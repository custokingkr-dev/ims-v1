package com.custoking.ims.config;

import com.custoking.ims.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Enforces PostgreSQL Row-Level Security (V4 migration) by setting
 * app.current_school_id on every connection borrowed from the pool.
 *
 * The value comes from TenantContext (populated by TenantResolverFilter after JWT auth).
 * Because we always write the variable on every borrow — either to a school ID or to ''
 * — stale values from a previous request on the same pooled connection cannot leak.
 *
 * SUPERADMIN connections should use the postgres/owner Postgres role that BYPASSES RLS
 * entirely; when they use ims_app, the empty string produces NULLIF('','')=NULL which
 * matches no rows — a safe fallback.
 */
@Configuration
public class TenantDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceConfig.class);

    /**
     * Wraps the auto-configured HikariCP DataSource bean before any other bean
     * (JPA, Flyway, health checks) consumes it. Declared static to guarantee early
     * BeanPostProcessor registration without triggering circular-dependency issues.
     */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("dataSource".equals(beanName) && bean instanceof DataSource ds
                        && !(bean instanceof TenantAwareDataSource)) {
                    log.info("Wrapping DataSource with TenantAwareDataSource for RLS enforcement");
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }

    static final class TenantAwareDataSource extends DelegatingDataSource {

        TenantAwareDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = super.getConnection();
            applySchoolId(conn);
            return conn;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection conn = super.getConnection(username, password);
            applySchoolId(conn);
            return conn;
        }

        /**
         * Executes SET SESSION app.current_school_id on the raw connection immediately
         * after it is checked out from the pool.  Using set_config with is_local=false
         * makes it session-scoped; the next getConnection() call always overwrites it,
         * so no explicit reset on return is required.
         */
        private static void applySchoolId(Connection conn) throws SQLException {
            Long id = TenantContext.get();
            String val = id != null ? id.toString() : "";
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT set_config('app.current_school_id', ?, false)")) {
                ps.setString(1, val);
                ps.execute();
            }
        }
    }
}
