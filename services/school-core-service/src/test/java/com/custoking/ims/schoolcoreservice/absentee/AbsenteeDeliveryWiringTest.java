package com.custoking.ims.schoolcoreservice.absentee;

import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the bean graph of {@link AbsenteeDeliveryConfiguration} resolves: dry-run by default, the
 * HTTP gateway only when {@code attendance.absentee-delivery.mode=live}. The {@code RestClient.Builder}
 * comes from the real {@link RestClientAutoConfiguration} on purpose: Spring Boot 4 moved it out of
 * {@code spring-boot-starter-web}, and the first full-context run of this service failed exactly there.
 */
class AbsenteeDeliveryWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(AbsenteeDeliveryConfiguration.class)
            .withBean(AbsenteeNotificationDeliveryRepository.class, () -> mock(AbsenteeNotificationDeliveryRepository.class))
            .withBean(AbsenteeDispatchPolicy.class, () -> (schoolId, studentId, channel, sourceEventId) -> DispatchDecision.denied("TEST"))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(AbsenteeDeliveryScheduler.class);

    @Test
    void defaultsToDryRunGatewayWithoutAnyPeerConfiguration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AbsenteeDeliveryWorker.class);
            assertThat(context).hasSingleBean(AbsenteeDeliveryScheduler.class);
            assertThat(context.getBean(AbsenteeDeliveryGateway.class)).isInstanceOf(DryRunAbsenteeDeliveryGateway.class);
            assertThat(context.getBean(AbsenteeDeliveryProperties.class).isLive()).isFalse();
        });
    }

    @Test
    void liveModeSelectsThePlatformHttpGateway() {
        runner.withPropertyValues(
                        "attendance.absentee-delivery.mode=live",
                        "platform.base-url=https://custoking-platform-service-dev-1.asia-south2.run.app",
                        "platform.notification-status-token=secret",
                        "attendance.absentee-delivery.max-attempts=4")
                .run(context -> {
                    assertThat(context.getBean(AbsenteeDeliveryGateway.class)).isInstanceOf(PlatformAbsenteeDeliveryGateway.class);
                    assertThat(((PlatformAbsenteeDeliveryGateway) context.getBean(AbsenteeDeliveryGateway.class)).configured()).isTrue();
                    assertThat(context.getBean(AbsenteeDeliveryProperties.class).getMaxAttempts()).isEqualTo(4);
                });
    }
}
