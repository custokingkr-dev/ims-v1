package com.custoking.ims.operationsservice.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
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

    private DataSource migrationDs(String url, String user, String pass) {
        return DataSourceBuilder.create().url(url).username(user).password(pass).build();
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
