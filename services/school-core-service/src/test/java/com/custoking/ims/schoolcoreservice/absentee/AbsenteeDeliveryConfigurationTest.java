package com.custoking.ims.schoolcoreservice.absentee;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flipping out of dry-run is a configuration change, and a wrong configuration must fail the
 * service at startup rather than dead-letter every row at runtime.
 */
class AbsenteeDeliveryConfigurationTest {

    @Test
    void dryRunIsTheDefaultAndNeedsNoPeerConfiguration() {
        AbsenteeDeliveryProperties properties = new AbsenteeDeliveryProperties();

        assertThatCode(() -> AbsenteeDeliveryConfiguration.validate(properties, "", ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> AbsenteeDeliveryConfiguration.validate(properties, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void liveModeRequiresPlatformBaseUrlAndServiceToken() {
        AbsenteeDeliveryProperties properties = new AbsenteeDeliveryProperties();
        properties.setMode("live");

        assertThatThrownBy(() -> AbsenteeDeliveryConfiguration.validate(properties, "", "token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_BASE_URL");
        assertThatThrownBy(() -> AbsenteeDeliveryConfiguration.validate(properties, "https://platform.run.app", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOTIFICATION_STATUS_TOKEN");
        assertThatCode(() -> AbsenteeDeliveryConfiguration.validate(properties, "https://platform.run.app", "token"))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownModeIsRejectedInsteadOfSilentlyMeaningSomething() {
        AbsenteeDeliveryProperties properties = new AbsenteeDeliveryProperties();
        properties.setMode("production");

        assertThatThrownBy(() -> AbsenteeDeliveryConfiguration.validate(properties, "https://platform.run.app", "token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attendance.absentee-delivery.mode");
    }
}
