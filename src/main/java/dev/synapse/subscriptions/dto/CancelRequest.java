package dev.synapse.subscriptions.dto;

/** {@code at_period_end} defaults to true (the reference's {@code CancelRequest}). */
public record CancelRequest(Boolean atPeriodEnd) {

    public boolean atPeriodEndOrDefault() {
        return atPeriodEnd == null || atPeriodEnd;
    }
}
