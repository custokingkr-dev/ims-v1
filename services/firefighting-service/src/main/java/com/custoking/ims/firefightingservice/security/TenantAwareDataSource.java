package com.custoking.ims.firefightingservice.security;

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
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('app.current_school_id', ?, false), set_config('app.bypass_rls', ?, false)")) {
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
