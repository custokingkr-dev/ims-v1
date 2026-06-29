package com.custoking.ims.attendanceservice.security;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps the auto-configured application DataSource (Hikari, connecting as app_rt) in a
 * TenantAwareDataSource. Flyway uses its own separate datasource (spring.flyway.* → appuser),
 * which is NOT a registered bean and so is not wrapped — migrations run as the owner and
 * bypass RLS.
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    public static BeanPostProcessor tenantDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }
}
