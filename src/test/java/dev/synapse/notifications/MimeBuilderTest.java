package dev.synapse.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The message bytes are a contract: the console's {@code invoice-email.spec.ts}
 * reads MailHog's raw MIME and matches the attachment part with a regex that
 * pins the header order the reference's {@code EmailMessage} produces.
 */
class MimeBuilderTest {

    /** apps/web/e2e/invoice-email.spec.ts — copied verbatim, \n for \r\n as the spec flattens first. */
    private static final Pattern SPEC_ATTACHMENT = Pattern.compile(
        "Content-Type: application/pdf\nContent-Transfer-Encoding: base64\n"
            + "Content-Disposition: attachment; filename=\"invoice-[^\"]+\\.pdf\"\nMIME-Version: 1\\.0\n\n([A-Za-z0-9+/=\n]+)");

    private static String flat(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void attachmentPartMatchesTheSpecRegexAndDecodesToThePdf() {
        byte[] pdf = "%PDF-1.5\nthe invoice bytes".getBytes(StandardCharsets.UTF_8);
        byte[] raw = MimeBuilder.build("billing@synapse.test", "ap@example.com",
            "Invoice INV-202609-0001: 1,999.00 PHP due",
            "Invoice INV-202609-0001 for 1,999.00 PHP is attached. Payment instructions are included in the PDF.",
            List.of(new Attachment("invoice-INV-202609-0001.pdf", pdf, "application/pdf")));

        String flat = flat(raw);
        Matcher matcher = SPEC_ATTACHMENT.matcher(flat);
        assertThat(matcher.find()).as("the spec's attachment regex matches:\n%s", flat).isTrue();
        assertThat(Base64.getMimeDecoder().decode(matcher.group(1))).isEqualTo(pdf);
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
