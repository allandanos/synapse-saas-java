package dev.synapse.usage.dto;

/** The contract's {@code UsageCheckOut}: read-only limit arithmetic for one metric. */
public record UsageCheckOut(String metric, long used, Long limit, Long remaining, boolean withinLimit, Long softLimit, boolean softLimitBreached) {}
