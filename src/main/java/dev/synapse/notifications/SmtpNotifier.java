package dev.synapse.notifications;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.metrics.FrameworkMetrics;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * SMTP notifier (reference: {@code notifications/smtp.py}), failing soft.
 *
 * <p>Transport security follows {@code SYNAPSE_SMTP_TLS}: {@code ssl} is
 * implicit TLS from the first byte (465), {@code starttls} upgrades after the
 * hello (587), {@code none} is plaintext (MailHog or a trusted local relay).
 * Credentials are refused over a plaintext channel — never sent in the clear.
 */
public final class SmtpNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(SmtpNotifier.class);
    private static final int TIMEOUT_MILLIS = 10_000;

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
            JavaMailSenderImpl sender = sender();
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, !attachments.isEmpty(), "UTF-8");
            helper.setFrom(props.smtpFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            for (Attachment attachment : attachments) {
                helper.addAttachment(attachment.filename(),
                    new org.springframework.core.io.ByteArrayResource(attachment.content()), attachment.contentType());
            }
            sender.send(message);
            log.info("email_sent to={} subject={} attachments={}", to, subject, attachments.size());
            metrics.email("sent");
        } catch (Exception e) {
            log.warn("email_send_failed to={} subject={} error={}", to, subject, e.toString());
            metrics.email("failed");
        }
    }

    private JavaMailSenderImpl sender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(props.smtpHost());
        sender.setPort(props.smtpPort());
        Properties mail = new Properties();
        mail.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        mail.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        mail.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        if ("ssl".equals(props.smtpTls())) {
            mail.put("mail.smtp.ssl.enable", "true");
        } else if ("starttls".equals(props.smtpTls())) {
            mail.put("mail.smtp.starttls.enable", "true");
            mail.put("mail.smtp.starttls.required", "true");
        }
        if (!props.smtpUsername().isBlank()) {
            if ("none".equals(props.smtpTls())) {
                throw new IllegalStateException("SMTP AUTH over a plaintext connection is refused; set SYNAPSE_SMTP_TLS");
            }
            sender.setUsername(props.smtpUsername());
            sender.setPassword(props.smtpPassword());
            mail.put("mail.smtp.auth", "true");
        }
        sender.setJavaMailProperties(mail);
        return sender;
    }
}
