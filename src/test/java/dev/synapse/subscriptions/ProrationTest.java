package dev.synapse.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Proration is a pure function; these pin the arithmetic the invoices depend on (reference: {@code test_proration.py}). */
class ProrationTest {

    static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    static final Instant END = START.plus(Duration.ofDays(30));

    @Nested
    class Prorate {

        @Test
        void halfwaySplitsBothPricesInHalf() {
            Proration result = Proration.prorate(10000, 30000, START, END, START.plus(Duration.ofDays(15)));
            assertThat(result.fractionRemaining()).isEqualByComparingTo(new BigDecimal("0.5"));
            assertThat(result.creditCents()).isEqualTo(5000);
            assertThat(result.chargeCents()).isEqualTo(15000);
            assertThat(result.netCents()).isEqualTo(10000);
        }

        @Test
        void beforeThePeriodStartsIsTheWholePeriod() {
            Proration result = Proration.prorate(10000, 30000, START, END, START.minus(Duration.ofDays(3)));
            assertThat(result.fractionRemaining()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.netCents()).isEqualTo(20000);
        }

        @Test
        void afterThePeriodEndsIsNothing() {
            Proration result = Proration.prorate(10000, 30000, START, END, END.plusSeconds(1));
            assertThat(result.fractionRemaining()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.creditCents()).isZero();
            assertThat(result.chargeCents()).isZero();
            assertThat(result.netCents()).isZero();
        }

        @Test
        void zeroLengthPeriodProratesNothing() {
            assertThat(Proration.prorate(10000, 30000, START, START, START).netCents()).isZero();
        }

        @Test
        void roundsHalfUpToWholeCents() {
            // 1/3 of the period left: 1000 * 0.333333 = 333.333 → 333; 5 * 0.333333 → 1.67 → 2
            Proration result = Proration.prorate(1000, 5, START, END, START.plus(Duration.ofDays(20)));
            assertThat(result.creditCents()).isEqualTo(333);
            assertThat(result.chargeCents()).isEqualTo(2);
        }

        @Test
        void downgradeNetsACredit() {
            assertThat(Proration.prorate(30000, 10000, START, END, START.plus(Duration.ofDays(15))).netCents()).isEqualTo(-10000);
        }
    }

    /**
     * The period invoice bills the NEW price for the whole period; the adjustment
     * returns the elapsed fraction at the price difference so the total equals
     * old*elapsed + new*remaining.
     */
    @Nested
    class ArrearsAdjustment {

        @Test
        void upgradeCreditsTheElapsedFraction() {
            long adjustment = Proration.arrearsAdjustmentCents(49900, 199900, START, END, START.plus(Duration.ofDays(15)));
            assertThat(adjustment).isEqualTo(-75000);
            assertThat(199900 + adjustment).isEqualTo((long) (49900 * 0.5 + 199900 * 0.5));
        }

        @Test
        void downgradeChargesTheElapsedFraction() {
            long adjustment = Proration.arrearsAdjustmentCents(199900, 49900, START, END, START.plus(Duration.ofDays(15)));
            assertThat(adjustment).isEqualTo(75000);
            assertThat(49900 + adjustment).isEqualTo((long) (199900 * 0.5 + 49900 * 0.5));
        }

        @Test
        void changeAtPeriodStartNeedsNoCorrection() {
            assertThat(Proration.arrearsAdjustmentCents(49900, 199900, START, END, START)).isZero();
        }

        @Test
        void changeAtPeriodEndIsFullyTheOldPrice() {
            assertThat(Proration.arrearsAdjustmentCents(49900, 199900, START, END, END)).isEqualTo(-150000);
        }

        @Test
        void samePriceIsZero() {
            assertThat(Proration.arrearsAdjustmentCents(49900, 49900, START, END, START.plus(Duration.ofDays(9)))).isZero();
        }
    }
}
