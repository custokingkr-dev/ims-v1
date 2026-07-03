package com.custoking.ims.schoolcoreservice.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;

/**
 * The school-core-service persists five independent domains (tenant-school, student,
 * attendance, fee, catalog) in five separate Postgres schemas. Spring Boot's single
 * auto-configured Flyway bean is disabled (spring.flyway.enabled=false); instead we
 * register one Flyway instance per schema, each with its own migration location and
 * its own history table in its own schema, preserving each domain's independent V1..
 * sequence exactly as it ran pre-merge (see ADR-001).
 */
@Configuration
public class SchoolCoreFlywayConfig {

    private DataSource migrationDs(String url, String user, String pass) {
        return DataSourceBuilder.create().url(url).username(user).password(pass).build();
    }

    @Bean(initMethod = "migrate")
    public Flyway tenantSchoolFlyway(@Value("${flyway.url}") String url,
                                      @Value("${flyway.user}") String user,
                                      @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("tenant_school")
                .defaultSchema("tenant_school")
                .locations("classpath:db/migration/tenant_school")
                // The original tenant-school-service used this non-default history table name;
                // it must match so Flyway finds the already-applied migrations in prod.
                .table("flyway_schema_history_tenant_school")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("tenantSchoolFlyway")
    public Flyway studentFlyway(@Value("${flyway.url}") String url,
                                 @Value("${flyway.user}") String user,
                                 @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("student")
                .defaultSchema("student")
                .locations("classpath:db/migration/student")
                .table("flyway_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("studentFlyway")
    public Flyway attendanceFlyway(@Value("${flyway.url}") String url,
                                    @Value("${flyway.user}") String user,
                                    @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("attendance")
                .defaultSchema("attendance")
                .locations("classpath:db/migration/attendance")
                .table("flyway_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("attendanceFlyway")
    public Flyway feeFlyway(@Value("${flyway.url}") String url,
                             @Value("${flyway.user}") String user,
                             @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("fee")
                .defaultSchema("fee")
                .locations("classpath:db/migration/fee")
                .table("flyway_schema_history")
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("feeFlyway")
    public Flyway catalogFlyway(@Value("${flyway.url}") String url,
                                 @Value("${flyway.user}") String user,
                                 @Value("${flyway.password}") String pass) {
        return Flyway.configure()
                .dataSource(migrationDs(url, user, pass))
                .schemas("catalog")
                .defaultSchema("catalog")
                .locations("classpath:db/migration/catalog")
                .table("flyway_schema_history")
                .load();
    }
}
