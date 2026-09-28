package dev.synapse.core.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * The checks {@code email_validator} (pydantic's {@code EmailStr}) applies
 * syntactically: exactly one {@code @}, a non-empty local part without
 * whitespace, and a dotted hostname on the right. Deliverability is not checked.
 */
public class EmailAddressValidator implements ConstraintValidator<EmailAddress, String> {

    private static final Pattern LOCAL = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // @NotNull reports "Field required"
        }
        String reason = reasonInvalid(value);
        if (reason == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("value is not a valid email address: " + reason).addConstraintViolation();
        return false;
    }

    /** {@code null} when valid, else the reason (worded like email_validator). */
    public static String reasonInvalid(String value) {
        int at = value.indexOf('@');
        if (at < 0) {
            return "An email address must have an @-sign.";
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        if (local.isEmpty()) {
            return "There must be something before the @-sign.";
        }
        if (domain.isEmpty()) {
            return "There must be something after the @-sign.";
        }
        if (!LOCAL.matcher(local).matches()) {
            return "The part before the @-sign contains invalid characters.";
        }
        if (domain.indexOf('@') >= 0 || domain.chars().anyMatch(Character::isWhitespace)) {
            return "The part after the @-sign contains invalid characters.";
        }
        if (!domain.contains(".")) {
            return "The part after the @-sign is not valid. It should have a period.";
        }
        for (String label : domain.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return "The part after the @-sign is not valid.";
            }
        }
        return null;
    }
}
