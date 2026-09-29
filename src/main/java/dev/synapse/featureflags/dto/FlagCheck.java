package dev.synapse.featureflags.dto;

/** The tenant-facing evaluation result. */
public record FlagCheck(String key, boolean enabled) {}
