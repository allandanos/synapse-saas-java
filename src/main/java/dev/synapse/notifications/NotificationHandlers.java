package dev.synapse.notifications;

import dev.synapse.billing.BillingCustomer;
import dev.synapse.billing.BillingCustomerRepository;
import dev.synapse.billing.invoicing.Invoice;
import dev.synapse.billing.invoicing.InvoiceLine;
import dev.synapse.billing.invoicing.InvoiceLineRepository;
import dev.synapse.billing.invoicing.InvoicePdf;
import dev.synapse.billing.invoicing.InvoiceRepository;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.outbox.Events;
import dev.synapse.identity.User;
import dev.synapse.identity.UserRepository;
import dev.synapse.tenancy.Organization;
import dev.synapse.tenancy.OrganizationRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email templates and the outbox-event → email mapping
 * (reference: {@code notifications/handlers.py}).
 *
 * <p>The worker's outbox dispatch invokes {@link #handleEvent} AFTER the events
 * are durably published, so a retry can never resend an invite or an invoice.
 * Every handler is best effort: an email problem must never fail the dispatch.
 */
@Component
public class NotificationHandlers {

    private static final Logger log = LoggerFactory.getLogger(NotificationHandlers.class);
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    private final Notifier notifier;
    private final SynapseProperties props;
    private final InvoiceRepository invoices;
    private final InvoiceLineRepository invoiceLines;
    private final BillingCustomerRepository customers;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final InvoicePdf pdf;

    public NotificationHandlers(Notifier notifier, SynapseProperties props, InvoiceRepository invoices, InvoiceLineRepository invoiceLines,
                                BillingCustomerRepository customers, OrganizationRepository organizations, UserRepository users,
                                InvoicePdf pdf) {
        this.notifier = notifier;
        this.props = props;
        this.invoices = invoices;
        this.invoiceLines = invoiceLines;
        this.customers = customers;
        this.organizations = organizations;
        this.users = users;
        this.pdf = pdf;
    }

    /** Map one outbox event to at most one email. Unknown events are ignored — email is opt-in per type. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void handleEvent(String eventType, Map<String, Object> payload) {
        switch (eventType) {
            case Events.MEMBER_INVITE_EMAIL -> inviteEmail(payload);
            case Events.USER_PASSWORD_RESET_LINK -> passwordResetEmail(payload);
            case Events.INVOICE_EMAIL -> invoiceEmail(payload);
            case Events.USAGE_SOFT_LIMIT_REACHED -> softLimitEmail(payload);
            default -> { /* other events intentionally unhandled */ }
        }
    }

    private void inviteEmail(Map<String, Object> payload) {
        String email = text(payload, "email");
        String token = text(payload, "invite_token");
        String org = payload.get("org_name") == null ? "an organization" : String.valueOf(payload.get("org_name"));
        if (email == null || token == null) {
            log.debug("invite_email_skipped reason=missing fields");
            return;
        }
        // The console accepts invite tokens on registration
        String link = webUrl("/register?invite=" + token);
        notifier.send(email, "You've been invited to " + org,
            "Someone invited you to " + org + ".\n\n"
                + "Accept your invitation by registering with this link:\n" + link + "\n\n"
                + "If you weren't expecting this, you can ignore this email.");
    }

    private void passwordResetEmail(Map<String, Object> payload) {
        String email = text(payload, "email");
        String token = text(payload, "token");
        if (email == null || token == null) {
            return;
        }
        String link = webUrl("/reset-password?reset=" + token); // the console's reset form reads ?reset=
        notifier.send(email, "Reset your password",
            "A password reset was requested for your account.\n\n"
                + "Reset it here (valid 30 minutes):\n" + link + "\n\n"
                + "If you didn't request this, ignore this email.");
    }

    /**
     * Invoice delivery: the finalize/pay hooks queue {@code {invoice_id}} and the
     * PDF is regenerated at send time, so the attachment always matches the
     * invoice's current state.
     */
    private void invoiceEmail(Map<String, Object> payload) {
        String invoiceId = text(payload, "invoice_id");
        if (invoiceId == null) {
            log.debug("invoice_email_skipped reason=missing invoice_id");
            return;
        }
        Invoice invoice = invoices.findById(UUID.fromString(invoiceId)).orElse(null);
        if (invoice == null) {
            log.warn("invoice_email_invoice_missing invoice_id={}", invoiceId);
            return;
        }
        Organization organization = organizations.findById(invoice.organizationId()).orElse(null);
        String orgName = organization == null ? "Customer" : organization.name();
        String billingEmail = invoice.billingCustomerId() == null ? null
            : customers.findById(invoice.billingCustomerId()).map(BillingCustomer::email).orElse(null);
        // Framework-drafted invoices carry no billing customer: fall through the
        // same chain as every other billing email (customer → settings → owner)
        // instead of dropping the mail on the floor.
        String recipient = billingEmail != null ? billingEmail : billingRecipient(invoice.organizationId());
        if (recipient == null || recipient.isBlank()) {
            log.info("invoice_email_no_recipient invoice_id={}", invoiceId);
            return;
        }

        List<InvoiceLine> lines = invoiceLines.forInvoice(invoice.id());
        byte[] bytes = pdf.render(invoice, lines, orgName, billingEmail, payTo());
        String number = invoice.number() != null ? invoice.number() : invoice.id().toString();
        String total = String.format(Locale.US, "%,.2f", invoice.totalCents() / 100.0) + " " + invoice.currency();
        String subject;
        String body;
        if ("paid".equals(invoice.status())) {
            subject = "Paid: Invoice " + number;
            body = "Your payment for invoice " + number + " (" + total + ") has been received. "
                + "The invoice is attached for your records.";
        } else {
            subject = "Invoice " + number + ": " + total + " due";
            body = "Invoice " + number + " for " + total + " is attached. Payment instructions are included in the PDF.";
        }
        notifier.send(recipient, subject, body, List.of(new Attachment("invoice-" + number + ".pdf", bytes, PDF_CONTENT_TYPE)));
    }

    /** Org-level quota warning to the billing contact. */
    private void softLimitEmail(Map<String, Object> payload) {
        String metric = text(payload, "metric");
        String organizationId = text(payload, "organization_id");
        if (metric == null || organizationId == null) {
            log.debug("soft_limit_email_skipped reason=missing organization_id/metric");
            return;
        }
        String recipient = billingRecipient(UUID.fromString(organizationId));
        if (recipient == null) {
            log.info("soft_limit_email_no_recipient metric={} org={}", metric, organizationId);
            return;
        }
        notifier.send(recipient, "You're approaching your " + metric + " limit",
            "Your organization has used " + payload.get("total") + " of " + payload.get("limit") + " " + metric
                + " for this period.\n\n"
                + "Upgrade or add capacity here: " + webUrl("/dashboard/billing"));
    }

    /** Who gets money/quota mail for an org: billing customer → settings.billing_email → owner. */
    public String billingRecipient(UUID organizationId) {
        String customerEmail = customers.findByOrg(organizationId).map(BillingCustomer::email).orElse(null);
        if (customerEmail != null && !customerEmail.isBlank()) {
            return customerEmail;
        }
        Organization organization = organizations.findById(organizationId).orElse(null);
        if (organization == null) {
            return null;
        }
        String fromSettings = settingsBillingEmail(organization);
        if (fromSettings != null && !fromSettings.isBlank()) {
            return fromSettings;
        }
        if (organization.ownerUserId() == null) {
            return null;
        }
        return users.findById(organization.ownerUserId()).map(User::email).orElse(null);
    }

    private static String settingsBillingEmail(Organization organization) {
        if (organization == null) {
            return null;
        }
        Object value = organization.settings().get("billing_email");
        return value instanceof String email ? email : null;
    }

    private String payTo() {
        return props.manualPayToInstructions().isBlank() ? null : props.manualPayToInstructions();
    }

    private String webUrl(String path) {
        String origin = props.webOrigin();
        return (origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin) + path;
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
