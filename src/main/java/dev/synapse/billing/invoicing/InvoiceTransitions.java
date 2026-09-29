package dev.synapse.billing.invoicing;

import dev.synapse.core.errors.ValidationFailedError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Invoice lifecycle (reference: {@code billing/invoicing.py:INVOICE_TRANSITIONS}).
 *
 * <pre>
 * draft → open (finalize: assigns a number, locks the amounts) | void
 * open  → paid | void | uncollectible
 * paid / void / uncollectible are terminal
 * </pre>
 */
public final class InvoiceTransitions {

    public static final Map<String, Set<String>> ALLOWED = Map.of(
        "draft", Set.of("open", "void"),
        "open", Set.of("paid", "void", "uncollectible"),
        "paid", Set.of(),
        "void", Set.of(),
        "uncollectible", Set.of());

    private InvoiceTransitions() {}

    /** Same status is a no-op; anything else must be in the table, else 422 with {@code from/to/allowed}. */
    public static void assertTransition(String current, String target) {
        if (current.equals(target)) {
            return;
        }
        Set<String> allowed = ALLOWED.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("from", current);
            extras.put("to", target);
            extras.put("allowed", allowed.stream().sorted().toList());
            throw new ValidationFailedError("Cannot move invoice from '" + current + "' to '" + target + "'", extras);
        }
    }

    public static List<String> sortedAllowed(String current) {
        return ALLOWED.getOrDefault(current, Set.of()).stream().sorted().toList();
    }
}
