package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;

import dev.synapse.core.db.Json;
import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.PlanCatalog;
import dev.synapse.subscriptions.PlanCatalog.PlanDefinition;
import dev.synapse.subscriptions.PlanCatalogLoader;
import dev.synapse.subscriptions.PlanRepository;
import dev.synapse.subscriptions.ProviderCatalogPush;
import dev.synapse.support.PostgresTestSupport;
import dev.synapse.support.StubProviderServer;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan sync to a billing provider (reference: {@code cli.py:_push_provider_catalog}
 * + {@code stripe_provider.upsert_product_and_price}), against a local stub Stripe.
 */
@SpringBootTest
class PlanSyncJourneyTest extends PostgresTestSupport {

    static final StubProviderServer STRIPE;

    static {
        try {
            STRIPE = new StubProviderServer()
                .on("POST", "/products", "{\"id\":\"prod_test\"}")
                .on("POST", "/prices", "{\"id\":\"price_test\"}");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void stripeStub(DynamicPropertyRegistry registry) {
        registry.add("synapse.provider-api-base.stripe", STRIPE::baseUrl);
        registry.add("synapse.stripe-secret-key", () -> "sk_test_plan_sync");
    }

    @AfterAll
    static void stopStub() {
        STRIPE.close();
    }

    @Autowired ProviderCatalogPush push;
    @Autowired PlanCatalogLoader loader;
    @Autowired PlanRepository plans;
    @Autowired JdbcClient jdbc;
    @Autowired Json json;

    @Test
    void aDryRunDescribesEveryPaidPlanAndCallsNothing() {
        PlanCatalog catalog = loader.load();
        int before = STRIPE.calls().size();
        List<String> lines = push.push("stripe", catalog, false);

        List<String> paid = catalog.plans().stream().filter(p -> p.priceCents() != null).map(PlanDefinition::key).toList();
        assertThat(paid).isNotEmpty();
        assertThat(lines).hasSameSizeAs(paid);
        assertThat(lines).allMatch(line -> line.startsWith("[dry-run] stripe: upsert product+price for "));
        // Custom-priced plans have nothing to push
        catalog.plans().stream().filter(p -> p.priceCents() == null)
            .forEach(p -> assertThat(lines).noneMatch(line -> line.contains(" " + p.key() + " ")));
        assertThat(STRIPE.calls()).hasSize(before);
    }

    @Test
    void applyCreatesProductAndPriceAndRecordsTheRefs() {
        PlanCatalog catalog = loader.load();
        List<String> lines = push.push("stripe", catalog, true);
        assertThat(lines).isNotEmpty();

        PlanDefinition first = catalog.plans().stream().filter(p -> p.priceCents() != null).findFirst().orElseThrow();
        StubProviderServer.Call product = STRIPE.calls().stream()
            .filter(c -> c.path().equals("/products")).findFirst().orElseThrow();
        assertThat(product.body()).contains("name=").contains("metadata%5Bplan_key%5D=");
        // Stripe authenticates the secret key as HTTP basic with an empty password
        assertThat(product.headers().get("authorization"))
            .isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("sk_test_plan_sync:".getBytes()));

        StubProviderServer.Call price = STRIPE.calls().stream()
            .filter(c -> c.path().equals("/prices")).findFirst().orElseThrow();
        assertThat(price.body()).contains("product=prod_test")
            .contains("unit_amount=" + first.priceCents())
            .contains("recurring%5Binterval%5D=");

        Plan stored = plans.findByKey(first.key(), true).orElseThrow();
        Map<String, Object> refs = json.readMap(jdbc.sql("SELECT provider_refs::text FROM plans WHERE id = :id")
            .param("id", stored.id()).query(String.class).single());
        assertThat(refs).containsKey("stripe");
        assertThat(refs.get("stripe").toString()).contains("prod_test").contains("price_test");
    }
}
