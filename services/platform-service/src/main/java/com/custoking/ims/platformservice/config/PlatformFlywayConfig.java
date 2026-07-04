package com.custoking.ims.platformservice.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import com.zaxxer.hikari.HikariDataSource;
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

    /**
     * Force JPA (entityManagerFactory) to wait for all three per-schema Flyway migrations.
     * Spring Boot's Flyway auto-config — which normally injects this ordering — is disabled
     * here, so on an empty database Hibernate schema validation would otherwise run before the
     * migrations and fail with "missing table". This restores Flyway-before-JPA ordering.
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor platformFlywayEmfDependsOn() {
        return new EntityManagerFactoryDependsOnPostProcessor(
                "reportingFlyway", "notificationFlyway", "auditFlyway");
    }

    // One-shot, sequential migrations need only a tiny pool; default Hikari (max 10, min-idle 10)
    // would hold idle connections per schema forever after migrating and exhaust Cloud SQL (53300).
    // Pool must allow >=2: Flyway holds a lock connection while acquiring a second for the
    // migration, so max-1 deadlocks. min-idle=0 drains the pool back to ~0 after migrating.
    private DataSource migrationDs(String url, String user, String pass) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(pass);
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(0);
        ds.setIdleTimeout(10000);
        ds.setPoolName("flyway-migration");
        return ds;
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
