package dev.synapse.subscriptions;

import dev.synapse.core.errors.SubscriptionStateError;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Subscription status state machine (reference: {@code subscriptions/state_machine.py}).
 * Provider webhooks arrive out of order and replay, so transitions are
 * idempotent (same → same is always legal) and this table is the single
 * definition of what else is.
 */
public final class SubscriptionStateMachine {

    private SubscriptionStateMachine() {}

    /** status → statuses it may move to */
    public static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
        "incomplete", Set.of("trialing", "active", "past_due", "canceled"),
        "trialing", Set.of("active", "past_due", "canceled", "incomplete"),
        "active", Set.of("past_due", "canceled", "unpaid", "active"), // active→active = plan change
        "past_due", Set.of("active", "canceled", "unpaid"),
        "unpaid", Set.of("active", "canceled"),
        "canceled", Set.of("active")); // resume

    /** Statuses whose plan features are in effect (the entitlement resolver uses this). */
    public static final Set<String> OCCUPYING_STATUSES = Set.of("trialing", "active", "past_due");

    public static boolean canTransition(String current, String target) {
        if (current.equals(target)) {
            return true; // idempotent re-assertion (webhook replays)
        }
        return ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target);
    }

    public static void assertTransition(String current, String target) {
        if (!canTransition(current, target)) {
            List<String> allowed = List.copyOf(new TreeSet<>(ALLOWED_TRANSITIONS.getOrDefault(current, Set.of())));
            throw new SubscriptionStateError("Cannot transition subscription from '" + current + "' to '" + target + "'",
                Map.of("from", current, "to", target, "allowed", allowed));
        }
    }
}
