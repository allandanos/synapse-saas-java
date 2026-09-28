package dev.synapse.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventsTest {

    @Test
    void internalEventsNeverFanOut() {
        assertThat(Events.audienceFor(Events.MEMBER_INVITE_EMAIL)).isEqualTo("internal");
        assertThat(Events.audienceFor(Events.USER_PASSWORD_RESET_LINK)).isEqualTo("internal");
        assertThat(Events.audienceFor(Events.MEMBER_INVITED)).isEqualTo("public");
        assertThat(Events.audienceFor(Events.ORG_CREATED)).isEqualTo("public");
        assertThat(Events.INTERNAL_EVENTS).containsExactlyInAnyOrder(
            "member.invite_email", "user.password_reset_link", "invoice.email", "authz.tuples_changed");
    }
}
