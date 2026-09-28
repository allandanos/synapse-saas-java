package dev.synapse.subscriptions.dto;

public record PlanLimitRead(String metric, Long limitValue, Double softLimitRatio) {}
