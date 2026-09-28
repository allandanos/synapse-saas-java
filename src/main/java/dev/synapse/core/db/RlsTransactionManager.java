package dev.synapse.core.db;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** {@link DataSourceTransactionManager} that binds the RLS GUCs on the transaction's connection after {@code BEGIN}. */
public class RlsTransactionManager extends DataSourceTransactionManager {

    private final transient RlsGucs gucs;

    public RlsTransactionManager(DataSource dataSource, RlsGucs gucs) {
        super(dataSource);
        this.gucs = gucs;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        gucs.applyToCurrentTransaction();
    }
}
