package dev.synapse.subscriptions;

/** {@code limit_value == null} ⇒ unlimited; overage pricing present only when both columns are set. */
public record PlanLimit(String metric, Long limitValue, Double softLimitRatio, Integer overageUnit, Long overagePriceCents) {}
