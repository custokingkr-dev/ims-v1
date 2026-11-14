import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Test harness for scripts/tests/create_app_rt_role_migrated_test.py. Not part of any service build.
 *
 * Runs Flyway the way MigrationOnlyMain does in every service image (one instance per schema, that schema as both the
 * managed and the default schema, the service's own history table), but from the repository's source folders, so the
 * SQL callbacks (afterMigrate__dedicated_runtime_acl.sql) run exactly as they do in a migration job.
 *
 * Arguments: jdbc-url user, then one "schema|location-directory|history-table|target" per unit, in migration order. An
 * empty target migrates to the latest version. The password comes from IMS_TEST_PG_PASSWORD so it never appears in a
 * process listing.
 */
public final class AppRtFlywayHarness {
    public static void main(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("usage: url user schema|location|table|target...");
        String url = args[0], user = args[1], password = System.getenv("IMS_TEST_PG_PASSWORD");
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("IMS_TEST_PG_PASSWORD is not set");
        for (int i = 2; i < args.length; i++) {
            String[] unit = args[i].split("\\|", -1);
            if (unit.length != 4) throw new IllegalArgumentException("unit must be schema|location|table|target: " + args[i]);
            FluentConfiguration configuration = Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(unit[0])
                    .defaultSchema(unit[0])
                    .locations("filesystem:" + unit[1])
                    .table(unit[2]);
            if (!unit[3].isEmpty()) configuration = configuration.target(unit[3]);
            Flyway flyway = configuration.load();
            MigrateResult result = flyway.migrate();
            System.out.println("MIGRATED " + unit[0] + " executed=" + result.migrationsExecuted
                    + " version=" + flyway.info().current().getVersion().getVersion());
        }
    }
}
