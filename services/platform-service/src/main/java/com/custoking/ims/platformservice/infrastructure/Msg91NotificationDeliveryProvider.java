package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.NotificationDeliveryProvider;
import com.custoking.ims.platformservice.application.NotificationDeliveryRequest;
import com.custoking.ims.platformservice.application.SenderProfile;
import com.custoking.ims.platformservice.persistence.SenderProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@EnableConfigurationProperties(Msg91Properties.class)
@ConditionalOnProperty(prefix = "notification.delivery", name = "provider", havingValue = "msg91")
public class Msg91NotificationDeliveryProvider implements NotificationDeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(Msg91NotificationDeliveryProvider.class);

    private final Msg91Properties properties;
    private final SenderProfileRepository senderProfiles;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public Msg91NotificationDeliveryProvider(Msg91Properties properties,
                                             SenderProfileRepository senderProfiles,
                                             ObjectMapper objectMapper,
                                             RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.senderProfiles = senderProfiles;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    @Bean
    ApplicationRunner validateMsg91Configuration() {
        return args -> {
            if (!properties.isDryRun() && properties.getAuthKey().isBlank()) {
                throw new IllegalStateException("notification.msg91.auth-key is required when MSG91_DRY_RUN=false");
            }
        };
    }

    @Override
    public void deliver(NotificationDeliveryRequest request) {
        try {
            Channel channel = Channel.from(request.channel());
            Object body = bodyFor(request);
            send(channel, request, body);
        } catch (Exception ex) {
            throw new IllegalStateException("MSG91 delivery failed for event " + request.eventId(), ex);
        }
    }

    Object bodyFor(NotificationDeliveryRequest request) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode payload = objectMapper.readTree(request.payload());
        Channel channel = Channel.from(request.channel());
        SenderProfile senderProfile = senderProfile(payload);
        return switch (channel) {
            case OTP -> otpBody(payload, senderProfile);
            case SMS -> smsBody(request, payload, senderProfile);
            case EMAIL -> emailBody(request, payload, senderProfile);
            case WHATSAPP -> whatsappBody(request, payload, senderProfile);
        };
    }

    private Object smsBody(NotificationDeliveryRequest request, JsonNode payload, SenderProfile senderProfile) {
        JsonNode passthrough = payload.get("msg91Body");
        if (passthrough != null && !passthrough.isNull()) {
            return objectMapper.convertValue(passthrough, Map.class);
        }

        String mobile = requiredMobile(firstText(payload, "mobile", "phone", "to", "recipientMobile", "destination"));
        String flowId = firstText(payload, "flowId", "templateId", "template_id");
        if (flowId == null || flowId.isBlank()) {
            flowId = senderProfile.msg91SmsFlowId();
        }
        if (flowId == null || flowId.isBlank()) {
            flowId = properties.getSmsFlowId();
        }
        if ((flowId == null || flowId.isBlank()) && properties.isDryRun()) {
            flowId = request.template();
        }
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("mobiles", mobile);
        recipient.putAll(variables(payload));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template_id", required(flowId, "flowId"));
        body.put("recipients", java.util.List.of(recipient));
        putIfPresent(body, "short_url", firstText(payload, "shortUrl", "short_url"));
        return body;
    }

    private Object otpBody(JsonNode payload, SenderProfile senderProfile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mobile", requiredMobile(firstText(payload, "mobile", "phone", "to", "recipientMobile", "destination")));
        String templateId = firstText(payload, "templateId", "template_id");
        if (templateId == null || templateId.isBlank()) {
            templateId = senderProfile.msg91OtpTemplateId();
        }
        if (templateId == null || templateId.isBlank()) {
            templateId = properties.getOtpTemplateId();
        }
        if ((templateId == null || templateId.isBlank()) && properties.isDryRun()) {
            templateId = text(payload, "template");
        }
        body.put("template_id", required(templateId, "templateId"));
        putIfPresent(body, "otp", firstText(payload, "otp", "code"));
        putIfPresent(body, "otp_length", firstText(payload, "otpLength", "otp_length"));
        return body;
    }

    private Object emailBody(NotificationDeliveryRequest request, JsonNode payload, SenderProfile senderProfile) {
        JsonNode passthrough = payload.get("msg91Body");
        if (passthrough != null && !passthrough.isNull()) {
            return objectMapper.convertValue(passthrough, Map.class);
        }

        String email = required(firstText(payload, "email", "to", "recipientEmail", "destination"), "email");
        String name = firstText(payload, "name", "recipientName");
        Map<String, Object> to = new LinkedHashMap<>();
        putIfPresent(to, "name", name);
        to.put("email", email);
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("to", java.util.List.of(to));
        recipient.put("variables", variables(payload));

        Map<String, Object> body = new LinkedHashMap<>();
        String templateId = firstText(payload, "templateId", "template_id", "template");
        if (templateId == null || templateId.isBlank()) {
            templateId = senderProfile.msg91EmailTemplateId();
        }
        putIfPresent(body, "template_id", templateId);
        body.put("recipients", java.util.List.of(recipient));
        String fromAddress = firstNonBlank(senderProfile.emailFromAddress(), properties.getEmailFromAddress());
        if (fromAddress != null && !fromAddress.isBlank()) {
            Map<String, Object> from = new LinkedHashMap<>();
            putIfPresent(from, "name", firstNonBlank(senderProfile.emailFromName(), properties.getEmailFromName()));
            from.put("email", fromAddress);
            body.put("from", from);
        }
        putIfPresent(body, "domain", firstNonBlank(
                firstText(payload, "domain", "emailDomain"),
                firstNonBlank(senderProfile.emailDomain(), firstNonBlank(properties.getEmailDomain(), domainFrom(fromAddress)))));
        putIfPresent(body, "reply_to", senderProfile.emailReplyTo());
        putIfPresent(body, "subject", firstText(payload, "subject"));
        String bodyData = firstText(payload, "body", "html", "text");
        if (bodyData != null && !bodyData.isBlank() && (templateId == null || templateId.isBlank())) {
            Map<String, Object> messageBody = new LinkedHashMap<>();
            messageBody.put("type", bodyData.trim().startsWith("<") ? "text/html" : "text");
            messageBody.put("data", bodyData);
            body.put("body", messageBody);
        }
        return body;
    }

    private Object whatsappBody(NotificationDeliveryRequest request, JsonNode payload, SenderProfile senderProfile) {
        JsonNode passthrough = payload.get("msg91Body");
        if (passthrough != null && !passthrough.isNull()) {
            return objectMapper.convertValue(passthrough, Map.class);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        String integratedNumber = firstText(payload, "integratedNumber", "integrated_number");
        if (integratedNumber == null || integratedNumber.isBlank()) {
            integratedNumber = senderProfile.whatsappIntegratedNumber();
        }
        if (integratedNumber == null || integratedNumber.isBlank()) {
            integratedNumber = properties.getWhatsappIntegratedNumber();
        }
        body.put("integrated_number", required(integratedNumber, "integratedNumber"));
        body.put("recipient_number", requiredMobile(
                firstText(payload, "whatsapp", "mobile", "phone", "to", "recipientMobile", "destination"),
                "recipient_number"));
        String templateName = firstText(payload, "templateName", "template");
        if (templateName == null || templateName.isBlank()) {
            templateName = senderProfile.whatsappDefaultTemplateName();
        }
        body.put("template_name", required(templateName, "templateName"));
        body.put("language", firstText(payload, "language", "languageCode") == null
                ? firstNonBlank(senderProfile.whatsappLanguageCode(), properties.getWhatsappLanguageCode())
                : firstText(payload, "language", "languageCode"));
        putIfPresent(body, "namespace", senderProfile.whatsappTemplateNamespace());
        body.put("variables", variables(payload));
        return body;
    }

    private void send(Channel channel, NotificationDeliveryRequest request, Object body) {
        if (properties.isDryRun()) {
            log.info("msg91.dry-run eventId={} channel={} body={}", request.eventId(), channel.name(), body);
            return;
        }
        if (properties.getAuthKey().isBlank()) {
            throw new IllegalStateException("notification.msg91.auth-key is required when dry-run=false");
        }

        String endpoint = endpoint(channel);
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("authkey", properties.getAuthKey());
        spec.body(body).retrieve().toBodilessEntity();
        log.info("msg91.sent eventId={} channel={}", request.eventId(), channel.name());
    }

    private String endpoint(Channel channel) {
        return switch (channel) {
            case OTP -> properties.getOtpEndpoint();
            case SMS -> properties.getSmsEndpoint();
            case EMAIL -> properties.getEmailEndpoint();
            case WHATSAPP -> properties.getWhatsappTemplateEndpoint();
        };
    }

    private Map<String, Object> variables(JsonNode payload) {
        Map<String, Object> variables = new LinkedHashMap<>();
        JsonNode variableNode = payload.get("variables");
        if (variableNode != null && variableNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = variableNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                variables.put(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class));
            }
        }
        putIfPresent(variables, "recipientType", text(payload, "recipientType"));
        putIfPresent(variables, "recipientId", text(payload, "recipientId"));
        putIfPresent(variables, "sourceEventId", text(payload, "sourceEventId"));
        return variables;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private SenderProfile senderProfile(JsonNode payload) {
        Long schoolId = longValue(payload, "schoolId");
        if (senderProfiles == null) {
            return new SenderProfile(null, schoolId, "Custoking Platform Default",
                    properties.getEmailFromName(), properties.getEmailFromAddress(), properties.getEmailDomain(), null,
                    properties.getWhatsappIntegratedNumber(), "Custoking", null, null,
                    properties.getWhatsappLanguageCode(), properties.getSmsFlowId(),
                    properties.getOtpTemplateId(), null);
        }
        return senderProfiles.resolve(schoolId);
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        return Long.parseLong(value.asText());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String domainFrom(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.lastIndexOf('@');
        return at < 0 || at == email.length() - 1 ? null : email.substring(at + 1);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing MSG91 " + name);
        }
        return value;
    }

    private static String requiredMobile(String value) {
        return requiredMobile(value, "mobile");
    }

    private static String requiredMobile(String value, String name) {
        String mobile = required(value, name).replaceAll("[^0-9]", "");
        if (mobile.length() < 10) {
            throw new IllegalArgumentException("Invalid MSG91 " + name);
        }
        return mobile;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private enum Channel {
        OTP,
        SMS,
        EMAIL,
        WHATSAPP;

        static Channel from(String value) {
            String normalized = value == null ? "" : value.trim().replace("-", "_").toUpperCase();
            if ("WA".equals(normalized)) {
                return WHATSAPP;
            }
            return switch (normalized) {
                case "OTP" -> OTP;
                case "EMAIL", "MAIL" -> EMAIL;
                case "WHATSAPP", "WHATS_APP" -> WHATSAPP;
                default -> SMS;
            };
        }
    }
}
