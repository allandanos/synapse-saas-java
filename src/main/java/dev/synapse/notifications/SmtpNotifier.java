package dev.synapse.notifications;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.metrics.FrameworkMetrics;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMTP notifier (reference: {@code notifications/smtp.py}), failing soft.
 *
 * <p>Transport security follows {@code SYNAPSE_SMTP_TLS}: {@code ssl} is
 * implicit TLS from the first byte (465), {@code starttls} upgrades after the
 * hello (587), {@code none} is plaintext (MailHog or a trusted local relay).
 * Credentials are refused over a plaintext channel — never sent in the clear.
 *
 * <p>The message is composed by {@link MimeBuilder} and handed to
 * {@link Transport#sendMessage} verbatim: a {@code MimeMessage} parsed from
 * bytes is already "saved", so Jakarta Mail re-emits the headers we wrote
 * instead of regenerating its own MIME (see {@link MimeBuilder} for why the
 * exact bytes matter).
 */
public final class SmtpNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(SmtpNotifier.class);
    private static final String TIMEOUT_MILLIS = "10000";

    private final SynapseProperties props;
    private final FrameworkMetrics metrics;

    public SmtpNotifier(SynapseProperties props, FrameworkMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public void send(String to, String subject, String body, List<Attachment> attachments) {
        if (props.smtpHost().isBlank()) {
            log.info("notification_suppressed_no_smtp to={} subject={} attachments={}", to, subject, attachments.size());
            metrics.email("suppressed");
            return;
        }
        try {
            byte[] raw = MimeBuilder.build(props.smtpFrom(), to, subject, body, attachments);
            Session session = Session.getInstance(sessionProperties());
            MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(raw));
            try (Transport transport = session.getTransport(props.smtpTls().equals("ssl") ? "smtps" : "smtp")) {
                if (props.smtpUsername().isBlank()) {
                    transport.connect(props.smtpHost(), props.smtpPort(), null, null);
                } else {
                    transport.connect(props.smtpHost(), props.smtpPort(), props.smtpUsername(), props.smtpPassword());
                }
                transport.sendMessage(message, message.getAllRecipients());
            }
            log.info("email_sent to={} subject={} attachments={}", to, subject, attachments.size());
            metrics.email("sent");
        } catch (Exception e) {
            log.warn("email_send_failed to={} subject={} error={}", to, subject, e.toString());
            metrics.email("failed");
        }
    }

    private Properties sessionProperties() {
        Properties mail = new Properties();
        mail.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        mail.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        mail.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        mail.put("mail.smtps.connectiontimeout", TIMEOUT_MILLIS);
        mail.put("mail.smtps.timeout", TIMEOUT_MILLIS);
        mail.put("mail.smtps.writetimeout", TIMEOUT_MILLIS);
        if ("starttls".equals(props.smtpTls())) {
            mail.put("mail.smtp.starttls.enable", "true");
            mail.put("mail.smtp.starttls.required", "true");
        }
        if (!props.smtpUsername().isBlank()) {
            if ("none".equals(props.smtpTls())) {
                throw new IllegalStateException("SMTP AUTH over a plaintext connection is refused; set SYNAPSE_SMTP_TLS");
            }
            mail.put("mail.smtp.auth", "true");
            mail.put("mail.smtps.auth", "true");
        }
        return mail;
    }
}
