package dev.synapse.core.validation;

import java.util.Locale;

/** {@code EmailStr} normalisation: the domain part is lower-cased, the local part is kept as given. */
public final class Emails {

    private Emails() {}

    public static String normalize(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0) {
            return email;
        }
        return email.substring(0, at) + "@" + email.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
