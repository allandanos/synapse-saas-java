package dev.synapse.usage.dto;

/**
 * The contract's {@code UsageResultOut}. {@code limit}/{@code remaining}/{@code within_limit}
 * are null on the non-enforcing path; {@code deduplicated} is true when an
 * idempotency key matched an earlier request and nothing was counted again.
 */
public record UsageResultOut(String metric, long quantity, long total, Long limit, Long remaining, Boolean withinLimit, boolean deduplicated) {

    public static UsageResultOut recorded(String metric, long quantity, long total) {
        return new UsageResultOut(metric, quantity, total, null, null, null, false);
    }

    public static UsageResultOut deduplicated(String metric, long quantity, long total) {
        return new UsageResultOut(metric, quantity, total, null, null, null, true);
    }

    /** The enforcing shape: limit arithmetic against {@code total}. */
    public static UsageResultOut enforced(String metric, long quantity, long total, Long limit, boolean deduplicated) {
        return new UsageResultOut(metric, quantity, total, limit, limit == null ? null : limit - total, limit == null || total <= limit, deduplicated);
    }
}
