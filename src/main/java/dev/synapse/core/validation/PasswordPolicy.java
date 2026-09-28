package dev.synapse.core.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Minimum password length (reference: {@code PASSWORD_MIN_LENGTH = 10}). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PasswordPolicyValidator.class)
public @interface PasswordPolicy {
    int MIN_LENGTH = 10;

    String message() default "Value error, Password must be at least " + MIN_LENGTH + " characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
