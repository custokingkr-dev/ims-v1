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
        assertThatThrownBy(() -> MigrationOnlyMain.migrate(Map.of("APP_MIGRATION_SERVICE","school-core-service")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FLYWAY_URL");
    }
}
