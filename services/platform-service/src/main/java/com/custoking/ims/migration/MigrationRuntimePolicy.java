package com.custoking.ims.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

/** Runtime disablement is authoritative over profile/command-line Flyway settings. */
public final class MigrationRuntimePolicy implements EnvironmentPostProcessor, Ordered {
    private static final Map<String, String> IMAGE_ROLES = Map.of(
        "com.custoking.ims.identityservice.IdentityServiceApplication", "ims_identity_rt",
        "com.custoking.ims.schoolcoreservice.SchoolCoreServiceApplication", "ims_school_core_rt",
        "com.custoking.ims.operationsservice.OperationsServiceApplication", "ims_operations_rt",
        "com.custoking.ims.platformservice.PlatformServiceApplication", "ims_platform_rt",
        "com.custoking.ims.billingservice.BillingServiceApplication", "ims_billing_rt");

    // Bind to the packaged application, never a caller-controlled service-name setting.
    static String imageRuntimeRole() {
        String role = null;
        for (var entry : IMAGE_ROLES.entrySet()) {
            try {
                Class.forName(entry.getKey(), false, MigrationRuntimePolicy.class.getClassLoader());
                if (role != null) throw new IllegalStateException("Ambiguous runtime application image");
                role = entry.getValue();
            } catch (ClassNotFoundException absent) {
                // Each service image contains exactly one known application class.
            }
        }
        if (role == null) throw new IllegalStateException("Unknown runtime application image");
        return role;
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!env.getProperty("K_SERVICE", "").isBlank()) {
            if (!"false".equalsIgnoreCase(env.getProperty("APP_MIGRATIONS_ENABLED")))
                throw new IllegalStateException("Cloud Run runtime requires explicit APP_MIGRATIONS_ENABLED=false");
            String expectedRole = imageRuntimeRole();
            if (!expectedRole.equals(env.getProperty("RUNTIME_DB_ROLE")))
                throw new IllegalStateException("Cloud Run runtime requires its image-specific dedicated RUNTIME_DB_ROLE: " + expectedRole);
            env.getPropertySources().addFirst(new MapPropertySource("cloud-run-runtime-role",
                Map.of("app.runtime-db-role", expectedRole)));
        }
        String enabled = env.getProperty("APP_MIGRATIONS_ENABLED", env.getProperty("app.migrations.enabled", "true"));
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false"))
            throw new IllegalStateException("APP_MIGRATIONS_ENABLED must be true or false");
        if (Boolean.parseBoolean(enabled)) return;
        // Do not resolve flyway.password placeholders: local defaults reference runtime credentials.
        // Explicit owner secret/config must never be attached to a migration-disabled server.
        for (String key : new String[]{"FLYWAY_PASSWORD","FLYWAY_USERNAME","FLYWAY_URL","SPRING_FLYWAY_PASSWORD","SPRING_FLYWAY_USER","SPRING_FLYWAY_URL"})
            if (env.containsProperty(key))
                throw new IllegalStateException("Migration-disabled runtime must not receive owner configuration: " + key);
        for (var source : env.getPropertySources()) {
            for (String key : new String[]{"flyway.password","flyway.user","flyway.url","spring.flyway.password","spring.flyway.user","spring.flyway.url"}) {
                Object raw = source.getProperty(key);
                if (raw instanceof String text && !text.startsWith("${"))
                    throw new IllegalStateException("Migration-disabled runtime must not receive explicit owner configuration: " + key);
            }
        }
        env.getPropertySources().addFirst(new MapPropertySource("migration-disabled-runtime", Map.of(
            "spring.flyway.enabled", false, "app.migrations.enabled", false)));
    }
}
