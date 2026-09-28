package dev.synapse.identity;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * argon2id with the reference's parameters (argon2-cffi defaults: t=3, m=64 MiB, p=4,
 * 16-byte salt, 32-byte hash). Encoded hashes are interchangeable with the Python reference.
 */
@Component
public class PasswordHasher {

    /** Argon2 hash of an unguessable value — burns comparable CPU on unknown-email logins (timing equalisation). */
    public static final String DUMMY_HASH =
        "$argon2id$v=19$m=65536,t=3,p=4$c3NybU5vdEFSZWFsUGFzc3dvcmQ$bQ9OBGPOtW4Kpl6Z73pQ4Lc2v1OiqeuCYiY0FbxBNCs";

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 4, 65_536, 3);

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean verify(String password, String passwordHash) {
        try {
            return encoder.matches(password, passwordHash);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
