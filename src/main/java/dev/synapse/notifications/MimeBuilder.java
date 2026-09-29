package dev.synapse.notifications;

import jakarta.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The message bytes, shaped exactly like the reference's
 * {@code email.message.EmailMessage} ({@code notifications/smtp.py}):
 *
 * <pre>
 * From / To / Subject, then MIME-Version + Content-Type: multipart/mixed
 *   ├─ text/plain; charset="utf-8"   (7bit, or quoted-printable when a line is long)
 *   └─ per attachment:
 *        Content-Type: application/pdf
 *        Content-Transfer-Encoding: base64
 *        Content-Disposition: attachment; filename="invoice-INV-….pdf"
 *        MIME-Version: 1.0
 * </pre>
 *
 * <p>Jakarta Mail's {@code MimeMessageHelper} writes the same information in a
 * different shape (a nested {@code multipart/related}, a {@code name=} parameter
 * on the part's Content-Type, an unquoted filename, no per-part MIME-Version),
 * and the console's e2e journeys parse the raw MIME — so the framework writes
 * the bytes itself and hands them to the transport untouched.
 */
final class MimeBuilder {

    /** {@code email.policy} default: a body line may not exceed this before it is soft-wrapped. */
    private static final int MAX_LINE_LENGTH = 78;
    /** RFC 2045 §6.8: base64 output is wrapped at 76 characters. */
    private static final int BASE64_LINE_LENGTH = 76;
    private static final String CRLF = "\r\n";
    private static final SecureRandom RANDOM = new SecureRandom();

    private MimeBuilder() {}

    static byte[] build(String from, String to, String subject, String body, List<Attachment> attachments) {
        StringBuilder out = new StringBuilder();
        out.append("From: ").append(encodeHeader(from)).append(CRLF);
        out.append("To: ").append(encodeHeader(to)).append(CRLF);
        out.append("Subject: ").append(encodeHeader(subject)).append(CRLF);

        String text = body.endsWith("\n") ? body : body + "\n";
        if (attachments.isEmpty()) {
            appendTextHeaders(out, text);
            out.append("MIME-Version: 1.0").append(CRLF).append(CRLF);
            out.append(encodeText(text));
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        String boundary = boundary();
        out.append("MIME-Version: 1.0").append(CRLF);
        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append('"').append(CRLF).append(CRLF);

        out.append("--").append(boundary).append(CRLF);
        appendTextHeaders(out, text);
        out.append(CRLF).append(encodeText(text));

        for (Attachment attachment : attachments) {
            out.append(CRLF).append("--").append(boundary).append(CRLF);
            out.append("Content-Type: ").append(attachment.contentType()).append(CRLF);
            out.append("Content-Transfer-Encoding: base64").append(CRLF);
            out.append("Content-Disposition: attachment; filename=\"").append(attachment.filename()).append('"').append(CRLF);
            out.append("MIME-Version: 1.0").append(CRLF).append(CRLF);
            out.append(base64(attachment.content()));
        }
        out.append(CRLF).append("--").append(boundary).append("--").append(CRLF);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendTextHeaders(StringBuilder out, String text) {
        out.append("Content-Type: text/plain; charset=\"utf-8\"").append(CRLF);
        out.append("Content-Transfer-Encoding: ").append(textEncoding(text)).append(CRLF);
    }

    /** The reference's content manager: 7bit while it can, quoted-printable for long ASCII, base64 otherwise. */
    static String textEncoding(String text) {
        if (!isAscii(text)) {
            return "base64";
        }
        for (String line : text.split("\n", -1)) {
            if (line.length() > MAX_LINE_LENGTH) {
                return "quoted-printable";
            }
        }
        return "7bit";
    }

    private static String encodeText(String text) {
        return switch (textEncoding(text)) {
            case "base64" -> base64(text.getBytes(StandardCharsets.UTF_8));
            case "quoted-printable" -> quotedPrintable(text);
            default -> text.replace("\n", CRLF);
        };
    }

    /** RFC 2047 only when the value is not plain ASCII — the framework's own subjects never are encoded. */
    private static String encodeHeader(String value) {
        if (isAscii(value)) {
            return value;
        }
        try {
            return MimeUtility.encodeText(value, "utf-8", "B");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(c -> c < 0x80);
    }

    private static String base64(byte[] content) {
        String encoded = Base64.getEncoder().encodeToString(content);
        StringBuilder wrapped = new StringBuilder();
        for (int offset = 0; offset < encoded.length(); offset += BASE64_LINE_LENGTH) {
            wrapped.append(encoded, offset, Math.min(offset + BASE64_LINE_LENGTH, encoded.length())).append(CRLF);
        }
        return wrapped.toString();
    }

    /** RFC 2045 §6.7 body encoding, soft-wrapped so no line (including the {@code =}) exceeds the policy length. */
    static String quotedPrintable(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String atom = escape(bytes[i], i == bytes.length - 1);
                if (current.length() + atom.length() > MAX_LINE_LENGTH - 1) {
                    out.append(current).append('=').append(CRLF);
                    current.setLength(0);
                }
                current.append(atom);
            }
            out.append(current).append(CRLF);
        }
        return out.toString();
    }

    private static String escape(byte value, boolean lastOnLine) {
        int unsigned = value & 0xFF;
        boolean printable = unsigned >= 33 && unsigned <= 126 && unsigned != '=';
        boolean space = unsigned == ' ' || unsigned == '\t';
        if (printable || (space && !lastOnLine)) {
            return String.valueOf((char) unsigned);
        }
        return String.format(Locale.ROOT, "=%02X", unsigned);
    }

    /** The reference's {@code email.generator} boundary shape. */
    private static String boundary() {
        return "===============" + Long.toUnsignedString(RANDOM.nextLong() >>> 1) + "==";
    }
}
