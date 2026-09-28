package dev.synapse.subscriptions;

/** Row of the {@code metrics} registry; {@code kind} is {@code counter} (flows, monthly) or {@code gauge} (levels). */
public record Metric(String key, String name, String kind, String unit, Integer overageUnit, Long overagePriceCents) {

    public boolean isGauge() {
        return "gauge".equals(kind);
    }
}
