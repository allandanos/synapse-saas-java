package dev.synapse.notifications;

import java.util.List;

/**
 * The email transport seam (reference: {@code notifications/__init__.py}).
 * Swap the transport by binding a different implementation; the protocol
 * carries attachments so any transport can honour them. Delivery failure is
 * logged and swallowed — an email must never fail the outbox dispatch that
 * carries it.
 */
public interface Notifier {

    void send(String to, String subject, String body, List<Attachment> attachments);

    default void send(String to, String subject, String body) {
        send(to, subject, body, List.of());
    }
}
