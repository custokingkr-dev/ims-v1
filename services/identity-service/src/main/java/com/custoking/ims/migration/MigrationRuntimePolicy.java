package com.custoking.ims.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

/** Runtime disablement is authoritative over profile/command-line Flyway settings. */
public final class MigrationRuntimePolicy implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
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
