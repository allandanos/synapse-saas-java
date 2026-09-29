package dev.synapse.notifications;

import dev.synapse.core.metrics.FrameworkMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Log-only transport: what runs when no SMTP host is configured (or {@code SYNAPSE_NOTIFIER=noop}). */
public final class NoopNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NoopNotifier.class);

    private final FrameworkMetrics metrics;

    public NoopNotifier(FrameworkMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void send(String to, String subject, String body, List<Attachment> attachments) {
        log.info("notification_suppressed to={} subject={} attachments={}", to, subject, attachments.size());
        metrics.email("suppressed");
    }
}
