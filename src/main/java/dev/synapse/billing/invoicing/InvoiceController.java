package dev.synapse.billing.invoicing;

import dev.synapse.billing.BillingCustomer;
import dev.synapse.billing.BillingCustomerRepository;
import dev.synapse.billing.dto.InvoiceDetailRead;
import dev.synapse.billing.dto.InvoiceDraftRequest;
import dev.synapse.billing.dto.InvoiceRead;
import dev.synapse.billing.dto.PaymentRecordRequest;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.security.Principal;
import dev.synapse.core.validation.Periods;
import dev.synapse.core.web.PlatformAdminOnly;
import dev.synapse.core.web.RequirePermission;
import dev.synapse.tenancy.Organization;
import dev.synapse.tenancy.OrganizationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Framework-native invoicing routes.
 *
 * <p>Tenants draft, finalize, list and download. Money movements — recording a
 * payment, voiding — are the OPERATOR's statement about money that changed
 * hands, so they live under {@code /admin} and answer 404 to tenants (ADR 0008).
 */
@RestController
@RequestMapping("/v1/billing")
public class InvoiceController {

    private final InvoicingService invoicing;
    private final OrganizationService organizations;
    private final BillingCustomerRepository customers;
    private final InvoicePdf pdf;
    private final SynapseProperties props;

    public InvoiceController(InvoicingService invoicing, OrganizationService organizations, BillingCustomerRepository customers,
                             InvoicePdf pdf, SynapseProperties props) {
        this.invoicing = invoicing;
        this.organizations = organizations;
        this.customers = customers;
        this.pdf = pdf;
        this.props = props;
    }

    @GetMapping("/invoices")
    @RequirePermission("billing:read")
    public List<InvoiceRead> list(TenantContext tenant, HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<Invoice> invoices = invoicing.forOrg(tenant.organizationId());
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(invoices.size()));
        return Pagination.sliceInMemory(invoices, limit, offset).stream().map(InvoiceRead::from).toList();
    }

    /** Generate — or return the existing — draft for the period: plan + overage + adjustment lines. */
    @PostMapping("/invoices/draft")
    @RequirePermission("billing:manage")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetailRead draft(@Valid @RequestBody(required = false) InvoiceDraftRequest body, TenantContext tenant, Principal principal) {
        String period = body == null ? null : body.period();
        Invoice invoice = invoicing.draftForOrg(tenant.organizationId(), Periods.firstDay(period, "body", "period"), principal.id());
        return detail(invoice);
    }

    /** Assign a number, lock the amounts, move to open. The outbox carries the webhook + email. */
    @PostMapping("/invoices/{invoiceId}/finalize")
    @RequirePermission("billing:manage")
    public InvoiceDetailRead finalizeInvoice(@PathVariable UUID invoiceId, TenantContext tenant) {
        return detail(invoicing.finalize(invoiceId, tenant.organizationId()));
    }

    @GetMapping("/invoices/{invoiceId}")
    @RequirePermission("billing:read")
    public InvoiceDetailRead get(@PathVariable UUID invoiceId, TenantContext tenant) {
        return detail(invoicing.get(invoiceId, tenant.organizationId()));
    }

    /** The framework-rendered PDF, as an attachment. */
    @GetMapping("/invoices/{invoiceId}/pdf")
    @RequirePermission("billing:read")
    public ResponseEntity<byte[]> download(@PathVariable UUID invoiceId, TenantContext tenant) {
        Invoice invoice = invoicing.get(invoiceId, tenant.organizationId());
        List<InvoiceLine> lines = invoicing.linesFor(invoiceId, tenant.organizationId());
        Organization organization = organizations.get(tenant.organizationId());
        byte[] bytes = pdf.render(invoice, lines, organization.name(), billingEmail(invoice),
            props.manualPayToInstructions().isBlank() ? null : props.manualPayToInstructions());
        String filename = "invoice-" + (invoice.number() != null ? invoice.number() : invoice.id()) + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
            .body(bytes);
    }

    // ── Operator-only money movements ─────────────────────────────────────────────

    /** Record an external payment (bank transfer, cheque, cash) against an open invoice. */
    @PostMapping("/admin/invoices/{invoiceId}/pay")
    @PlatformAdminOnly
    public InvoiceDetailRead recordPayment(@PathVariable UUID invoiceId, @Valid @RequestBody PaymentRecordRequest body) {
        UUID organizationId = invoicing.organizationOf(invoiceId);
        return detail(invoicing.recordPayment(invoiceId, organizationId, body.amountCents(), body.reference()));
    }

    @PostMapping("/admin/invoices/{invoiceId}/void")
    @PlatformAdminOnly
    public InvoiceDetailRead voidInvoice(@PathVariable UUID invoiceId) {
        UUID organizationId = invoicing.organizationOf(invoiceId);
        return detail(invoicing.voidInvoice(invoiceId, organizationId));
    }

    private InvoiceDetailRead detail(Invoice invoice) {
        return InvoiceDetailRead.of(invoice, invoicing.linesFor(invoice.id(), invoice.organizationId()));
    }

    private String billingEmail(Invoice invoice) {
        if (invoice.billingCustomerId() == null) {
            return null;
        }
        return customers.findById(invoice.billingCustomerId()).map(BillingCustomer::email).orElse(null);
    }
}
