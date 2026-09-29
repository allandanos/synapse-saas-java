package dev.synapse.billing;

import dev.synapse.core.db.Rows;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code billing_customers}: one row per org (unique), created lazily by {@code ensureCustomer}. */
@Repository
public class BillingCustomerRepository {

    private static final String COLUMNS =
        "id, organization_id, provider, provider_customer_id, email, name, tax_id, currency, created_at";

    private final JdbcClient jdbc;
    private final RowMapper<BillingCustomer> mapper = (rs, i) -> new BillingCustomer(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), rs.getString("provider"), rs.getString("provider_customer_id"),
        rs.getString("email"), rs.getString("name"), rs.getString("tax_id"), rs.getString("currency"), Rows.local(rs, "created_at"));

    public BillingCustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BillingCustomer> findByOrg(UUID organizationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM billing_customers WHERE organization_id = :org")
            .param("org", organizationId).query(mapper).optional();
    }

    public Optional<BillingCustomer> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM billing_customers WHERE id = :id").param("id", id).query(mapper).optional();
    }

    public BillingCustomer insert(UUID organizationId, String provider, String providerCustomerId, String email, String name, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO billing_customers (id, organization_id, provider, provider_customer_id, email, name, billing_address, currency)
                VALUES (:id, :org, :provider, :providerCustomer, :email, :name, '{}'::jsonb, :currency)
                """)
            .param("id", id).param("org", organizationId).param("provider", provider).param("providerCustomer", providerCustomerId)
            .param("email", email).param("name", name).param("currency", currency)
            .update();
        return findById(id).orElseThrow();
    }
}
