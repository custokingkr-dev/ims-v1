package com.custoking.ims.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import java.util.List;
import java.util.Map;

/** One-shot owner process. Deliberately never creates a Spring application context. */
public final class MigrationOnlyMain {
    record Plan(String application, List<String> schemas) {}
    static final Map<String, Plan> PLANS = Map.of(
        "identity-service", new Plan("com.custoking.ims.identityservice.IdentityServiceApplication", List.of("identity")),
        "billing-service", new Plan("com.custoking.ims.billingservice.BillingServiceApplication", List.of("billing")),
        "school-core-service", new Plan("com.custoking.ims.schoolcoreservice.SchoolCoreServiceApplication", List.of("tenant_school","student","attendance","fee","catalog")),
        "operations-service", new Plan("com.custoking.ims.operationsservice.OperationsServiceApplication", List.of("workflow","firefighting")),
        "platform-service", new Plan("com.custoking.ims.platformservice.PlatformServiceApplication", List.of("reporting","notification","audit"))
    );
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Migration process accepts no command-line overrides");
        try { migrate(System.getenv()); }
        catch (Exception failure) {
            // SQL/library errors may contain JDBC credentials or sensitive SQL values.
            System.err.println("Owner migration failed; deployment must remain blocked. Consult restricted database logs.");
            System.exit(1);
        }
    }
    static Plan plan(String service) {
        Plan plan = PLANS.get(service);
        if (plan == null) throw new IllegalArgumentException("APP_MIGRATION_SERVICE must identify a supported service");
        try { Class.forName(plan.application(), false, MigrationOnlyMain.class.getClassLoader()); }
        catch (ClassNotFoundException missing) { throw new IllegalArgumentException("Migration service does not match this image"); }
        return plan;
    }
    static String required(Map<String,String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required migration configuration missing: " + key);
        return value;
    }
    static void migrate(Map<String,String> env) {
        Plan plan = plan(required(env,"APP_MIGRATION_SERVICE"));
        String url = required(env,"FLYWAY_URL");
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Migration requires PostgreSQL JDBC");
        String user = required(env,"FLYWAY_USERNAME"), password = required(env,"FLYWAY_PASSWORD");
        for (String schema : plan.schemas()) {
            try (HikariDataSource owner = new HikariDataSource()) {
                owner.setJdbcUrl(url); owner.setUsername(user); owner.setPassword(password);
                owner.setPoolName("owner-migration-" + schema);
                owner.setMaximumPoolSize(3); owner.setMinimumIdle(0);
                owner.setConnectionTimeout(15000); owner.setValidationTimeout(5000);
                owner.addDataSourceProperty("connectTimeout","10");
                // Per-statement time bound includes historical backfills; fail deployment rather than bypass.
                owner.addDataSourceProperty("socketTimeout","1800");
                owner.setConnectionInitSql("SET statement_timeout='30min'; SET lock_timeout='60s'");
                String location = plan.schemas().size() == 1 ? "classpath:db/migration" : "classpath:db/migration/" + schema;
                var flyway = Flyway.configure().dataSource(owner).schemas(schema).defaultSchema(schema)
                    .locations(location)
                    .table(schema.equals("tenant_school") ? "flyway_schema_history_tenant_school" : "flyway_schema_history")
                    .connectRetries(2).lockRetryCount(60).load();
                var result = flyway.migrate();
                String version = flyway.info().current().getVersion().getVersion();
                if (!version.matches("[0-9.]+")) throw new IllegalStateException("Unexpected migration version");
                System.out.println("OWNER_MIGRATION_RESULT service=" + env.get("APP_MIGRATION_SERVICE")
                    + " schema=" + schema + " version=" + version + " migrationsExecuted=" + result.migrationsExecuted + " success=true");
            }
        }
        System.out.println("Owner migrations completed for " + env.get("APP_MIGRATION_SERVICE"));
    }
}
