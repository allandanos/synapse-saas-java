package dev.synapse.billing.invoicing;

import dev.synapse.core.db.Json;
import dev.synapse.core.db.Rows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code invoice_lines}; {@code organization_id} is denormalised so RLS polices lines directly. */
@Repository
public class InvoiceLineRepository {

    private final JdbcClient jdbc;
    private final Json json;
    private final RowMapper<InvoiceLine> mapper;

    public InvoiceLineRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new InvoiceLine(Rows.uuid(rs, "id"), Rows.uuid(rs, "invoice_id"), Rows.uuid(rs, "organization_id"),
            rs.getString("kind"), rs.getString("description"), rs.getLong("quantity"), rs.getLong("unit_amount_cents"),
            rs.getLong("amount_cents"), rs.getString("metric"), json.readMap(rs.getString("properties")));
    }

    public List<InvoiceLine> forInvoice(UUID invoiceId) {
        return jdbc.sql("""
                SELECT id, invoice_id, organization_id, kind, description, quantity, unit_amount_cents, amount_cents, metric,
                       properties::text AS properties
                FROM invoice_lines WHERE invoice_id = :invoice ORDER BY created_at, id
                """)
            .param("invoice", invoiceId).query(mapper).list();
    }

    public void insertAll(UUID invoiceId, UUID organizationId, List<InvoiceLine.Draft> lines) {
        for (InvoiceLine.Draft line : lines) {
            jdbc.sql("""
                    INSERT INTO invoice_lines (id, invoice_id, organization_id, kind, description, quantity, unit_amount_cents,
                                               amount_cents, metric, properties)
                    VALUES (:id, :invoice, :org, :kind, :description, :quantity, :unit, :amount, :metric, CAST(:properties AS jsonb))
                    """)
                .param("id", UUID.randomUUID()).param("invoice", invoiceId).param("org", organizationId).param("kind", line.kind())
                .param("description", line.description()).param("quantity", line.quantity()).param("unit", line.unitAmountCents())
                .param("amount", line.amountCents()).param("metric", line.metric()).param("properties", json.write(line.properties()))
                .update();
        }
    }
}
