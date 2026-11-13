package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.stream.Collectors;

/**
 * The row-level-security scope of an order-workflow request made by an operator (design D42). Operators have no home
 * school, so the tenant tables admit them only through {@code app.operator_schools}: this sets it, for the current
 * transaction, to every school where the operator works any item (school-wide assignments plus well-formed item scopes,
 * {@link TenantContext#operatorSchoolsForAnyItem()}, the same set the order workflow repository applies). Which item the
 * operator may work at which school is then checked per order by {@code OrderAccess}, against the order's own (school,
 * category) pair; this scope only lets the rows be read at all.
 *
 * <p>Superadmin needs nothing (the connection already bypasses RLS) and every other caller is refused before any read, so
 * for them this does nothing.
 */
@Component
public class WorkflowRequestScope {
    private final JdbcClient jdbc;

    public WorkflowRequestScope(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Admits the operator's schools for the rest of the current transaction.
     *
     * @throws IllegalStateException outside a transaction, where a transaction-local setting would end with its statement
     */
    public void applyOperatorScope() {
        TenantContext ctx = TenantContext.get();
        if (!ctx.isOperations()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The operator scope is transaction-local; apply it inside a transaction");
        }
        String csv = ctx.operatorSchoolsForAnyItem().stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.sql("SELECT set_config('app.operator_schools', :csv, true)").param("csv", csv).query(String.class).single();
    }
}
