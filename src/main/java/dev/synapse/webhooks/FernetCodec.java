package dev.synapse.webhooks;

import dev.synapse.core.config.SynapseProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Fernet (AES-128-CBC + HMAC-SHA256, base64url) for webhook endpoint secrets at
 * rest. The wire format is the spec's:
 * {@code 0x80 ‖ timestamp(8) ‖ IV(16) ‖ ciphertext ‖ HMAC-SHA256(32)}, the HMAC
 * covering everything before it.
 *
 * <p>The key is derived exactly as the reference does
 * ({@code webhooks/service.py::_fernet}): SHA-256 of {@code SYNAPSE_SECRET_KEY},
 * whose first 16 bytes sign and whose last 16 encrypt. Rotating
 * {@code SYNAPSE_SECRET_KEY} therefore invalidates stored secrets — and an
 * endpoint created by any implementation of the contract is deliverable by any
 * other.
 */
@Component
public class FernetCodec {

    public static final byte VERSION = (byte) 0x80;
    private static final int IV_LENGTH = 16;
    private static final int HMAC_LENGTH = 32;
    private static final int HEADER_LENGTH = 1 + 8 + IV_LENGTH;
    private static final String AES_CBC = "AES/CBC/PKCS5Padding";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final byte[] signingKey;
    private final byte[] encryptionKey;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public FernetCodec(SynapseProperties props) {
        this(props.secretKey());
    }

    public FernetCodec(String secretKey) {
        byte[] digest = sha256(secretKey.getBytes(StandardCharsets.UTF_8));
        this.signingKey = Arrays.copyOfRange(digest, 0, 16);
        this.encryptionKey = Arrays.copyOfRange(digest, 16, 32);
    }

    /** The token as stored in {@code webhook_endpoints.secret_encrypted}. */
    public byte[] encrypt(String plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        return encrypt(plaintext, iv, System.currentTimeMillis() / 1000);
    }

    byte[] encrypt(String plaintext, byte[] iv, long timestamp) {
        try {
            Cipher cipher = Cipher.getInstance(AES_CBC);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer signed = ByteBuffer.allocate(HEADER_LENGTH + ciphertext.length);
            signed.put(VERSION).putLong(timestamp).put(iv).put(ciphertext);
            byte[] body = signed.array();
            byte[] token = Arrays.copyOf(body, body.length + HMAC_LENGTH);
            System.arraycopy(hmac(body), 0, token, body.length, HMAC_LENGTH);
            return Base64.getUrlEncoder().encode(token);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Fernet encryption failed", e);
        }
    }

    public String decrypt(byte[] storedToken) {
        byte[] token = Base64.getUrlDecoder().decode(new String(storedToken, StandardCharsets.US_ASCII));
        if (token.length < HEADER_LENGTH + HMAC_LENGTH || token[0] != VERSION) {
            throw new IllegalStateException("Not a Fernet token");
        }
        byte[] body = Arrays.copyOf(token, token.length - HMAC_LENGTH);
        byte[] signature = Arrays.copyOfRange(token, body.length, token.length);
        if (!MessageDigest.isEqual(hmac(body), signature)) {
            throw new IllegalStateException("Fernet token signature mismatch");
        }
        byte[] iv = Arrays.copyOfRange(body, 9, HEADER_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(body, HEADER_LENGTH, body.length);
        try {
            Cipher cipher = Cipher.getInstance(AES_CBC);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Fernet decryption failed", e);
        }
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(signingKey, HMAC_SHA256));
            return mac.doFinal(body);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
