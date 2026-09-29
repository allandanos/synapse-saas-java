package dev.synapse.billing.invoicing;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code invoices}: framework-generated drafts and provider-sourced upserts. */
@Repository
public class InvoiceRepository {

    private static final String COLUMNS = """
        id, organization_id, billing_customer_id, provider, provider_invoice_id, number, currency, subtotal_cents, tax_cents,
        total_cents, status, period_start, period_end, hosted_url, pdf_url, issued_at, paid_at, created_at
        """;

    private final JdbcClient jdbc;
    private final RowMapper<Invoice> mapper = (rs, i) -> new Invoice(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), Rows.uuid(rs, "billing_customer_id"), rs.getString("provider"),
        rs.getString("provider_invoice_id"), rs.getString("number"), rs.getString("currency"), rs.getLong("subtotal_cents"),
        rs.getLong("tax_cents"), rs.getLong("total_cents"), rs.getString("status"), Rows.instant(rs, "period_start"),
        Rows.instant(rs, "period_end"), rs.getString("hosted_url"), rs.getString("pdf_url"), Rows.instant(rs, "issued_at"),
        Rows.instant(rs, "paid_at"), Rows.instant(rs, "created_at"));

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Invoice> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM invoices WHERE id = :id").param("id", id).query(mapper).optional();
    }

    /** Tenant list route: newest first, capped like the reference's 50. */
    public List<Invoice> forOrg(UUID organizationId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM invoices WHERE organization_id = :org ORDER BY created_at DESC LIMIT :limit")
            .param("org", organizationId).param("limit", limit).query(mapper).list();
    }

    /** The open draft for a period, if one was already generated (idempotent drafting). */
    public Optional<Invoice> findDraft(UUID organizationId, LocalDate periodStart) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM invoices WHERE organization_id = :org AND status = 'draft' "
                + "AND date(period_start) = :period ORDER BY created_at DESC LIMIT 1")
            .param("org", organizationId).param("period", periodStart).query(mapper).optional();
    }

    public Optional<Invoice> findByProviderRef(String provider, String providerInvoiceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM invoices WHERE provider = :provider AND provider_invoice_id = :ref")
            .param("provider", provider).param("ref", providerInvoiceId).query(mapper).optional();
    }

    /** Only the org can be known up front; everything else is set by the caller's save. */
    public UUID insert(UUID organizationId, UUID billingCustomerId, String provider, String currency, long subtotalCents, long taxCents,
                       long totalCents, String status, Instant periodStart, Instant periodEnd) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO invoices (id, organization_id, billing_customer_id, provider, currency, subtotal_cents, tax_cents, total_cents,
                                      status, period_start, period_end)
                VALUES (:id, :org, :customer, :provider, :currency, :subtotal, :tax, :total, :status, :periodStart, :periodEnd)
                """)
            .param("id", id).param("org", organizationId).param("customer", billingCustomerId).param("provider", provider)
            .param("currency", currency).param("subtotal", subtotalCents).param("tax", taxCents).param("total", totalCents)
            .param("status", status).param("periodStart", Rows.at(periodStart)).param("periodEnd", Rows.at(periodEnd))
            .update();
        return id;
    }

    public Invoice save(Invoice invoice) {
        jdbc.sql("""
                UPDATE invoices SET billing_customer_id = :customer, provider = :provider, provider_invoice_id = :providerRef,
                       number = :number, currency = :currency, subtotal_cents = :subtotal, tax_cents = :tax, total_cents = :total,
                       status = :status, period_start = :periodStart, period_end = :periodEnd, hosted_url = :hostedUrl,
                       pdf_url = :pdfUrl, issued_at = :issuedAt, paid_at = :paidAt
                WHERE id = :id
                """)
            .param("id", invoice.id()).param("customer", invoice.billingCustomerId()).param("provider", invoice.provider())
            .param("providerRef", invoice.providerInvoiceId()).param("number", invoice.number()).param("currency", invoice.currency())
            .param("subtotal", invoice.subtotalCents()).param("tax", invoice.taxCents()).param("total", invoice.totalCents())
            .param("status", invoice.status()).param("periodStart", Rows.at(invoice.periodStart()))
            .param("periodEnd", Rows.at(invoice.periodEnd())).param("hostedUrl", invoice.hostedUrl()).param("pdfUrl", invoice.pdfUrl())
            .param("issuedAt", Rows.at(invoice.issuedAt())).param("paidAt", Rows.at(invoice.paidAt()))
            .update();
        return findById(invoice.id()).orElseThrow();
    }

    /** Numbers are assigned per org: the count of already-numbered invoices decides the next one. */
    public long numberedCount(UUID organizationId) {
        return jdbc.sql("SELECT count(*) FROM invoices WHERE organization_id = :org AND number IS NOT NULL")
            .param("org", organizationId).query(Long.class).single();
    }

    /** Serialise numbering per org: two finalizes racing would otherwise collide on uq_invoices_org_number. */
    public void lockOrganization(UUID organizationId) {
        jdbc.sql("SELECT id FROM organizations WHERE id = :org FOR UPDATE").param("org", organizationId).query(UUID.class).optional();
    }

    public UUID organizationOf(UUID invoiceId) {
        return jdbc.sql("SELECT organization_id FROM invoices WHERE id = :id").param("id", invoiceId).query(UUID.class).optional().orElse(null);
    }
}
