package dev.synapse.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.synapse.core.errors.SubscriptionStateError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Reference: {@code tests/unit/subscriptions/test_state_machine.py}. */
class SubscriptionStateMachineTest {

    @ParameterizedTest
    @CsvSource({
        "incomplete, trialing", "incomplete, active", "trialing, active", "trialing, canceled", "active, past_due", "active, canceled",
        "active, unpaid", "past_due, active", "past_due, canceled", "unpaid, canceled", "canceled, active"})
    void legal(String current, String target) {
        assertThat(SubscriptionStateMachine.canTransition(current, target)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"canceled, trialing", "canceled, past_due", "unpaid, trialing", "incomplete, unpaid"})
    void illegal(String current, String target) {
        assertThat(SubscriptionStateMachine.canTransition(current, target)).isFalse();
    }

    @Test
    void sameStateIsIdempotent() {
        for (String status : new String[] {"trialing", "active", "past_due", "canceled"}) {
            assertThat(SubscriptionStateMachine.canTransition(status, status)).isTrue();
        }
    }

    @Test
    void assertRaisesWithContext() {
        assertThatThrownBy(() -> SubscriptionStateMachine.assertTransition("canceled", "trialing"))
            .isInstanceOf(SubscriptionStateError.class)
            .satisfies(t -> {
                SubscriptionStateError e = (SubscriptionStateError) t;
                assertThat(e.status()).isEqualTo(409);
                assertThat(e.title()).isEqualTo("invalid_subscription_transition");
                assertThat(e.extras().get("from")).isEqualTo("canceled");
                assertThat(e.extras()).containsKey("allowed");
            });
    }

    @Test
    void assertAcceptsIdempotent() {
        assertThatCode(() -> SubscriptionStateMachine.assertTransition("active", "active")).doesNotThrowAnyException();
    }

    @Test
    void occupyingStatuses() {
        assertThat(SubscriptionStateMachine.OCCUPYING_STATUSES).containsExactlyInAnyOrder("trialing", "active", "past_due");
    }
}
