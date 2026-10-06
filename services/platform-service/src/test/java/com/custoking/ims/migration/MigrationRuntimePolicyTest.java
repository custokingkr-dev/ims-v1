package com.custoking.ims.migration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MigrationRuntimePolicyTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class EmptyApplication {}
    private SpringApplication cloudRunApplication(Map<String, Object> overrides) {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        var properties = new java.util.HashMap<String, Object>();
        properties.put("K_SERVICE", "fixture-cloud-service");
        properties.put("spring.profiles.active", "prod");
        properties.putAll(overrides);
        application.setDefaultProperties(properties);
        return application;
    }
    @Test void realCloudRunBootRejectsMissingAndEnabledMigrationFlagsEvenWithLowercaseDisablement() {
        assertThatThrownBy(cloudRunApplication(Map.of("app.migrations.enabled", "false"))::run)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_MIGRATIONS_ENABLED=false");
        assertThatThrownBy(cloudRunApplication(Map.of("APP_MIGRATIONS_ENABLED", "true"))::run)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_MIGRATIONS_ENABLED=false");
    }
    @Test void realCloudRunBootRejectsMissingSharedAndOtherImageRoles() {
        String expected = MigrationRuntimePolicy.imageRuntimeRole();
        for (String wrong : new String[]{"", "app_rt", "postgres", expected.equals("ims_identity_rt") ? "ims_platform_rt" : "ims_identity_rt"}) {
            var properties = new java.util.HashMap<String, Object>();
            properties.put("APP_MIGRATIONS_ENABLED", "false");
            if (!wrong.isEmpty()) properties.put("RUNTIME_DB_ROLE", wrong);
            assertThatThrownBy(cloudRunApplication(properties)::run).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("image-specific dedicated RUNTIME_DB_ROLE");
        }
    }
    @Test void realCloudRunBootAcceptsOnlyExplicitDisabledMigrationsAndPackagedServiceRole() {
        try (var context = cloudRunApplication(Map.of("APP_MIGRATIONS_ENABLED", "false",
                "RUNTIME_DB_ROLE", MigrationRuntimePolicy.imageRuntimeRole(), "spring.flyway.enabled", "true", "app.runtime-db-role", "app_rt")).run()) {
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("app.runtime-db-role"))
                .isEqualTo(MigrationRuntimePolicy.imageRuntimeRole());
        }
        assertThatThrownBy(cloudRunApplication(Map.of("APP_MIGRATIONS_ENABLED", "false",
            "RUNTIME_DB_ROLE", MigrationRuntimePolicy.imageRuntimeRole(), "FLYWAY_PASSWORD", "owner-secret"))::run)
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Migration-disabled runtime")
            .hasMessageNotContaining("owner-secret");
    }
    @Test void realBootProdProfileFailsClosedWhenServerStillReceivesOwnerSecret() {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.of("APP_MIGRATIONS_ENABLED","false","spring.profiles.active","prod","FLYWAY_PASSWORD","fixture-owner-secret"));
        assertThatThrownBy(application::run).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Migration-disabled runtime").hasMessageNotContaining("fixture-owner-secret");
    }
    @Test void realBootProdProfileLoadsWithoutOwnerCredentialsAndOverridesRequestedAutoMigration() {
        var application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.of("APP_MIGRATIONS_ENABLED","false","spring.profiles.active","prod","spring.flyway.enabled","true"));
        try (var context = application.run()) {
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("app.migrations.enabled")).isEqualTo("false");
        }
    }
    @Test void explicitLowercaseOwnerOverrideCannotEvadeDisabledPolicy() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("command-line", Map.of("APP_MIGRATIONS_ENABLED","false","app.migrations.enabled","true","spring.flyway.password","owner-secret")));
        assertThatThrownBy(() -> new MigrationRuntimePolicy().postProcessEnvironment(env,new SpringApplication()))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("owner-secret");
    }
    @Test void disabledRuntimeOverridesFlywayEnableAndNeedsNoOwnerCredentials() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("app.migrations.enabled","false","spring.flyway.enabled","true")));
        new MigrationRuntimePolicy().postProcessEnvironment(env,new SpringApplication());
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.migrations.enabled")).isEqualTo("false");
    }
    @Test void disabledRuntimeRejectsOwnerSecretWithoutDisclosingIt() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("app.migrations.enabled","false","FLYWAY_PASSWORD","fixture-super-secret")));
        assertThatThrownBy(() -> new MigrationRuntimePolicy().postProcessEnvironment(env,new SpringApplication()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("FLYWAY_PASSWORD").hasMessageNotContaining("fixture-super-secret");
    }
    @Test void localDefaultRetainsMigrationsAndMalformedFlagFails() {
        var env = new StandardEnvironment();
        new MigrationRuntimePolicy().postProcessEnvironment(env,new SpringApplication());
        assertThat(env.getPropertySources().contains("migration-disabled-runtime")).isFalse();
        env.getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("app.migrations.enabled","sometimes")));
        assertThatThrownBy(() -> new MigrationRuntimePolicy().postProcessEnvironment(env,new SpringApplication())).isInstanceOf(IllegalStateException.class);
    }
    @Test void bootDiscoversRuntimePolicyAndUnsupportedServicesFailBeforeConnecting() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(org.springframework.boot.EnvironmentPostProcessor.class,getClass().getClassLoader()))
            .contains(MigrationRuntimePolicy.class.getName());
        assertThatThrownBy(() -> MigrationOnlyMain.plan("untrusted-service")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MigrationOnlyMain.migrate(Map.of("APP_MIGRATION_SERVICE", switch (MigrationRuntimePolicy.imageRuntimeRole()) {
            case "ims_identity_rt" -> "identity-service";
            case "ims_school_core_rt" -> "school-core-service";
            case "ims_operations_rt" -> "operations-service";
            case "ims_platform_rt" -> "platform-service";
            case "ims_billing_rt" -> "billing-service";
            default -> throw new IllegalStateException("Unknown fixture image");
        })))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FLYWAY_URL");
    }
}
