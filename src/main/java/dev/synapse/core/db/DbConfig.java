package dev.synapse.core.db;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Persistence is {@code JdbcClient} + records over the fixed baseline schema:
 * the schema is Postgres-specific (citext, text[], jsonb, RLS policies,
 * SECURITY DEFINER lookups) and never generated from code, so an ORM mapping
 * layer would add translation without buying anything. Services are
 * {@code @Transactional}; controllers serialise after the commit.
 */
@Configuration
@EnableTransactionManagement
public class DbConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource, RlsGucs gucs) {
        return new RlsTransactionManager(dataSource, gucs);
    }
}
