package dev.synapse.subscriptions;

import dev.synapse.core.errors.CatalogInvalidError;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The validated plan catalog (reference: {@code subscriptions/catalog.py}).
 *
 * <p>{@link #fromRaw(Map, String)} is the pydantic model: field shapes and
 * constraints first ("Plans file failed validation"), then the cross checks
 * — unknown feature/metric references, duplicate keys, overage on unlimited
 * metrics, public plans without a concrete price — reported all at once as
 * a 400 {@code plan_catalog_invalid} with {@code errors[]}. The catalog is the
 * pricing source of truth; the database is a projection of it ({@link PlanCatalogSync}).
 */
public record PlanCatalog(int version, Defaults defaults, List<FeatureDefinition> features,
                          List<MetricDefinition> metrics, List<PlanDefinition> plans) {

    static final Pattern KEY = Pattern.compile("^[a-z0-9_]+$");
    static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    static final Pattern INTERVAL = Pattern.compile("^(month|year)$");

    public record Defaults(String currency, String interval, int trialDays, Double softLimitRatio) {
        public static final Defaults STANDARD = new Defaults("PHP", "month", 0, 0.8);
    }

    public record FeatureDefinition(String key, String name, String category) {}

    /** Price for usage beyond a limit: {@code priceCents} per {@code unit} units (ceil). */
    public record OverageDefinition(int unit, long priceCents) {}

    public record MetricDefinition(String key, String name, String kind, String unit, Double softLimitRatio, OverageDefinition overage) {}

    public record PlanDefinition(String key, String name, String description, Long priceCents, String price, String currency,
                                 String interval, boolean isPublic, boolean isCustom, Integer trialDays, int sortOrder,
                                 List<String> features, Map<String, Long> limits, Map<String, OverageDefinition> overage) {
        public PlanDefinition {
            features = List.copyOf(features);
            limits = new LinkedHashMap<>(limits);
            overage = new LinkedHashMap<>(overage);
        }

        public boolean isCustomPriced() {
            return "custom".equals(price);
        }
    }

    public PlanCatalog {
        features = List.copyOf(features);
        metrics = List.copyOf(metrics);
        plans = List.copyOf(plans);
    }

    public Set<String> featureKeys() {
        return features.stream().map(FeatureDefinition::key).collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> metricKeys() {
        return metrics.stream().map(MetricDefinition::key).collect(Collectors.toUnmodifiableSet());
    }

    public Optional<PlanDefinition> plan(String key) {
        return plans.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    public Optional<MetricDefinition> metric(String key) {
        return metrics.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    /** Effective overage pricing: the plan's override, else the metric's default. */
    public Optional<OverageDefinition> overageFor(String planKey, String metric) {
        Optional<PlanDefinition> plan = plan(planKey);
        if (plan.isPresent() && plan.get().overage().containsKey(metric)) {
            return Optional.of(plan.get().overage().get(metric));
        }
        return metric(metric).map(MetricDefinition::overage);
    }

    // ── Parsing + validation ─────────────────────────────────────────────────────

    /** Build and validate from the parsed YAML document; {@code path} only decorates errors. */
    public static PlanCatalog fromRaw(Object document, String path) {
        Schema schema = new Schema(path);
        PlanCatalog catalog = schema.catalog(document);
        schema.raise();
        catalog.crossValidate();
        return catalog;
    }

    private void crossValidate() {
        List<String> errors = new ArrayList<>();
        List<String> featureKeys = features.stream().map(FeatureDefinition::key).toList();
        Set<String> dupFeatures = duplicates(featureKeys);
        if (!dupFeatures.isEmpty()) {
            errors.add("duplicate feature keys: " + pyList(dupFeatures));
        }
        List<String> metricKeys = metrics.stream().map(MetricDefinition::key).toList();
        Set<String> dupMetrics = duplicates(metricKeys);
        if (!dupMetrics.isEmpty()) {
            errors.add("duplicate metric keys: " + pyList(dupMetrics));
        }
        List<String> planKeys = plans.stream().map(PlanDefinition::key).toList();
        Set<String> dupPlans = duplicates(planKeys);
        if (!dupPlans.isEmpty()) {
            errors.add("duplicate plan keys: " + pyList(dupPlans));
        }
        Set<String> featureSet = new LinkedHashSet<>(featureKeys);
        Set<String> metricSet = new LinkedHashSet<>(metricKeys);
        for (PlanDefinition plan : plans) {
            Set<String> unknown = new TreeSet<>(plan.features());
            unknown.removeAll(featureSet);
            if (!unknown.isEmpty()) {
                errors.add("plan " + pyRepr(plan.key()) + " references unknown features: " + pyList(unknown));
            }
            Set<String> unknownMetrics = new TreeSet<>(plan.limits().keySet());
            unknownMetrics.removeAll(metricSet);
            if (!unknownMetrics.isEmpty()) {
                errors.add("plan " + pyRepr(plan.key()) + " limits unknown metrics: " + pyList(unknownMetrics));
            }
            Set<String> unpriced = new TreeSet<>(plan.overage().keySet());
            unpriced.removeAll(plan.limits().keySet());
            if (!unpriced.isEmpty()) {
                errors.add("plan " + pyRepr(plan.key()) + " prices overage for metrics it does not limit: " + pyList(unpriced));
            }
            if (plan.isPublic() && plan.priceCents() == null) {
                errors.add("public plan " + pyRepr(plan.key()) + " must have a concrete price_cents");
            }
        }
        if (!errors.isEmpty()) {
            throw new CatalogInvalidError("Invalid plan catalog", Map.of("errors", List.copyOf(errors)));
        }
    }

    private static Set<String> duplicates(List<String> keys) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> dups = new TreeSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                dups.add(key);
            }
        }
        return dups;
    }

    /** Python's {@code repr} of a sorted list of strings, as the reference embeds it. */
    static String pyList(Collection<String> values) {
        return values.stream().sorted().map(PlanCatalog::pyRepr).collect(Collectors.joining(", ", "[", "]"));
    }

    static String pyRepr(String value) {
        return "'" + value + "'";
    }

    // ── Field-level schema (pydantic's constraints, extra="forbid") ──────────────

    private static final class Schema {
        private final String path;
        private final List<String> errors = new ArrayList<>();

        Schema(String path) {
            this.path = path;
        }

        void raise() {
            if (!errors.isEmpty()) {
                Map<String, Object> extras = new LinkedHashMap<>();
                if (path != null) {
                    extras.put("path", path);
                }
                extras.put("errors", List.copyOf(errors));
                throw new CatalogInvalidError("Plans file failed validation: " + String.join("; ", errors), extras);
            }
        }

        PlanCatalog catalog(Object document) {
            Map<String, Object> root = object(document, "catalog");
            if (root == null) {
                raise();
            }
            forbidExtra(root, "catalog", Set.of("version", "defaults", "features", "metrics", "plans"));
            int version = integer(root.get("version"), "version", true, 0);
            Defaults defaults = defaults(root.get("defaults"));
            List<FeatureDefinition> features = new ArrayList<>();
            for (Object item : list(root.get("features"), "features", true)) {
                features.add(feature(item));
            }
            List<MetricDefinition> metrics = new ArrayList<>();
            for (Object item : list(root.get("metrics"), "metrics", true)) {
                metrics.add(metric(item));
            }
            List<PlanDefinition> plans = new ArrayList<>();
            for (Object item : list(root.get("plans"), "plans", true)) {
                plans.add(plan(item));
            }
            return new PlanCatalog(version, defaults, features.stream().filter(java.util.Objects::nonNull).toList(),
                metrics.stream().filter(java.util.Objects::nonNull).toList(), plans.stream().filter(java.util.Objects::nonNull).toList());
        }

        Defaults defaults(Object raw) {
            if (raw == null) {
                return Defaults.STANDARD;
            }
            Map<String, Object> node = object(raw, "defaults");
            if (node == null) {
                return Defaults.STANDARD;
            }
            String currency = pattern(string(node.get("currency"), "defaults.currency", false, "PHP"), CURRENCY, "defaults.currency");
            String interval = pattern(string(node.get("interval"), "defaults.interval", false, "month"), INTERVAL, "defaults.interval");
            int trialDays = nonNegative(integer(node.get("trial_days"), "defaults.trial_days", false, 0), "defaults.trial_days");
            Double soft = node.containsKey("soft_limit_ratio") ? ratio(node.get("soft_limit_ratio"), "defaults.soft_limit_ratio") : 0.8;
            return new Defaults(currency, interval, trialDays, soft);
        }

        FeatureDefinition feature(Object raw) {
            Map<String, Object> node = object(raw, "feature");
            if (node == null) {
                return null;
            }
            forbidExtra(node, "feature", Set.of("key", "name", "category"));
            return new FeatureDefinition(pattern(string(node.get("key"), "feature.key", true, null), KEY, "feature.key"),
                string(node.get("name"), "feature.name", true, null), string(node.get("category"), "feature.category", false, null));
        }

        OverageDefinition overage(Object raw, String where) {
            Map<String, Object> node = object(raw, where);
            if (node == null) {
                return null;
            }
            forbidExtra(node, where, Set.of("unit", "price_cents"));
            int unit = integer(node.get("unit"), where + ".unit", false, 1);
            if (unit < 1) {
                errors.add(where + ".unit: Input should be greater than or equal to 1");
            }
            long price = longValue(node.get("price_cents"), where + ".price_cents", true, 0L);
            if (price < 0) {
                errors.add(where + ".price_cents: Input should be greater than or equal to 0");
            }
            return new OverageDefinition(unit, price);
        }

        MetricDefinition metric(Object raw) {
            Map<String, Object> node = object(raw, "metric");
            if (node == null) {
                return null;
            }
            forbidExtra(node, "metric", Set.of("key", "name", "kind", "unit", "soft_limit_ratio", "overage"));
            String key = pattern(string(node.get("key"), "metric.key", true, null), KEY, "metric.key");
            String kind = string(node.get("kind"), "metric.kind", true, null);
            if (kind != null && !kind.equals("counter") && !kind.equals("gauge")) {
                errors.add("metric.kind: Input should be 'counter' or 'gauge'");
            }
            Double soft = node.get("soft_limit_ratio") == null ? null : ratio(node.get("soft_limit_ratio"), "metric.soft_limit_ratio");
            OverageDefinition overage = node.get("overage") == null ? null : overage(node.get("overage"), "metric.overage");
            return new MetricDefinition(key, string(node.get("name"), "metric.name", true, null), kind,
                string(node.get("unit"), "metric.unit", false, null), soft, overage);
        }

        PlanDefinition plan(Object raw) {
            Map<String, Object> node = object(raw, "plan");
            if (node == null) {
                return null;
            }
            forbidExtra(node, "plan", Set.of("key", "name", "description", "price_cents", "price", "currency", "interval",
                "is_public", "is_custom", "trial_days", "sort_order", "features", "limits", "overage"));
            String key = pattern(string(node.get("key"), "plan.key", true, null), KEY, "plan.key");
            Long priceCents = node.get("price_cents") == null ? null : longValue(node.get("price_cents"), "plan.price_cents", false, 0L);
            if (priceCents != null && priceCents < 0) {
                errors.add("plan.price_cents: Input should be greater than or equal to 0");
            }
            String price = string(node.get("price"), "plan.price", false, null);
            if (price != null && !price.equals("custom")) {
                errors.add("plan.price: Input should be 'custom'");
            }
            String currency = node.get("currency") == null ? null : pattern(string(node.get("currency"), "plan.currency", false, null), CURRENCY, "plan.currency");
            String interval = node.get("interval") == null ? null : pattern(string(node.get("interval"), "plan.interval", false, null), INTERVAL, "plan.interval");
            boolean isPublic = bool(node.get("is_public"), "plan.is_public", true);
            boolean isCustom = bool(node.get("is_custom"), "plan.is_custom", false);
            Integer trialDays = node.get("trial_days") == null ? null : nonNegative(integer(node.get("trial_days"), "plan.trial_days", false, 0), "plan.trial_days");
            int sortOrder = integer(node.get("sort_order"), "plan.sort_order", false, 0);
            List<String> features = new ArrayList<>();
            for (Object item : list(node.get("features"), "plan.features", false)) {
                features.add(string(item, "plan.features[]", true, null));
            }
            Map<String, Long> limits = new LinkedHashMap<>();
            Map<String, Object> limitsNode = node.get("limits") == null ? Map.of() : object(node.get("limits"), "plan.limits");
            if (limitsNode != null) {
                limitsNode.forEach((metric, value) -> limits.put(metric, value == null ? null : longValue(value, "plan.limits." + metric, false, 0L)));
            }
            Map<String, OverageDefinition> overage = new LinkedHashMap<>();
            Map<String, Object> overageNode = node.get("overage") == null ? Map.of() : object(node.get("overage"), "plan.overage");
            if (overageNode != null) {
                overageNode.forEach((metric, value) -> overage.put(metric, overage(value, "plan.overage." + metric)));
            }
            // Exactly one of price_cents / price: custom (the reference's model validator)
            boolean hasCents = priceCents != null;
            boolean custom = "custom".equals(price);
            if (hasCents && custom) {
                errors.add("plan " + pyRepr(key) + ": set either price_cents or price: custom, not both");
            }
            if (!hasCents && !custom) {
                errors.add("plan " + pyRepr(key) + ": must set price_cents or price: custom");
            }
            return new PlanDefinition(key, string(node.get("name"), "plan.name", true, null), string(node.get("description"), "plan.description", false, null),
                priceCents, price, currency, interval, isPublic, isCustom, trialDays, sortOrder,
                features.stream().filter(java.util.Objects::nonNull).toList(), limits, overage);
        }

        // ── primitives ───────────────────────────────────────────────────────────

        @SuppressWarnings("unchecked")
        Map<String, Object> object(Object raw, String where) {
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
            errors.add(where + ": Input should be a valid dictionary");
            return null;
        }

        List<Object> list(Object raw, String where, boolean required) {
            if (raw == null) {
                if (required) {
                    errors.add(where + ": Field required");
                }
                return List.of();
            }
            if (raw instanceof List<?> l) {
                return new ArrayList<>(l);
            }
            errors.add(where + ": Input should be a valid list");
            return List.of();
        }

        String string(Object raw, String where, boolean required, String fallback) {
            if (raw == null) {
                if (required) {
                    errors.add(where + ": Field required");
                }
                return fallback;
            }
            if (raw instanceof String s) {
                return s;
            }
            errors.add(where + ": Input should be a valid string");
            return fallback;
        }

        String pattern(String value, Pattern pattern, String where) {
            if (value != null && !pattern.matcher(value).matches()) {
                errors.add(where + ": String should match pattern '" + pattern.pattern() + "'");
            }
            return value;
        }

        int integer(Object raw, String where, boolean required, int fallback) {
            Long value = longValue(raw, where, required, (long) fallback);
            return value == null ? fallback : value.intValue();
        }

        int nonNegative(int value, String where) {
            if (value < 0) {
                errors.add(where + ": Input should be greater than or equal to 0");
            }
            return value;
        }

        Long longValue(Object raw, String where, boolean required, Long fallback) {
            if (raw == null) {
                if (required) {
                    errors.add(where + ": Field required");
                }
                return fallback;
            }
            if (raw instanceof Integer || raw instanceof Long) {
                return ((Number) raw).longValue();
            }
            if (raw instanceof Double d && d == Math.rint(d)) {
                return d.longValue();
            }
            if (raw instanceof String s) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            errors.add(where + ": Input should be a valid integer");
            return fallback;
        }

        Double ratio(Object raw, String where) {
            if (raw == null) {
                return null;
            }
            double value;
            if (raw instanceof Number n) {
                value = n.doubleValue();
            } else {
                errors.add(where + ": Input should be a valid number");
                return null;
            }
            if (value < 0 || value > 1) {
                errors.add(where + ": Input should be between 0 and 1");
            }
            return value;
        }

        boolean bool(Object raw, String where, boolean fallback) {
            if (raw == null) {
                return fallback;
            }
            if (raw instanceof Boolean b) {
                return b;
            }
            if (raw instanceof String s) {
                String v = s.trim().toLowerCase();
                if (Set.of("true", "yes", "on", "1").contains(v)) {
                    return true;
                }
                if (Set.of("false", "no", "off", "0").contains(v)) {
                    return false;
                }
            }
            errors.add(where + ": Input should be a valid boolean");
            return fallback;
        }

        void forbidExtra(Map<String, Object> node, String where, Set<String> allowed) {
            for (String key : node.keySet()) {
                if (!allowed.contains(key)) {
                    errors.add(where + "." + key + ": Extra inputs are not permitted");
                }
            }
        }
    }
}
