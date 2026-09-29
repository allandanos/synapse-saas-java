package dev.synapse.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The message bytes stay byte-compatible with the reference's
 * {@code email.message.EmailMessage} (ADR 0012: mirror the reference), and
 * remain readable by the console's own MIME helpers — {@code fixtures.ts}
 * {@code pdfAttachmentBase64()} (any base64 {@code application/pdf} part) and
 * {@code resetTokenFromEmail()} (quoted-printable decoded, then the reset link).
 */
class MimeBuilderTest {

    /** The layout the reference's EmailMessage produces; the spec no longer pins it, the port still matches it. */
    private static final Pattern REFERENCE_ATTACHMENT = Pattern.compile(
        "Content-Type: application/pdf\nContent-Transfer-Encoding: base64\n"
            + "Content-Disposition: attachment; filename=\"invoice-[^\"]+\\.pdf\"\nMIME-Version: 1\\.0\n\n([A-Za-z0-9+/=\n]+)");

    private static String flat(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void attachmentPartMatchesTheReferenceLayoutAndDecodesToThePdf() {
        byte[] pdf = "%PDF-1.5\nthe invoice bytes".getBytes(StandardCharsets.UTF_8);
        byte[] raw = MimeBuilder.build("billing@synapse.test", "ap@example.com",
            "Invoice INV-202609-0001: 1,999.00 PHP due",
            "Invoice INV-202609-0001 for 1,999.00 PHP is attached. Payment instructions are included in the PDF.",
            List.of(new Attachment("invoice-INV-202609-0001.pdf", pdf, "application/pdf")));

        String flat = flat(raw);
        Matcher matcher = REFERENCE_ATTACHMENT.matcher(flat);
        assertThat(matcher.find()).as("the reference's attachment layout:\n%s", flat).isTrue();
        assertThat(Base64.getMimeDecoder().decode(matcher.group(1))).isEqualTo(pdf);
        // …and the console's tolerant finder sees the same bytes.
        assertThat(Base64.getMimeDecoder().decode(pdfAttachmentBase64(flat))).isEqualTo(pdf);
    }

    /**
     * A port of {@code fixtures.ts} {@code pdfAttachmentBase64()}: the first
     * base64 {@code application/pdf} part, however the part is spelled.
     */
    private static String pdfAttachmentBase64(String flat) {
        for (String part : flat.split("\n--[^\n]+\n")) {
            int separator = part.indexOf("\n\n");
            if (separator == -1) {
                continue;
            }
            String headers = part.substring(0, separator).replaceAll("\n[ \t]+", " ");
            if (!headers.toLowerCase(java.util.Locale.ROOT).contains("content-type: application/pdf")
                || !headers.toLowerCase(java.util.Locale.ROOT).contains("content-transfer-encoding: base64")) {
                continue;
            }
            return part.substring(separator + 2).split("\n--")[0].replaceAll("\\s+", "");
        }
        return null;
    }

    /** A port of {@code fixtures.ts} {@code resetTokenFromEmail()}. */
    private static String resetTokenFromEmail(String raw) {
        String decoded = raw.replaceAll("=\r?\n", "");
        Matcher escaped = Pattern.compile("=([0-9A-Fa-f]{2})").matcher(decoded);
        StringBuilder unescaped = new StringBuilder();
        while (escaped.find()) {
            escaped.appendReplacement(unescaped, String.valueOf((char) Integer.parseInt(escaped.group(1), 16)));
        }
        escaped.appendTail(unescaped);
        Matcher link = Pattern.compile("/reset-password\\?reset=([A-Za-z0-9_-]+)").matcher(unescaped);
        return link.find() ? link.group(1) : null;
    }

    @Test
    void theResetLinkSurvivesQuotedPrintableAndBlankLinesAreKept() {
        String token = "M1mQ3rT7xY_a-BcDeFgHiJkLmNoPqRsTuVwXyZ0123456";
        String body = "A password reset was requested for your account.\n\n"
            + "Reset it here (valid 30 minutes):\n"
            + "http://localhost:3300/reset-password?reset=" + token + "\n\n"
            + "If you didn't request this, ignore this email.";
        String raw = new String(MimeBuilder.build("noreply@synapse.test", "u@example.com",
            "Reset your password", body, List.of()), StandardCharsets.UTF_8);

        assertThat(MimeBuilder.textEncoding(body + "\n")).isEqualTo("quoted-printable");
        assertThat(raw).contains("=3D");  // the link's own "=" is escaped, so a raw match would miss it
        assertThat(resetTokenFromEmail(raw)).isEqualTo(token);
        // The paragraph breaks are part of the message, not artefacts of the terminator.
        assertThat(MimeBuilder.quotedPrintable("one\n\ntwo\n")).isEqualTo("one\r\n\r\ntwo\r\n");
    }

    @Test
    void multipartShapeMirrorsTheReference() {
        byte[] raw = MimeBuilder.build("billing@synapse.test", "ap@example.com", "Invoice INV-1", "Body.",
            List.of(new Attachment("invoice-INV-1.pdf", new byte[] {1, 2, 3}, "application/pdf")));
        String flat = flat(raw);

        assertThat(flat).startsWith("From: billing@synapse.test\nTo: ap@example.com\nSubject: Invoice INV-1\n"
            + "MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"===============");
        assertThat(flat).contains("Content-Type: text/plain; charset=\"utf-8\"\nContent-Transfer-Encoding: 7bit\n\nBody.\n");
        String boundary = flat.substring(flat.indexOf("boundary=\"") + 10, flat.indexOf("\"\n"));
        assertThat(flat).endsWith("--" + boundary + "--\n");
        // Exactly two parts: text, then the attachment — no nested multipart/related.
        assertThat(flat.split(Pattern.quote("--" + boundary + "\n"), -1)).hasSize(3);
    }

    @Test
    void singlePartCarriesTheReferencesHeaderOrder() {
        String flat = flat(MimeBuilder.build("noreply@synapse.test", "u@example.com", "Reset your password",
            "Short ascii body.", List.of()));
        assertThat(flat).isEqualTo("From: noreply@synapse.test\nTo: u@example.com\nSubject: Reset your password\n"
            + "Content-Type: text/plain; charset=\"utf-8\"\nContent-Transfer-Encoding: 7bit\nMIME-Version: 1.0\n\n"
            + "Short ascii body.\n");
    }

    @Test
    void longAsciiLinesAreQuotedPrintableAndNonAsciiIsBase64() {
        String longLine = "Invoice INV-202609-0001 for 1,999.00 PHP is attached. Payment instructions are included in the PDF.";
        assertThat(MimeBuilder.textEncoding(longLine + "\n")).isEqualTo("quoted-printable");
        assertThat(MimeBuilder.textEncoding("Total ₱1,999.00\n")).isEqualTo("base64");
        assertThat(MimeBuilder.textEncoding("Short.\n")).isEqualTo("7bit");

        String encoded = MimeBuilder.quotedPrintable(longLine + "\n");
        for (String line : encoded.split("\r\n")) {
            assertThat(line.length()).as("soft-wrapped below the policy line length").isLessThanOrEqualTo(78);
        }
        assertThat(encoded.replace("=\r\n", "").replace("\r\n", "")).isEqualTo(longLine);
    }
}
