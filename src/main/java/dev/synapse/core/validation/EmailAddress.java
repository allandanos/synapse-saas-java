package dev.synapse.core.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Pydantic {@code EmailStr} semantics (see {@link EmailAddressValidator}). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EmailAddressValidator.class)
public @interface EmailAddress {
    String message() default "value is not a valid email address";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
