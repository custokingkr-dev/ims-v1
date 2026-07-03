package com.custoking.ims.operationsservice.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;

/**
 * The operations-service persists two independent domains (workflow, firefighting)
 * in two separate Postgres schemas. Spring Boot's single auto-configured Flyway bean
 * is disabled (spring.flyway.enabled=false); instead we register one Flyway instance
 * per schema, each with its own migration location and its own history table in its
 * own schema, preserving each domain's independent V1.. sequence exactly as it ran
 * pre-merge (see ADR-001).
 */
@Configuration
public class OperationsFlywayConfig {

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
    public Flyway workflowFlyway(@Value("${flyway.url}") String url,
                                  @Value("${flyway.user}") String user,
                                  @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("workflow")
                .defaultSchema("workflow")
                .locations("classpath:db/migration/workflow")
                .table("flyway_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("workflowFlyway")
    public Flyway firefightingFlyway(@Value("${flyway.url}") String url,
                                      @Value("${flyway.user}") String user,
                                      @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("firefighting")
                .defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting")
                .table("flyway_schema_history")
                .load();
    }
}
