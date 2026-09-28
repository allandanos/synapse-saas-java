package dev.synapse.authorization;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PermissionRepository {

    private final JdbcClient jdbc;

    public PermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> existingKeys() {
        return jdbc.sql("SELECT key FROM permissions").query(String.class).stream().collect(Collectors.toSet());
    }

    public void insert(PermissionDef def) {
        jdbc.sql("INSERT INTO permissions (id, key, resource, action, description) VALUES (:id, :key, :resource, :action, :description) ON CONFLICT (key) DO NOTHING")
            .param("id", UUID.randomUUID()).param("key", def.key()).param("resource", def.resource())
            .param("action", def.action()).param("description", def.description())
            .update();
    }
}
