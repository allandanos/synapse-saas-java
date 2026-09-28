package dev.synapse.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailAddressValidatorTest {

    @Test
    void acceptsOrdinaryAddresses() {
        assertThat(EmailAddressValidator.reasonInvalid("owner-1@conformance.example.com")).isNull();
        assertThat(EmailAddressValidator.reasonInvalid("First.Last+tag@Example.COM")).isNull();
    }

    @Test
    void rejectsLikeEmailValidator() {
        assertThat(EmailAddressValidator.reasonInvalid("not-an-email")).isEqualTo("An email address must have an @-sign.");
        assertThat(EmailAddressValidator.reasonInvalid("@example.com")).isEqualTo("There must be something before the @-sign.");
        assertThat(EmailAddressValidator.reasonInvalid("a@")).isEqualTo("There must be something after the @-sign.");
        assertThat(EmailAddressValidator.reasonInvalid("a@localhost")).isEqualTo("The part after the @-sign is not valid. It should have a period.");
        assertThat(EmailAddressValidator.reasonInvalid("a b@example.com")).isNotNull();
        assertThat(EmailAddressValidator.reasonInvalid("a@ex ample.com")).isNotNull();
        assertThat(EmailAddressValidator.reasonInvalid("a@-bad-.com")).isNotNull();
    }

    @Test
    void normalisationLowercasesTheDomainOnly() {
        assertThat(Emails.normalize("Owner-1@Example.COM")).isEqualTo("Owner-1@example.com");
        assertThat(Emails.normalize("nope")).isEqualTo("nope");
    }
}
