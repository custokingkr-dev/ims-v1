package com.custoking.ims.schoolcoreservice.absentee;

import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Wires the absentee delivery worker. Mode selection is configuration only:
 *
 * <pre>
 *   ATTENDANCE_ABSENTEE_DELIVERY_MODE = dry-run (default) | live
 *   PLATFORM_BASE_URL                 = https://custoking-platform-service-&lt;env&gt;-&lt;n&gt;.asia-south2.run.app   (live)
 *   PLATFORM_CLOUD_RUN_AUTH           = auto (default) | never | always                                     (live)
 *   NOTIFICATION_STATUS_TOKEN         = the same secret platform-service reads as notification.status.token (live)
 *   ATTENDANCE_ABSENTEE_WHATSAPP_TEMPLATE_NAME = approved MSG91 WhatsApp template (live; blank until supplied)
 * </pre>
 *
 * Live mode with an incomplete configuration fails startup rather than dead-lettering every row.
 */
@Configuration
@EnableConfigurationProperties(AbsenteeDeliveryProperties.class)
public class AbsenteeDeliveryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AbsenteeDeliveryConfiguration.class);

    @Bean
    AbsenteeDeliveryGateway absenteeDeliveryGateway(AbsenteeDeliveryProperties properties,
                                                    RestClient.Builder restClientBuilder,
                                                    ObjectMapper objectMapper,
                                                    @Value("${platform.base-url:}") String platformBaseUrl,
                                                    @Value("${platform.notification-status-token:}") String serviceToken,
                                                    @Value("${platform.cloud-run-auth:auto}") String cloudRunAuthMode,
                                                    @Value("${platform.connect-timeout-ms:3000}") int connectTimeoutMs,
                                                    @Value("${platform.read-timeout-ms:10000}") int readTimeoutMs) {
        if (!properties.isLive()) {
            return new DryRunAbsenteeDeliveryGateway(properties.getWhatsappTemplateName());
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(0, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(0, readTimeoutMs));
        RestClient client = restClientBuilder.clone().requestFactory(requestFactory).build();
        return new PlatformAbsenteeDeliveryGateway(client, platformBaseUrl, serviceToken,
                CloudRunIdentityTokenSupplier.metadataServer(cloudRunAuthMode),
                properties.getWhatsappTemplateName(), objectMapper);
    }

    @Bean
    AbsenteeDeliveryWorker absenteeDeliveryWorker(AbsenteeNotificationDeliveryRepository repository,
                                                  AbsenteeDispatchPolicy policy,
                                                  AbsenteeDeliveryGateway gateway,
                                                  PlatformTransactionManager transactionManager,
                                                  AbsenteeDeliveryProperties properties) {
        AbsenteeDeliveryStateMachine machine = new AbsenteeDeliveryStateMachine(
                properties.getMaxAttempts(), properties.getInitialBackoff(), properties.getMaxBackoff());
        return new AbsenteeDeliveryWorker(repository, policy, gateway, transactionManager, machine,
                properties.getBatchSize(), Clock.systemUTC());
    }

    @Bean
    ApplicationRunner absenteeDeliveryStartupCheck(AbsenteeDeliveryProperties properties,
                                                   @Value("${platform.base-url:}") String platformBaseUrl,
                                                   @Value("${platform.notification-status-token:}") String serviceToken) {
        return args -> {
            validate(properties, platformBaseUrl, serviceToken);
            log.info("absentee.delivery.mode mode={} batchSize={} maxAttempts={} whatsappTemplateName={}",
                    properties.getMode(), properties.getBatchSize(), properties.getMaxAttempts(),
                    properties.getWhatsappTemplateName().isBlank() ? "(unset)" : properties.getWhatsappTemplateName());
        };
    }

    static void validate(AbsenteeDeliveryProperties properties, String platformBaseUrl, String serviceToken) {
        String mode = properties.getMode();
        if (!AbsenteeDeliveryProperties.MODE_DRY_RUN.equals(mode) && !AbsenteeDeliveryProperties.MODE_LIVE.equals(mode)) {
            throw new IllegalStateException("attendance.absentee-delivery.mode must be 'dry-run' or 'live', got '" + mode + "'");
        }
        if (!properties.isLive()) {
            return;
        }
        if (!StringUtils.hasText(platformBaseUrl)) {
            throw new IllegalStateException("attendance.absentee-delivery.mode=live requires platform.base-url (PLATFORM_BASE_URL)");
        }
        if (!StringUtils.hasText(serviceToken)) {
            throw new IllegalStateException("attendance.absentee-delivery.mode=live requires platform.notification-status-token (NOTIFICATION_STATUS_TOKEN)");
        }
    }
}
