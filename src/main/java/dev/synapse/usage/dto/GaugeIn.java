package dev.synapse.usage.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Set a gauge to {@code value}, or move it by {@code delta} — exactly one of the two (checked by the controller). */
public record GaugeIn(@NotNull String metric, @Min(0) Long value, Long delta) {

    public boolean hasExactlyOne() {
        return (value == null) != (delta == null);
    }
}
