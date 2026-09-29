package dev.synapse.notifications;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.metrics.FrameworkMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The configured transport: SMTP when a host is set (and not forced to noop), else Noop. */
@Configuration
public class NotifierConfig {

    public static final String NOOP = "noop";

    @Bean
    Notifier notifier(SynapseProperties props, FrameworkMetrics metrics) {
        if (NOOP.equals(props.notifier()) || props.smtpHost().isBlank()) {
            return new NoopNotifier(metrics);
        }
        return new SmtpNotifier(props, metrics);
    }
}
