package dev.synapse.subscriptions;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Proration for mid-period plan changes — a pure function (reference: {@code subscriptions/proration.py}).
 *
 * <p>Time-based, second-granular. Two views of the same split: {@link #prorate}
 * in advance (unused fraction credited at the old price, charged at the new),
 * {@link #arrearsAdjustmentCents} in arrears, which is how the invoicing
 * engine bills (the elapsed fraction at the price difference).
 *
 * <p>Arithmetic mirrors Python's {@code decimal}: the fraction is the quotient
 * at 28 significant digits quantised to six places (half-even, the default
 * context), cents round half-up.
 */
public record Proration(long creditCents, long chargeCents, BigDecimal fractionRemaining) {

    private static final MathContext PY_CONTEXT = new MathContext(28, RoundingMode.HALF_EVEN);
    private static final int FRACTION_SCALE = 6;

    /** In-advance view: positive ⇒ the org owes this much now; negative ⇒ it is owed. */
    public long netCents() {
        return chargeCents - creditCents;
    }

    public BigDecimal elapsedFraction() {
        return BigDecimal.ONE.subtract(fractionRemaining);
    }

    /**
     * Correction line for a period invoiced IN ARREARS at the new price: the
     * elapsed fraction at the price difference — negative (credit) on an
     * upgrade, positive on a downgrade, so that {@code new + adjustment == old*elapsed + new*remaining}.
     */
    public static long arrearsAdjustmentCents(long oldCents, long newCents, Instant periodStart, Instant periodEnd, Instant now) {
        Proration result = prorate(oldCents, newCents, periodStart, periodEnd, now);
        return cents(oldCents - newCents, result.elapsedFraction());
    }

    /**
     * Prorate a plan change at {@code now} inside {@code [periodStart, periodEnd)}.
     * Clamped: before the period starts ⇒ the whole period; after it ends ⇒
     * nothing. A zero-length period prorates nothing (the renewal charges in full).
     */
    public static Proration prorate(long oldCents, long newCents, Instant periodStart, Instant periodEnd, Instant now) {
        BigDecimal total = seconds(Duration.between(periodStart, periodEnd));
        if (total.signum() <= 0) {
            return new Proration(0, 0, BigDecimal.ZERO);
        }
        BigDecimal remaining = seconds(Duration.between(now, periodEnd)).max(BigDecimal.ZERO).min(total);
        BigDecimal fraction = remaining.divide(total, PY_CONTEXT).setScale(FRACTION_SCALE, RoundingMode.HALF_EVEN);
        return new Proration(cents(oldCents, fraction), cents(newCents, fraction), fraction);
    }

    static long cents(long amount, BigDecimal fraction) {
        return BigDecimal.valueOf(amount).multiply(fraction).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** {@code timedelta.total_seconds()}: microsecond-precise seconds. */
    private static BigDecimal seconds(Duration d) {
        return BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9)).setScale(6, RoundingMode.HALF_EVEN);
    }
}
