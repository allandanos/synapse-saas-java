package dev.synapse.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.synapse.core.errors.CatalogInvalidError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Catalog loading + validation (reference: {@code tests/unit/subscriptions/test_catalog.py}). */
class PlanCatalogTest {

    static final String SHIPPED = "classpath:config/plans.yaml";

    static PlanCatalog shipped() {
        return new PlanCatalogLoader(null).load(SHIPPED);
    }

    @Nested
    class ShippedCatalog {

        @Test
        void loadsAndValidates() {
            assertThat(shipped().plans().size()).isGreaterThanOrEqualTo(4);
        }

        @Test
        void planKeys() {
            assertThat(shipped().plans().stream().map(PlanCatalog.PlanDefinition::key)).contains("free", "starter", "pro", "enterprise");
        }

        @Test
        void pricesAreIntegerMinorUnits() {
            PlanCatalog catalog = shipped();
            assertThat(catalog.plan("free").orElseThrow().priceCents()).isEqualTo(0L);
            assertThat(catalog.plan("starter").orElseThrow().priceCents()).isEqualTo(49900L); // ₱499.00
            assertThat(catalog.plan("pro").orElseThrow().priceCents()).isEqualTo(199900L); // ₱1,999.00
            assertThat(catalog.plan("enterprise").orElseThrow().price()).isEqualTo("custom");
        }

        @Test
        void enterpriseNotPublic() {
            assertThat(shipped().plan("enterprise").orElseThrow().isPublic()).isFalse();
        }

        @Test
        void unlimitedLimitsAreNull() {
            PlanCatalog.PlanDefinition pro = shipped().plan("pro").orElseThrow();
            assertThat(pro.limits()).containsKey("projects");
            assertThat(pro.limits().get("projects")).isNull();
        }

        @Test
        void packagedCatalogIsTheReferencesCatalog() throws IOException {
            Path reference = Path.of("../synapse-saas/src/synapse_saas/config/plans.yaml");
            assumeTrue(Files.exists(reference), "reference checkout not beside this repo");
            String packaged = new String(getClass().getClassLoader().getResourceAsStream("config/plans.yaml").readAllBytes(), StandardCharsets.UTF_8);
            assertThat(packaged).isEqualTo(Files.readString(reference));
        }
    }

    @Nested
    class ValidationFailures {

        @TempDir Path tmp;

        private Path write(String content) throws IOException {
            Path path = tmp.resolve("plans.yaml");
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return path;
        }

        private static String errors(Throwable t) {
            return String.valueOf(((CatalogInvalidError) t).extras().get("errors"));
        }

        @Test
        void unknownFeatureRejected() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - key: pro
                    name: Pro
                    price_cents: 100
                    features: [nonexistent_feature]
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class)
                .satisfies(t -> assertThat(errors(t)).contains("nonexistent_feature"));
        }

        @Test
        void unknownMetricInLimits() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - key: pro
                    name: Pro
                    price_cents: 100
                    limits: {warp_drives: 5}
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class)
                .satisfies(t -> assertThat(errors(t)).contains("warp_drives"));
        }

        @Test
        void duplicatePlanKeys() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - {key: pro, name: Pro, price_cents: 100}
                  - {key: pro, name: Pro Again, price_cents: 200}
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class)
                .satisfies(t -> assertThat(errors(t)).contains("duplicate plan"));
        }

        @Test
        void priceCentsAndCustomBothSet() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - {key: pro, name: Pro, price_cents: 100, price: custom}
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class);
        }

        @Test
        void missingPriceRejected() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - {key: pro, name: Pro}
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class);
        }

        @Test
        void publicPlanRequiresConcretePrice() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - {key: pro, name: Pro, price: custom, is_public: true, is_custom: true}
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class)
                .satisfies(t -> assertThat(errors(t)).contains("concrete price"));
        }

        @Test
        void missingFile() {
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(tmp.resolve("nope.yaml")))
                .isInstanceOf(CatalogInvalidError.class).hasMessageContaining("not found");
        }

        @Test
        void malformedYaml() throws IOException {
            Path path = write("version: [unclosed");
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path))
                .isInstanceOf(CatalogInvalidError.class).hasMessageContaining("not valid YAML");
        }

        @Test
        void allErrorsReportedAtOnce() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - {key: pro, name: Pro, price_cents: 100, features: [ghost], limits: {phantom: 1}}
                  - {key: pro, name: Dup, price_cents: 200}
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class)
                .satisfies(t -> assertThat(errors(t)).contains("ghost").contains("phantom").contains("duplicate plan"));
        }

        @Test
        void extraKeysAreForbidden() throws IOException {
            Path path = write("""
                version: 1
                features: [{key: basic_dashboard, name: Basic, colour: red}]
                metrics: [{key: users, name: Users, kind: gauge}]
                plans:
                  - {key: pro, name: Pro, price_cents: 100}
                """);
            assertThatThrownBy(() -> new PlanCatalogLoader(null).load(path)).isInstanceOf(CatalogInvalidError.class)
                .hasMessageContaining("failed validation").hasMessageContaining("Extra inputs are not permitted");
        }
    }

    // ── Overage pricing in the catalog ────────────────────────────────────────────

    static Map<String, Object> catalog() {
        return new java.util.LinkedHashMap<>(Map.of(
            "version", 1,
            "features", List.of(Map.of("key", "f", "name", "F")),
            "metrics", List.of(
                Map.of("key", "ai_tokens", "name", "AI", "kind", "counter", "overage", Map.of("unit", 1000, "price_cents", 20)),
                Map.of("key", "seats", "name", "Seats", "kind", "gauge")),
            "plans", List.of(
                Map.of("key", "starter", "name", "S", "price_cents", 100, "limits", Map.of("ai_tokens", 10)),
                Map.of("key", "pro", "name", "P", "price_cents", 200, "limits", Map.of("ai_tokens", 100, "seats", 5),
                    "overage", Map.of("ai_tokens", Map.of("unit", 1000, "price_cents", 15))))));
    }

    @Nested
    class OverageCatalog {

        @Test
        void planOverrideBeatsMetricDefault() {
            PlanCatalog catalog = PlanCatalog.fromRaw(catalog(), null);
            assertThat(catalog.overageFor("starter", "ai_tokens").orElseThrow().priceCents()).isEqualTo(20);
            assertThat(catalog.overageFor("pro", "ai_tokens").orElseThrow().priceCents()).isEqualTo(15);
            assertThat(catalog.overageFor("pro", "seats")).isEmpty(); // unpriced ⇒ enforced, never billed
        }

        @Test
        @SuppressWarnings("unchecked")
        void overageForAnUnlimitedMetricIsRejected() {
            Map<String, Object> bad = catalog();
            List<Map<String, Object>> plans = new java.util.ArrayList<>((List<Map<String, Object>>) bad.get("plans"));
            Map<String, Object> starter = new java.util.LinkedHashMap<>(plans.get(0));
            starter.put("overage", Map.of("seats", Map.of("unit", 1, "price_cents", 5))); // starter never limits seats
            plans.set(0, starter);
            bad.put("plans", plans);
            assertThatThrownBy(() -> PlanCatalog.fromRaw(bad, null)).isInstanceOf(CatalogInvalidError.class)
                .satisfies(t -> assertThat(String.valueOf(((CatalogInvalidError) t).extras())).contains("prices overage for metrics it does not limit"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void unitMustBePositive() {
            Map<String, Object> bad = catalog();
            List<Map<String, Object>> metrics = new java.util.ArrayList<>((List<Map<String, Object>>) bad.get("metrics"));
            Map<String, Object> aiTokens = new java.util.LinkedHashMap<>(metrics.get(0));
            aiTokens.put("overage", Map.of("unit", 0, "price_cents", 20));
            metrics.set(0, aiTokens);
            bad.put("metrics", metrics);
            assertThatThrownBy(() -> PlanCatalog.fromRaw(bad, null)).isInstanceOf(CatalogInvalidError.class).hasMessageContaining("failed validation");
        }
    }
}
