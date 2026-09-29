package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import dev.synapse.billing.Signatures;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxRepository;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import dev.synapse.support.StubProviderServer;
import dev.synapse.webhooks.WebhookDeliveryRepository;
import dev.synapse.webhooks.WebhookDeliveryService;
import dev.synapse.worker.JobRegistry;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker end to end over a real Postgres: outbox fan-out to a live HTTP
 * endpoint with a signature the receiver can verify, the retry ladders up to
 * {@code exhausted} and {@code dead_at}, internal events staying internal,
 * partition pre-creation, retention, and the three emails — captured by an
 * in-process SMTP server, invoice PDF attached.
 *
 * <p>The scheduler is off: every job runs exactly when a test asks it to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.worker-enabled=false",
    "synapse.notifier=smtp"
})
class WorkerJourneyTest extends PostgresTestSupport {

    private static GreenMail smtp;

    @DynamicPropertySource
    static void smtpServer(DynamicPropertyRegistry registry) {
        if (smtp == null) {
            smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
            smtp.start();
        }
        registry.add("synapse.smtp-host", () -> "127.0.0.1");
        registry.add("synapse.smtp-port", () -> smtp.getSmtp().getPort());
        registry.add("synapse.smtp-from", () -> "billing@synapse.example.com");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobRegistry jobs;
    @Autowired OutboxRepository outbox;
    @Autowired WebhookDeliveryService webhooks;
    @Autowired TransactionTemplate transaction;
    @MockitoSpyBean WebhookDeliveryRepository deliveries;

    ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        smtp.purgeEmailFromAllMailboxes();
        // The other journeys leave unpublished rows behind (they never run the worker);
        // retire them so a batch claim only ever sees this test's own events.
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL AND dead_at IS NULL").update();
    }

    /** Drain the outbox however many batches it takes. */
    private int dispatchAll() {
        int dispatched = 0;
        for (int batch = 0; batch < 10; batch++) {
            int count = jobs.run("dispatch_outbox");
            if (count <= 0) {
                break;
            }
            dispatched += count;
        }
        return dispatched;
    }

    @Test
    void aPublicEventReachesASubscribedEndpointWithAVerifiableSignature() throws Exception {
        try (StubProviderServer receiver = new StubProviderServer()) {
            receiver.on("POST", "/hook", "{\"ok\":true}");
            Tenant tenant = api.makeTenant("fanout");
            UUID orgId = UUID.fromString(tenant.orgId());
            WebhookDeliveryService.Created endpoint = transaction.execute(status ->
                webhooks.createEndpoint(orgId, receiver.baseUrl() + "/hook", List.of(Events.INVOICE_PAID), "invoices only"));

            append(orgId, Events.INVOICE_PAID, Map.of("total_cents", 149900, "currency", "PHP"));
            assertThat(dispatchAll()).isPositive();

            // one pending delivery per subscribed endpoint, carrying the event payload verbatim
            Map<String, Object> delivery = onlyDelivery(orgId, Events.INVOICE_PAID);
            assertThat(delivery.get("status")).isEqualTo("pending");
            assertThat(delivery.get("attempts")).isEqualTo(0);
            assertThat(String.valueOf(delivery.get("payload"))).contains("149900");
            assertThat(publishedAt(Events.INVOICE_PAID, orgId)).isNotNull();

            assertThat(jobs.run("deliver_webhooks")).isEqualTo(1);

            StubProviderServer.Call call = receiver.lastCall();
            assertThat(call.method()).isEqualTo("POST");
            assertThat(call.headers().get("content-type")).isEqualTo("application/json");
            // The envelope the contract promises, and a signature the receiver can check
            JsonNode envelope = json.readTree(call.body());
            assertThat(envelope.get("event_type").asText()).isEqualTo(Events.INVOICE_PAID);
            assertThat(envelope.get("organization_id").asText()).isEqualTo(tenant.orgId());
            assertThat(envelope.get("id").asText()).isEqualTo(String.valueOf(delivery.get("id")));
            assertThat(envelope.at("/data/total_cents").asLong()).isEqualTo(149900);
            assertThat(envelope.get("created_at").isNull()).isFalse();

            String header = call.headers().get("x-synapse-signature");
            assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
            long timestamp = Long.parseLong(header.substring(2, header.indexOf(',')));
            String signature = header.substring(header.indexOf("v1=") + 3);
            assertThat(Signatures.verifySignature(call.body().getBytes(StandardCharsets.UTF_8), endpoint.secret(), timestamp, signature))
                .as("the endpoint's plaintext secret verifies the delivered body").isTrue();

            Map<String, Object> delivered = onlyDelivery(orgId, Events.INVOICE_PAID);
            assertThat(delivered.get("status")).isEqualTo("delivered");
            assertThat(delivered.get("last_response_code")).isEqualTo(200);
        }
    }

    @Test
    void internalEventsNeverReachAnEndpointAndFiltersAreHonoured() throws Exception {
        Tenant tenant = api.makeTenant("filters");
        UUID orgId = UUID.fromString(tenant.orgId());
        transaction.execute(status -> webhooks.createEndpoint(orgId, "http://127.0.0.1:1/never", List.of(Events.INVOICE_PAID), "narrow"));

        append(orgId, Events.INVOICE_EMAIL, Map.of("invoice_id", UUID.randomUUID().toString()));
        append(orgId, Events.SUBSCRIPTION_UPDATED, Map.of("status", "active"));
        append(orgId, Events.INVOICE_PAID, Map.of("total_cents", 1));
        jobs.run("dispatch_outbox");

        // internal ⇒ never fanned out; a filtered endpoint only sees what it asked for
        assertThat(deliveryCount(orgId, Events.INVOICE_EMAIL)).isZero();
        assertThat(deliveryCount(orgId, Events.SUBSCRIPTION_UPDATED)).isZero();
        assertThat(deliveryCount(orgId, Events.INVOICE_PAID)).isEqualTo(1);
        // …and all three are still marked published: fan-out is not the point of the outbox
        assertThat(publishedAt(Events.INVOICE_EMAIL, orgId)).isNotNull();
        assertThat(publishedAt(Events.SUBSCRIPTION_UPDATED, orgId)).isNotNull();
    }

    @Test
    void anUnreachableEndpointWalksTheLadderAndFinallyExhausts() throws Exception {
        try (StubProviderServer receiver = new StubProviderServer()) {
            receiver.failWith(500, "{\"error\":\"boom\"}");
            Tenant tenant = api.makeTenant("exhaust");
            UUID orgId = UUID.fromString(tenant.orgId());
            transaction.execute(status ->
                webhooks.createEndpoint(orgId, receiver.baseUrl() + "/hook", List.of(Events.INVOICE_PAID), null));
            append(orgId, Events.INVOICE_PAID, Map.of("total_cents", 1));
            dispatchAll();

            for (int attempt = 1; attempt <= WebhookDeliveryService.MAX_DELIVERY_ATTEMPTS; attempt++) {
                assertThat(jobs.run("deliver_webhooks")).isZero();
                Map<String, Object> row = onlyDelivery(orgId, Events.INVOICE_PAID);
                assertThat(row.get("attempts")).isEqualTo(attempt);
                assertThat(row.get("last_response_code")).isEqualTo(500);
                if (attempt < WebhookDeliveryService.MAX_DELIVERY_ATTEMPTS) {
                    assertThat(row.get("status")).as("attempt %d stays pending", attempt).isEqualTo("pending");
                    makeDue(orgId); // skip the 1m/5m/30m/2h/6h wait
                } else {
                    assertThat(row.get("status")).isEqualTo("exhausted");
                }
            }
            assertThat(receiver.calls()).hasSize(WebhookDeliveryService.MAX_DELIVERY_ATTEMPTS);
            // exhausted rows stay put: nothing re-sends them until an operator retries
            makeDue(orgId);
            assertThat(jobs.run("deliver_webhooks")).isZero();
            assertThat(receiver.calls()).hasSize(WebhookDeliveryService.MAX_DELIVERY_ATTEMPTS);
        }
    }

    @Test
    void anEndpointThatDisappearedFailsTheDeliveryWithoutRetrying() throws Exception {
        Tenant tenant = api.makeTenant("gone");
        UUID orgId = UUID.fromString(tenant.orgId());
        transaction.execute(status ->
            webhooks.createEndpoint(orgId, "http://127.0.0.1:1/hook", List.of(Events.INVOICE_PAID), null));
        append(orgId, Events.INVOICE_PAID, Map.of("total_cents", 1));
        dispatchAll();
        jdbc.sql("UPDATE webhook_endpoints SET is_active = false WHERE organization_id = :org").param("org", orgId).update();

        assertThat(jobs.run("deliver_webhooks")).isZero();
        Map<String, Object> row = onlyDelivery(orgId, Events.INVOICE_PAID);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(String.valueOf(row.get("last_error"))).contains("inactive");
    }

    /** A poison event must not pin the batch: it walks the 8-step ladder, then dies. */
    @Test
    void aPoisonOutboxRowIsDeadLetteredAfterEightAttempts() throws Exception {
        Tenant tenant = api.makeTenant("poison");
        UUID orgId = UUID.fromString(tenant.orgId());
        transaction.execute(status ->
            webhooks.createEndpoint(orgId, "http://127.0.0.1:1/hook", List.of(Events.INVOICE_PAID), null));
        // Drain the org's own lifecycle events first, then poison the fan-out
        dispatchAll();
        doThrow(new IllegalStateException("fan-out exploded")).when(deliveries)
            .insert(any(), any(), any(), anyString(), anyMap(), anyInt());

        append(orgId, Events.INVOICE_PAID, Map.of("total_cents", 1));
        for (int attempt = 1; attempt <= 8; attempt++) {
            assertThat(jobs.run("dispatch_outbox")).as("a failed event is not counted as dispatched").isZero();
            Map<String, Object> row = outboxRow(Events.INVOICE_PAID, orgId);
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat(String.valueOf(row.get("last_error"))).contains("fan-out exploded");
            assertThat(row.get("published_at")).as("a failed event is never marked published").isNull();
            if (attempt < 8) {
                assertThat(row.get("dead_at")).isNull();
                jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() WHERE organization_id = :org AND event_type = :type")
                    .param("org", orgId).param("type", Events.INVOICE_PAID).update();
            } else {
                assertThat(row.get("dead_at")).as("dead-lettered on the eighth attempt").isNotNull();
            }
        }
        // a dead row is never claimed again, however overdue it is
        jdbc.sql("UPDATE outbox_events SET next_attempt_at = now() WHERE organization_id = :org").param("org", orgId).update();
        assertThat(jobs.run("dispatch_outbox")).isZero();
        assertThat(outboxRow(Events.INVOICE_PAID, orgId).get("attempts")).isEqualTo(8);
    }

    @Test
    void ensurePartitionsCreatesThisMonthAndThreeMore() {
        assertThat(jobs.run("ensure_partitions")).isEqualTo(4);
        List<String> partitions = jdbc.sql("""
                SELECT to_char((date_trunc('month', now()) + make_interval(months => g))::date, '"usage_events_y"YYYY"m"MM') AS name
                FROM generate_series(0, 3) AS g
                """)
            .query(String.class).list();
        for (String name : partitions) {
            assertThat(tableExists(name)).as("%s exists", name).isTrue();
        }
        // idempotent: a second run changes nothing
        assertThat(jobs.run("ensure_partitions")).isEqualTo(4);
    }

    @Test
    void purgeExpiredHonoursEachRetentionWindow() throws Exception {
        Tenant tenant = api.makeTenant("retention");
        UUID orgId = UUID.fromString(tenant.orgId());
        transaction.execute(status -> webhooks.createEndpoint(orgId, "http://127.0.0.1:1/hook", List.of(), null));
        UUID endpointId = jdbc.sql("SELECT id FROM webhook_endpoints WHERE organization_id = :org").param("org", orgId)
            .query(UUID.class).single();

        UUID recentDelivered = insertAgedDelivery(endpointId, orgId, "delivered", 29);
        UUID oldDelivered = insertAgedDelivery(endpointId, orgId, "delivered", 31);
        UUID oldExhausted = insertAgedDelivery(endpointId, orgId, "exhausted", 31);
        UUID ancientExhausted = insertAgedDelivery(endpointId, orgId, "exhausted", 91);
        append(orgId, Events.INVOICE_PAID, Map.of("total_cents", 1));
        jdbc.sql("UPDATE outbox_events SET published_at = now() - interval '8 days' WHERE organization_id = :org AND event_type = :type")
            .param("org", orgId).param("type", Events.INVOICE_PAID).update();

        assertThat(jobs.run("purge_expired")).isEqualTo(1);

        assertThat(deliveryExists(recentDelivered)).as("inside the 30-day window").isTrue();
        assertThat(deliveryExists(oldDelivered)).as("past the 30-day window").isFalse();
        assertThat(deliveryExists(oldExhausted)).as("the failure audit trail is kept 90 days").isTrue();
        assertThat(deliveryExists(ancientExhausted)).as("past the 90-day window").isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE organization_id = :org AND event_type = :type")
            .param("org", orgId).param("type", Events.INVOICE_PAID).query(Long.class).single())
            .as("published outbox rows are kept 7 days").isZero();
    }

    @Test
    void theInviteResetAndInvoiceEmailsAreSentAfterTheOutboxCommits() throws Exception {
        Tenant tenant = api.makeTenant("emails");
        // A framework-drafted invoice has no provider customer, so the billing contact
        // comes from the org's settings — the reference's second recipient hop.
        String billingEmail = "billing-" + ApiClient.uid() + "@conformance.example.com";
        assertThat(api.patch("/v1/orgs/current", tenant.headers(),
            Map.of("settings", Map.of("billing_email", billingEmail))).status()).isEqualTo(200);
        String memberEmail = "member-" + ApiClient.uid() + "@conformance.example.com";
        assertThat(api.post("/v1/orgs/current/members/invite", tenant.headers(), Map.of("email", memberEmail)).status()).isEqualTo(201);
        assertThat(api.post("/v1/auth/forgot-password", Map.of(), Map.of("email", tenant.email())).status()).isEqualTo(202);
        assertThat(api.post("/v1/billing/checkout/confirm", tenant.headers(), Map.of("plan_key", "pro")).status()).isEqualTo(200);
        String invoiceId = api.post("/v1/billing/invoices/draft", tenant.headers(), Map.of()).text("id");
        Res finalized = api.post("/v1/billing/invoices/" + invoiceId + "/finalize", tenant.headers(), null);
        assertThat(finalized.status()).isEqualTo(200);

        // Nothing is sent until the events are durably published
        assertThat(smtp.getReceivedMessages()).isEmpty();
        assertThat(dispatchAll()).isPositive();
        assertThat(smtp.waitForIncomingEmail(10_000, 3)).isTrue();

        MimeMessage invite = messageWithSubjectContaining("invited to");
        assertThat(invite.getAllRecipients()[0].toString()).isEqualTo(memberEmail);
        assertThat(body(invite)).contains("/register?invite=");

        MimeMessage reset = messageWithSubjectContaining("Reset your password");
        assertThat(reset.getAllRecipients()[0].toString()).isEqualTo(tenant.email());
        assertThat(body(reset)).contains("/login?reset=").contains("30 minutes");

        MimeMessage invoice = messageWithSubjectContaining("Invoice " + finalized.text("number"));
        assertThat(invoice.getAllRecipients()[0].toString()).isEqualTo(billingEmail);
        assertThat(invoice.getSubject()).contains("due");
        assertThat(invoice.getContent()).isInstanceOf(MimeMultipart.class);
        MimeMultipart parts = (MimeMultipart) invoice.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(1).getFileName()).isEqualTo("invoice-" + finalized.text("number") + ".pdf");
        byte[] attachment = parts.getBodyPart(1).getInputStream().readAllBytes();
        assertThat(new String(attachment, 0, 4)).isEqualTo("%PDF");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void append(UUID organizationId, String eventType, Map<String, Object> payload) {
        transaction.executeWithoutResult(status ->
            outbox.append(eventType, "test", UUID.randomUUID(), organizationId, payload));
    }

    private UUID insertAgedDelivery(UUID endpointId, UUID organizationId, String status, int ageDays) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO webhook_deliveries (id, endpoint_id, organization_id, event_type, payload, status, attempts, max_attempts,
                                                created_at)
                VALUES (:id, :endpoint, :org, 'test.event', '{}'::jsonb, :status, 1, 6, now() - make_interval(days => :age))
                """)
            .param("id", id).param("endpoint", endpointId).param("org", organizationId).param("status", status).param("age", ageDays)
            .update();
        return id;
    }

    private boolean deliveryExists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE id = :id").param("id", id).query(Long.class).single() == 1;
    }

    private boolean tableExists(String name) {
        return jdbc.sql("SELECT count(*) FROM pg_class WHERE relname = :name").param("name", name).query(Long.class).single() > 0;
    }

    private long deliveryCount(UUID organizationId, String eventType) {
        return jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE organization_id = :org AND event_type = :type")
            .param("org", organizationId).param("type", eventType).query(Long.class).single();
    }

    private Map<String, Object> onlyDelivery(UUID organizationId, String eventType) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, status, attempts, last_response_code, last_error, payload::text AS payload
                FROM webhook_deliveries WHERE organization_id = :org AND event_type = :type
                """)
            .param("org", organizationId).param("type", eventType)
            .query((rs, i) -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id", rs.getObject("id", UUID.class).toString());
                row.put("status", rs.getString("status"));
                row.put("attempts", rs.getInt("attempts"));
                row.put("last_response_code", dev.synapse.core.db.Rows.intOrNull(rs, "last_response_code"));
                row.put("last_error", rs.getString("last_error"));
                row.put("payload", rs.getString("payload"));
                return row;
            })
            .list();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private void makeDue(UUID organizationId) {
        jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE organization_id = :org").param("org", organizationId).update();
    }

    private Object publishedAt(String eventType, UUID organizationId) {
        return jdbc.sql("SELECT published_at FROM outbox_events WHERE event_type = :type AND organization_id = :org")
            .param("type", eventType).param("org", organizationId).query(Object.class).single();
    }

    private Map<String, Object> outboxRow(String eventType, UUID organizationId) {
        return jdbc.sql("SELECT attempts, last_error, dead_at, published_at FROM outbox_events WHERE event_type = :type "
                + "AND organization_id = :org")
            .param("type", eventType).param("org", organizationId)
            .query((rs, i) -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("attempts", rs.getInt("attempts"));
                row.put("last_error", rs.getString("last_error"));
                row.put("dead_at", rs.getObject("dead_at"));
                row.put("published_at", rs.getObject("published_at"));
                return row;
            })
            .single();
    }

    private MimeMessage messageWithSubjectContaining(String fragment) throws Exception {
        for (MimeMessage message : smtp.getReceivedMessages()) {
            if (message.getSubject().contains(fragment)) {
                return message;
            }
        }
        throw new AssertionError("No message with a subject containing '" + fragment + "'");
    }

    private static String body(MimeMessage message) throws IOException, jakarta.mail.MessagingException {
        Object content = message.getContent();
        return content instanceof MimeMultipart multipart ? String.valueOf(multipart.getBodyPart(0).getContent())
            : String.valueOf(content);
    }
}
