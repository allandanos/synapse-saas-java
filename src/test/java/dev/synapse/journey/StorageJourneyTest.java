package dev.synapse.journey;

import static dev.synapse.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.storage.FileService;
import dev.synapse.storage.StorageBackend;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import dev.synapse.worker.JobRegistry;

/**
 * Files end to end over a real Postgres and the local-disk backend: the
 * {@code api_access} gate, multipart upload, the {@code storage_bytes} gauge
 * moving up and back down, download bytes and headers, the quota refusing an
 * upload before a byte is written, presigning answering 409 on local disk, and
 * the retention job releasing abandoned reservations.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.worker-enabled=false",
    "synapse.storage-root=${java.io.tmpdir}/synapse-java-storage-journey"
})
class StorageJourneyTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobRegistry jobs;
    @Autowired StorageBackend storage;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    @Test
    void uploadListDownloadDeleteMovesTheGaugeBothWays() throws Exception {
        Tenant tenant = api.makeTenant("files");
        assertThat(used(tenant)).isZero();

        Res uploaded = upload(tenant, "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(uploaded.status()).isEqualTo(201);
        assertThat(uploaded.body().get("size_bytes").asLong()).isEqualTo(5);
        assertThat(uploaded.text("content_type")).isEqualTo("text/plain");
        assertThat(uploaded.text("status")).isEqualTo("ready");
        assertThat(uploaded.text("organization_id")).isEqualTo(tenant.orgId());
        assertThat(uploaded.text("key")).isEqualTo(tenant.orgId() + "/a.txt");
        String fileId = uploaded.text("id");
        assertThat(used(tenant)).isEqualTo(5);

        Res listed = api.get("/v1/files", tenant.headers());
        assertThat(listed.header("X-Total-Count")).isEqualTo("1");
        assertThat(listed.body().get(0).get("id").asText()).isEqualTo(fileId);

        // The catalogued event and its audit row
        assertThat(outboxPayload("file.uploaded", UUID.fromString(fileId)))
            .containsEntry("file_id", fileId).containsEntry("name", "a.txt")
            .containsEntry("content_type", "text/plain").containsEntry("size_bytes", 5);
        Res auditUploaded = api.get("/v1/audit?event_type=file.uploaded", tenant.headers());
        assertThat(auditUploaded.body().get("data").findValues("target_id").stream().map(JsonNode::asText))
            .containsExactly(fileId);
        assertThat(auditUploaded.body().get("data").get(0).get("target_type").asText()).isEqualTo("file");

        MockHttpServletResponse download = api.raw(org.springframework.http.HttpMethod.GET, "/v1/files/" + fileId, tenant.headers());
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentAsByteArray()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(download.getContentType()).startsWith("text/plain");
        assertThat(download.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"a.txt\"");

        // Local disk has no presigned URLs in either direction
        JsonNode presign = assertProblem(api.post("/v1/files/" + fileId + "/presign", tenant.headers(), null),
            409, "presign unsupported");
        assertThat(presign.has("direct_upload_limit_bytes")).isFalse();
        JsonNode presignUpload = assertProblem(api.post("/v1/files/presign-upload", tenant.headers(),
            Map.of("name", "big.bin", "size_bytes", 1024, "content_type", "application/octet-stream")),
            409, "presign unsupported");
        assertThat(presignUpload.get("direct_upload_limit_bytes").asLong()).isEqualTo(FileService.MAX_DIRECT_UPLOAD_BYTES);

        // Another org sees neither the row nor the bytes
        Tenant other = api.makeTenant("files-other");
        assertProblem(api.get("/v1/files/" + fileId, other.headers()), 404, "not found");
        assertThat(api.get("/v1/files", other.headers()).body().size()).isZero();

        assertThat(api.delete("/v1/files/" + fileId, tenant.headers()).status()).isEqualTo(204);
        assertProblem(api.get("/v1/files/" + fileId, tenant.headers()), 404, "not found");
        assertThat(used(tenant)).isZero();
        assertThat(storage.head(tenant.orgId() + "/a.txt")).as("the object goes with the row").isNull();
        assertThat(api.get("/v1/files", tenant.headers()).body().size()).isZero();
        assertThat(api.get("/v1/audit?event_type=file.deleted", tenant.headers())
            .body().get("data").findValues("target_id").stream().map(JsonNode::asText)).containsExactly(fileId);
    }

    @Test
    void uploadsAreRefusedBeforeAByteIsWrittenWhenTheQuotaIsExhausted() throws Exception {
        Tenant tenant = api.makeTenant("files-quota");
        // Park the gauge on the free plan's 1 GiB ceiling, leaving room for 3 bytes
        long limit = 1024L * 1024 * 1024;
        jdbc.sql("""
                INSERT INTO usage_counters (organization_id, metric, period_start, quantity_total)
                VALUES (:org, 'storage_bytes', DATE '1970-01-01', :total)
                ON CONFLICT (organization_id, metric, period_start) DO UPDATE SET quantity_total = EXCLUDED.quantity_total
                """)
            .param("org", UUID.fromString(tenant.orgId())).param("total", limit - 3)
            .update();

        JsonNode problem = assertProblem(upload(tenant, "too-big.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)),
            402, "usage limit exceeded");
        assertThat(problem.get("metric").asText()).isEqualTo("storage_bytes");
        assertThat(problem.get("limit").asLong()).isEqualTo(limit);
        assertThat(used(tenant)).as("the level never moved").isEqualTo(limit - 3);
        assertThat(storage.head(tenant.orgId() + "/too-big.txt")).as("nothing was written").isNull();

        // What still fits is accepted
        assertThat(upload(tenant, "ok.txt", "text/plain", "ok!".getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(201);
        assertThat(used(tenant)).isEqualTo(limit);
    }

    @Test
    void multipartRulesAndTheDirectUploadCap() throws Exception {
        Tenant tenant = api.makeTenant("files-rules");

        // Not multipart at all
        assertProblem(api.post("/v1/files", tenant.headers(), Map.of("file", "x")), 400, "storage error");
        // Multipart, but no 'file' part
        MockMultipartHttpServletRequestBuilder builder = multipart("/v1/files");
        builder.file(new MockMultipartFile("other", "a.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8)));
        tenant.headers().forEach(builder::header);
        assertThat(mvc.perform(builder).andReturn().getResponse().getStatus()).isEqualTo(400);

        // Over the 10 MiB direct cap ⇒ 400, not a 413 from the container
        byte[] tooLarge = new byte[(int) FileService.MAX_DIRECT_UPLOAD_BYTES + 1];
        assertProblem(upload(tenant, "big.bin", "application/octet-stream", tooLarge), 400, "storage error");
        assertThat(used(tenant)).isZero();

        // A part with no declared type falls back to the octet-stream default
        Res untyped = upload(tenant, "raw.bin", null, new byte[] {1, 2, 3});
        assertThat(untyped.status()).isEqualTo(201);
        assertThat(untyped.text("content_type")).isEqualTo("application/octet-stream");
    }

    @Test
    void theRetentionJobReleasesAbandonedPendingUploads() throws Exception {
        Tenant tenant = api.makeTenant("files-stale");
        UUID orgId = UUID.fromString(tenant.orgId());
        UUID fileId = UUID.randomUUID();
        // A presigned reservation that never completed, older than twice the presign TTL
        jdbc.sql("""
                INSERT INTO stored_files (id, organization_id, key, name, content_type, size_bytes, status, created_at)
                VALUES (:id, :org, :key, 'stale.bin', 'application/octet-stream', 4096, 'pending', now() - interval '30 days')
                """)
            .param("id", fileId).param("org", orgId).param("key", orgId + "/stale.bin").update();
        jdbc.sql("""
                INSERT INTO usage_counters (organization_id, metric, period_start, quantity_total)
                VALUES (:org, 'storage_bytes', DATE '1970-01-01', 4096)
                ON CONFLICT (organization_id, metric, period_start) DO UPDATE SET quantity_total = 4096
                """)
            .param("org", orgId).update();

        // Pending rows are invisible to the list and to reads before the job even runs
        assertThat(api.get("/v1/files", tenant.headers()).body().size()).isZero();
        assertProblem(api.get("/v1/files/" + fileId, tenant.headers()), 404, "not found");

        assertThat(jobs.run("purge_expired")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM stored_files WHERE id = :id").param("id", fileId)
            .query(Boolean.class).single()).isTrue();
        assertThat(used(tenant)).as("the reserved bytes go back to the quota").isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Res upload(Tenant tenant, String filename, String contentType, byte[] data) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart("/v1/files");
        builder.file(new MockMultipartFile("file", filename, contentType, data));
        tenant.headers().forEach(builder::header);
        MockHttpServletResponse raw = mvc.perform(builder).andReturn().getResponse();
        String content = raw.getContentAsString();
        return new Res(raw.getStatus(), content.isEmpty() ? null : json.readTree(content), raw);
    }

    private Map<String, Object> outboxPayload(String eventType, UUID aggregateId) throws Exception {
        String payload = jdbc.sql("SELECT payload::text FROM outbox_events WHERE event_type = :type AND aggregate_id = :id")
            .param("type", eventType).param("id", aggregateId).query(String.class).single();
        return json.readValue(payload, Map.class);
    }

    private long used(Tenant tenant) throws Exception {
        Res res = api.get("/v1/usage/summary", tenant.headers());
        assertThat(res.status()).isEqualTo(200);
        for (JsonNode metric : res.body().get("metrics")) {
            if ("storage_bytes".equals(metric.get("metric").asText())) {
                return metric.get("used").asLong();
            }
        }
        return 0; // no counter row yet ⇒ nothing stored
    }
}
