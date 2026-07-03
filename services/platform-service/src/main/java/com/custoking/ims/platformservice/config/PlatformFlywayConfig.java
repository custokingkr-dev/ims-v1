package com.custoking.ims.platformservice.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;

/**
 * The platform-service persists three independent domains (reporting, notification,
 * audit) in three separate Postgres schemas. Spring Boot's single auto-configured
 * Flyway bean is disabled (spring.flyway.enabled=false); instead we register one
 * Flyway instance per schema, each with its own migration location and its own
 * history table in its own schema, preserving each domain's independent V1.. sequence
 * exactly as it ran pre-merge (see ADR-001).
 */
@Configuration
public class PlatformFlywayConfig {

    private DataSource migrationDs(String url, String user, String pass) {
        return DataSourceBuilder.create().url(url).username(user).password(pass).build();
    }

    @Bean(initMethod = "migrate")
    public Flyway reportingFlyway(@Value("${flyway.url}") String url,
                                   @Value("${flyway.user}") String user,
                                   @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("reporting")
                .defaultSchema("reporting")
                .locations("classpath:db/migration/reporting")
                .table("flyway_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("reportingFlyway")
    public Flyway notificationFlyway(@Value("${flyway.url}") String url,
                                      @Value("${flyway.user}") String user,
                                      @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("notification")
                .defaultSchema("notification")
                .locations("classpath:db/migration/notification")
                .table("flyway_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("notificationFlyway")
    public Flyway auditFlyway(@Value("${flyway.url}") String url,
                               @Value("${flyway.user}") String user,
                               @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("audit")
                .defaultSchema("audit")
                .locations("classpath:db/migration/audit")
                .table("flyway_schema_history")
                .load();
    }
}
