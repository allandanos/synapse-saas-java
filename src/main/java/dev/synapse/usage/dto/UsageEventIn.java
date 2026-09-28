package dev.synapse.usage.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** One metered event; {@code quantity} defaults to 1, {@code idempotency_key} dedupes per organization. */
public record UsageEventIn(@NotNull String metric, @Min(1) Long quantity, String idempotencyKey, Map<String, Object> properties) {

    public long quantityOrDefault() {
        return quantity == null ? 1 : quantity;
    }
}
