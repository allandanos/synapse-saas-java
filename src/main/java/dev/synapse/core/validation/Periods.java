package dev.synapse.core.validation;

import dev.synapse.core.errors.ValidationFailedError;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * {@code YYYY-MM} billing/usage periods. The pattern validates the MONTH
 * (reference: {@code usage/router.py} and {@code billing/schemas.py} —
 * {@code 2026-13} is a 422 {@code validation_failed}, never a parser 500).
 */
public final class Periods {

    /** Same regex as the contract's {@code period} query parameter and request field. */
    public static final String MONTH_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";

    private Periods() {}

    /** First day of the month, or null for a null input. */
    public static LocalDate firstDay(String period, String location, String field) {
        if (period == null) {
            return null;
        }
        try {
            return YearMonth.parse(period).atDay(1);
        } catch (DateTimeParseException e) {
            throw new ValidationFailedError("Invalid request: " + field, Map.of("errors", List.of(
                Map.of("loc", List.of(location, field), "msg", "String should match pattern '" + MONTH_PATTERN + "'",
                    "type", "string_pattern_mismatch"))));
        }
    }
}
