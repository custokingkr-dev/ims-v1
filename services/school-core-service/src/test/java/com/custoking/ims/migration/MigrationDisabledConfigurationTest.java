package com.custoking.ims.migration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.flywaydb.core.Flyway;
class MigrationDisabledConfigurationTest {
    @Test void noCustomOwnerBeansOrJpaMigrationDependenciesExistWhenDisabled() {
        new ApplicationContextRunner().withUserConfiguration(com.custoking.ims.schoolcoreservice.config.SchoolCoreFlywayConfig.class)
            .withPropertyValues("app.migrations.enabled=false")
            .run(context -> org.assertj.core.api.Assertions.assertThat(context).hasNotFailed().doesNotHaveBean(Flyway.class));
    }
}
