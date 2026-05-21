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
 * Sets the session variable {@code app.current_school_id} on every connection
 * borrowed from the pool so it is available to any SQL that reads it.
 *
 * NOTE: PostgreSQL Row-Level Security (RLS) is currently DISABLED (migration V117).
 * Tenant isolation is enforced at the application layer via TenantScopeService and
 * scoped user_role_assignments. This config is retained so the session variable is
 * available for auditing and can support re-enabling RLS in the future without code changes.
 *
 * The variable is always written on every connection borrow — either to a school ID
 * or to an empty string — so stale values from a previous request on the same pooled
 * connection cannot leak.
 */
@Configuration
public class TenantDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantDataSourceConfig.class);

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("dataSource".equals(beanName) && bean instanceof DataSource ds
                        && !(bean instanceof TenantAwareDataSource)) {
                    log.info("Wrapping DataSource with TenantAwareDataSource (session variable injection)");
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
