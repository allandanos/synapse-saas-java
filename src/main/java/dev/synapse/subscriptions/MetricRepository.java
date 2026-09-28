package dev.synapse.subscriptions;

import dev.synapse.core.db.Rows;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code metrics} registry (synced from the catalog; read by usage metering and the entitlement resolver). */
@Repository
public class MetricRepository {

    private static final RowMapper<Metric> MAPPER = (rs, i) -> new Metric(rs.getString("key"), rs.getString("name"), rs.getString("kind"),
        rs.getString("unit"), Rows.intOrNull(rs, "overage_unit"), Rows.longOrNull(rs, "overage_price_cents"));

    private final JdbcClient jdbc;

    public MetricRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Metric> findByKey(String key) {
        return jdbc.sql("SELECT * FROM metrics WHERE key = :key").param("key", key).query(MAPPER).optional();
    }

    public List<Metric> all() {
        return jdbc.sql("SELECT * FROM metrics ORDER BY key").query(MAPPER).list();
    }

    /** Metrics with a catalog default overage price (the resolver's {@code metric_overage} map). */
    public List<Metric> withOverage() {
        return jdbc.sql("SELECT * FROM metrics WHERE overage_price_cents IS NOT NULL ORDER BY key").query(MAPPER).list();
    }

    public void insert(String key, String name, String kind, String unit, Integer overageUnit, Long overagePriceCents) {
        jdbc.sql("INSERT INTO metrics (key, name, kind, unit, overage_unit, overage_price_cents) VALUES (:key, :name, :kind, :unit, :ounit, :oprice)")
            .param("key", key).param("name", name).param("kind", kind).param("unit", unit).param("ounit", overageUnit).param("oprice", overagePriceCents)
            .update();
    }

    public void update(String key, String name, String kind, String unit, Integer overageUnit, Long overagePriceCents) {
        jdbc.sql("UPDATE metrics SET name = :name, kind = :kind, unit = :unit, overage_unit = :ounit, overage_price_cents = :oprice WHERE key = :key")
            .param("key", key).param("name", name).param("kind", kind).param("unit", unit).param("ounit", overageUnit).param("oprice", overagePriceCents)
            .update();
    }
}
