package dev.synapse.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.synapse.core.errors.ValidationFailedError;
import dev.synapse.entitlements.EntitlementResolver.Overage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The invoicing arithmetic and lifecycle rules, without a database. */
class InvoicingLogicTest {

    @Nested
    class Transitions {

        @Test
        void followsTheReferenceTable() {
            assertThat(InvoiceTransitions.sortedAllowed("draft")).containsExactly("open", "void");
            assertThat(InvoiceTransitions.sortedAllowed("open")).containsExactly("paid", "uncollectible", "void");
            assertThat(InvoiceTransitions.sortedAllowed("paid")).isEmpty();
            assertThat(InvoiceTransitions.sortedAllowed("void")).isEmpty();
            assertThat(InvoiceTransitions.sortedAllowed("uncollectible")).isEmpty();
        }

        @Test
        void theSameStatusIsANoOp() {
            InvoiceTransitions.assertTransition("paid", "paid"); // terminal, yet idempotent
        }

        @Test
        void anIllegalMoveIs422WithFromToAllowed() {
            assertThatThrownBy(() -> InvoiceTransitions.assertTransition("paid", "void"))
                .isInstanceOf(ValidationFailedError.class)
                .satisfies(thrown -> {
                    ValidationFailedError error = (ValidationFailedError) thrown;
                    assertThat(error.status()).isEqualTo(422);
                    assertThat(error.title()).isEqualTo("validation_failed");
                    assertThat(error.extras()).containsEntry("from", "paid").containsEntry("to", "void")
                        .containsEntry("allowed", List.of());
                });
            assertThatThrownBy(() -> InvoiceTransitions.assertTransition("draft", "paid"))
                .isInstanceOf(ValidationFailedError.class)
                .satisfies(thrown -> assertThat(((ValidationFailedError) thrown).extras())
                    .containsEntry("allowed", List.of("open", "void")));
        }
    }

    @Nested
    class Numbering {

        @Test
        void isInvYearMonthAndAZeroPaddedCounter() {
            Instant september = Instant.parse("2026-09-29T10:00:00Z");
            assertThat(InvoiceNumbering.next(0, september)).isEqualTo("INV-202609-0001");
            assertThat(InvoiceNumbering.next(41, september)).isEqualTo("INV-202609-0042");
            assertThat(InvoiceNumbering.next(9999, september)).isEqualTo("INV-202609-10000");
        }

        @Test
        void usesTheUtcMonth() {
            assertThat(InvoiceNumbering.next(0, Instant.parse("2026-12-31T23:59:59Z"))).isEqualTo("INV-202612-0001");
        }
    }

    @Nested
    class Periods {

        @Test
        void aPeriodSpansTheWholeMonthInUtc() {
            assertThat(InvoicingService.startOfMonth(LocalDate.of(2026, 9, 1))).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
            assertThat(InvoicingService.endOfMonth(LocalDate.of(2026, 9, 1))).isEqualTo(Instant.parse("2026-09-30T23:59:59Z"));
        }

        @Test
        void februaryEndsOnItsOwnLastDay() {
            assertThat(InvoicingService.endOfMonth(LocalDate.of(2026, 2, 1))).isEqualTo(Instant.parse("2026-02-28T23:59:59Z"));
            assertThat(InvoicingService.endOfMonth(LocalDate.of(2028, 2, 1))).isEqualTo(Instant.parse("2028-02-29T23:59:59Z"));
        }

        @Test
        void theBucketIsTheFirstOfTheUtcMonth() {
            assertThat(InvoicingService.monthBucket(Instant.parse("2026-09-29T23:30:00Z"))).isEqualTo(LocalDate.of(2026, 9, 1));
        }
    }

    @Nested
    class OverageLines {

        /** A line must reconcile on its own: quantity × unit price == amount. */
        @Test
        void quantityTimesUnitPriceEqualsTheAmount() {
            Overage perThousand = new Overage(1000, 500);
            for (long unitsOver : new long[] {1, 999, 1000, 1001, 123_456}) {
                Overage.Bill bill = perThousand.bill(unitsOver);
                assertThat(bill.amountCents()).isEqualTo(bill.quantity() * perThousand.priceCents());
            }
        }

        @Test
        void billingRoundsWholeBlocksUp() {
            Overage perThousand = new Overage(1000, 500);
            assertThat(perThousand.bill(1).quantity()).isEqualTo(1);
            assertThat(perThousand.bill(1000).quantity()).isEqualTo(1);
            assertThat(perThousand.bill(1001).quantity()).isEqualTo(2);
            assertThat(perThousand.bill(0).amountCents()).isZero();
        }
    }

    @Nested
    class AdjustmentLines {

        @Test
        void aNegativeAdjustmentIsACreditAndAPositiveOneIsCustom() {
            List<InvoiceLine.Draft> lines = InvoicingService.adjustmentLines(List.of(
                adjustment("proration", -1234, "Plan change pro → starter: credit"),
                adjustment("proration", 5678, "Plan change starter → pro: charge")));
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0).kind()).isEqualTo("credit");
            assertThat(lines.get(0).amountCents()).isEqualTo(-1234);
            assertThat(lines.get(0).unitAmountCents()).isEqualTo(-1234);
            assertThat(lines.get(0).quantity()).isEqualTo(1);
            assertThat(lines.get(1).kind()).isEqualTo("custom");
            assertThat(lines.get(1).amountCents()).isEqualTo(5678);
        }

        @Test
        void aZeroAdjustmentProducesNoLine() {
            assertThat(InvoicingService.adjustmentLines(List.of(adjustment("proration", 0, "nothing owed")))).isEmpty();
        }

        @Test
        void thePropertiesKeepEverythingButTheDescriptionAndAmount() {
            InvoiceLine.Draft line = InvoicingService.adjustmentLines(List.of(adjustment("proration", -10, "credit"))).get(0);
            assertThat(line.properties()).containsEntry("kind", "proration").containsKey("from_plan")
                .doesNotContainKey("description").doesNotContainKey("amount_cents");
        }

        @Test
        void anAdjustmentWithoutADescriptionFallsBackToItsKind() {
            Map<String, Object> bare = new LinkedHashMap<>();
            bare.put("kind", "credit_carryover");
            bare.put("amount_cents", -500);
            assertThat(InvoicingService.adjustmentLines(List.of(bare)).get(0).description()).isEqualTo("credit_carryover");
        }

        private static Map<String, Object> adjustment(String kind, long cents, String description) {
            Map<String, Object> adjustment = new LinkedHashMap<>();
            adjustment.put("kind", kind);
            adjustment.put("amount_cents", cents);
            adjustment.put("description", description);
            adjustment.put("from_plan", "pro");
            adjustment.put("to_plan", "starter");
            return adjustment;
        }
    }

    @Nested
    class Money {

        @Test
        void pdfMoneyIsGroupedMajorUnitsWithTheCurrency() {
            assertThat(InvoicePdf.money(149900, "PHP")).isEqualTo("PHP 1,499.00");
            assertThat(InvoicePdf.money(0, "USD")).isEqualTo("USD 0.00");
            assertThat(InvoicePdf.money(-1234, "PHP")).isEqualTo("PHP -12.34");
            assertThat(InvoicePdf.money(100, "JPY")).isEqualTo("JPY 1.00"); // unknown currency: the code plus a space
        }

        @Test
        void latin1SanitisingMatchesTheReferenceReplacements() {
            assertThat(InvoicePdf.latin1("api_requests overage — 1,000 units over plan"))
                .isEqualTo("api_requests overage - 1,000 units over plan");
            assertThat(InvoicePdf.latin1("₱1,499.00")).isEqualTo("PHP 1,499.00");
            assertThat(InvoicePdf.latin1("‘quoted’ and “quoted”")).isEqualTo("'quoted' and \"quoted\"");
        }
    }
}
