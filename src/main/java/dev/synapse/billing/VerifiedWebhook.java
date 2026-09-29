package dev.synapse.billing;

import java.time.Instant;
import java.util.Map;

/** A webhook whose signature (or token) checked out; {@code parsed} is its JSON body. */
public record VerifiedWebhook(String providerEventId, String eventType, Map<String, Object> parsed, Instant receivedAt) {}
