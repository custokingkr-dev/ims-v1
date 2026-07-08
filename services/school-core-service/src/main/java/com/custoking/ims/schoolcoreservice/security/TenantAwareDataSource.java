package com.custoking.ims.schoolcoreservice.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Sets the per-request tenant GUCs on every borrowed connection so PostgreSQL RLS
 * resolves to the authenticated school. Session-level set_config (false) so the value
 * applies to autocommit reads and transactional writes alike; overwritten on each borrow.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenantGucs(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenantGucs(super.getConnection(username, password));
    }

    private Connection applyTenantGucs(Connection connection) throws SQLException {
        TenantContext ctx = TenantContext.get();
        String schoolId = ctx.schoolId() == null ? "" : ctx.schoolId().toString();
        String bypass = ctx.isSuperAdmin() ? "on" : "off";
        // Reset app.operator_schools to empty (fail-closed) on every checkout so a stale operator
        // scope can never leak across the pool. The operator-scope setter uses a transaction-local
        // (is_local=true) set_config, so this session-level reset is defense-in-depth only.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('app.current_school_id', ?, false), set_config('app.bypass_rls', ?, false), set_config('app.operator_schools', '', false)")) {
            ps.setString(1, schoolId);
            ps.setString(2, bypass);
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
