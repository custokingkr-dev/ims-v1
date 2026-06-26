package com.custoking.ims.notificationservice.infrastructure;

import com.custoking.ims.notificationservice.application.NotificationDeliveryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Msg91NotificationDeliveryProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsSmsFlowPayload() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setSmsFlowId("flow-123");

        Map<String, Object> body = body(fixture.provider, request("event-1", "SMS", """
                {
                  "destination": "+91 99999 99999",
                  "variables": {
                    "student": "Asha",
                    "amount": 1200
                  }
                }
                """));

        assertThat(body).containsEntry("template_id", "flow-123");
        List<Map<String, Object>> recipients = list(body.get("recipients"));
        assertThat(recipients).hasSize(1);
        assertThat(recipients.getFirst())
                .containsEntry("mobiles", "919999999999")
                .containsEntry("student", "Asha")
                .containsEntry("amount", 1200);
    }

    @Test
    void buildsOtpPayload() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setOtpTemplateId("otp-template");

        Map<String, Object> body = body(fixture.provider, request("event-otp", "OTP", """
                {
                  "destination": "919999999999",
                  "otp": "123456",
                  "otpLength": "6"
                }
                """));

        assertThat(body)
                .containsEntry("mobile", "919999999999")
                .containsEntry("template_id", "otp-template")
                .containsEntry("otp", "123456")
                .containsEntry("otp_length", "6");
    }

    @Test
    void buildsEmailPayload() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setEmailFromName("Custoking");
        fixture.properties.setEmailFromAddress("no-reply@custoking.test");
        fixture.properties.setEmailDomain("custoking.test");

        Map<String, Object> body = body(fixture.provider, request("event-email", "EMAIL", """
                {
                  "destination": "parent@example.test",
                  "recipientName": "Parent",
                  "subject": "Fee reminder",
                  "body": "<p>Hello Asha</p>",
                  "variables": {
                    "student": "Asha"
                  }
                }
                """));

        assertThat(body)
                .containsEntry("domain", "custoking.test")
                .containsEntry("subject", "Fee reminder");
        assertThat(map(body.get("body")))
                .containsEntry("type", "text/html")
                .containsEntry("data", "<p>Hello Asha</p>");
        assertThat(map(body.get("from")))
                .containsEntry("name", "Custoking")
                .containsEntry("email", "no-reply@custoking.test");
        Map<String, Object> recipient = list(body.get("recipients")).getFirst();
        assertThat(map(list(recipient.get("to")).getFirst()))
                .containsEntry("name", "Parent")
                .containsEntry("email", "parent@example.test");
        assertThat(map(recipient.get("variables"))).containsEntry("student", "Asha");
    }

    @Test
    void buildsWhatsappTemplatePayload() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.setWhatsappIntegratedNumber("919888888888");

        Map<String, Object> body = body(fixture.provider, request("event-wa", "WHATSAPP", """
                {
                  "destination": "+91-99999-99999",
                  "templateName": "fee_reminder",
                  "variables": {
                    "student": "Asha"
                  }
                }
                """));

        assertThat(body)
                .containsEntry("integrated_number", "919888888888")
                .containsEntry("recipient_number", "919999999999")
                .containsEntry("template_name", "fee_reminder")
                .containsEntry("language", "en");
        assertThat(map(body.get("variables"))).containsEntry("student", "Asha");
    }

    @Test
    void passesRawMsg91BodyThrough() throws Exception {
        Fixture fixture = fixture();

        Map<String, Object> body = body(fixture.provider, request("event-passthrough", "SMS", """
                {
                  "msg91Body": {
                    "template_id": "tenant-template",
                    "recipients": [
                      {
                        "mobiles": "919999999999",
                        "var1": "custom"
                      }
                    ]
                  }
                }
                """));

        assertThat(body).containsEntry("template_id", "tenant-template");
        assertThat(list(body.get("recipients")).getFirst())
                .containsEntry("mobiles", "919999999999")
                .containsEntry("var1", "custom");
    }

    @Test
    void failsFastWhenProductionAuthKeyMissing() {
        Fixture fixture = fixture();
        fixture.properties.setDryRun(false);

        assertThatThrownBy(() -> fixture.provider.validateMsg91Configuration().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth-key");
    }

    @Test
    void dryRunDeliveryDoesNotRequireAuthKey() {
        Fixture fixture = fixture();
        fixture.properties.setDryRun(true);
        fixture.properties.setSmsFlowId("flow-123");

        fixture.provider.deliver(request("event-dry-run", "SMS", """
                {
                  "destination": "919999999999"
                }
                """));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Msg91NotificationDeliveryProvider provider,
                                     NotificationDeliveryRequest request) throws Exception {
        return (Map<String, Object>) provider.bodyFor(request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private Fixture fixture() {
        Msg91Properties properties = new Msg91Properties();
        Msg91NotificationDeliveryProvider provider =
                new Msg91NotificationDeliveryProvider(properties, null, objectMapper, RestClient.builder());
        return new Fixture(properties, provider);
    }

    private NotificationDeliveryRequest request(String eventId, String channel, String payload) {
        return new NotificationDeliveryRequest(eventId, "template", channel, "student", "1", payload);
    }

    private record Fixture(
            Msg91Properties properties,
            Msg91NotificationDeliveryProvider provider) {
    }
}
