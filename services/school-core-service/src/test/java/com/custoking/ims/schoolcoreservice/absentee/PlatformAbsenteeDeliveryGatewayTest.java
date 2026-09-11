package com.custoking.ims.schoolcoreservice.absentee;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The live gateway calls platform-service's internal delivery endpoint over HTTP with the shared
 * service token plus a Cloud Run OIDC bearer, and maps the peer's outcome onto the worker's
 * {@link DeliveryOutcome} vocabulary. Transport problems are transient (retried with backoff);
 * a peer dead-letter is permanent; a policy suppression is terminal.
 */
class PlatformAbsenteeDeliveryGatewayTest {

    private static final String BASE_URL = "https://custoking-platform-service-dev-123.asia-south2.run.app";
    private static final String ENDPOINT = BASE_URL + "/api/v1/internal/notifications/deliveries";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private PlatformAbsenteeDeliveryGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PlatformAbsenteeDeliveryGateway(builder.build(), BASE_URL, "status-token",
                audience -> audience.equals(BASE_URL) ? "oidc-token" : "", "approved_absence_v1", objectMapper);
    }

    @Test
    void sendsTheNotificationRequestedContractWithServiceTokenAndOidcBearer() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Notification-Service-Token", "status-token"))
                .andExpect(header("Authorization", "Bearer oidc-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    JsonNode body = objectMapper.readTree(request.getBody().toString());
                    assertThat(body.path("eventId").asText()).isEqualTo("school-core:absentee:row-1");
                    assertThat(body.path("eventType").asText()).isEqualTo("notification.requested.v1");
                    assertThat(body.path("aggregateType").asText()).isEqualTo("AbsenteeNotification");
                    assertThat(body.path("aggregateId").asText()).isEqualTo("row-1");
                    JsonNode payload = body.path("payload");
                    assertThat(payload.path("sourceEventType").asText()).isEqualTo("attendance.absentee-notification-requested.v1");
                    assertThat(payload.path("sourceEventId").asText()).isEqualTo("school-core:absentee:row-1");
                    assertThat(payload.path("absenteeRequestId").asText()).isEqualTo("school-core:absentee:row-1");
                    assertThat(payload.path("notificationType").asText()).isEqualTo("ABSENTEE_ALERT");
                    assertThat(payload.path("template").asText()).isEqualTo("absentee-alert.v1");
                    assertThat(payload.path("templateName").asText()).isEqualTo("approved_absence_v1");
                    assertThat(payload.path("channel").asText()).isEqualTo("WHATSAPP");
                    assertThat(payload.path("destination").asText()).isEqualTo("919999999999");
                    assertThat(payload.path("schoolId").asLong()).isEqualTo(10L);
                    assertThat(payload.path("studentId").asLong()).isEqualTo(7L);
                    assertThat(payload.path("recipientType").asText()).isEqualTo("GUARDIAN");
                    assertThat(payload.path("recipientId").asText()).isEqualTo("guardian-1");
                    assertThat(payload.path("policyEvidence").path("decision").asText()).isEqualTo("ALLOW");
                    assertThat(payload.path("variables").path("attendanceDate").asText()).isEqualTo("2026-09-10");
                    assertThat(payload.has("msg91Body")).isFalse();
                })
                .andRespond(withSuccess("""
                        {"eventId":"school-core:absentee:row-1","status":"DELIVERED","dryRun":false,
                         "provider":"msg91","attempts":1,"providerMessageId":"msg-1"}
                        """, MediaType.APPLICATION_JSON));

        DeliveryOutcome outcome = gateway.deliver(request());

        server.verify();
        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.DELIVERED);
        assertThat(outcome.provider()).isEqualTo("msg91");
        assertThat(outcome.providerMessageId()).isEqualTo("msg-1");
    }

    @Test
    void peerDryRunIsNeverReportedAsDelivered() {
        respond("""
                {"eventId":"school-core:absentee:row-1","status":"DELIVERED","dryRun":true,"provider":"logging","attempts":1}
                """);

        DeliveryOutcome outcome = gateway.deliver(request());

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.DRY_RUN);
        assertThat(outcome.provider()).isEqualTo("logging");
    }

    @Test
    void peerSuppressionIsTerminalWithItsReasonCode() {
        respond("""
                {"eventId":"school-core:absentee:row-1","status":"SUPPRESSED","dryRun":true,"provider":"logging",
                 "attempts":1,"error":"POLICY_EVIDENCE_STALE"}
                """);

        DeliveryOutcome outcome = gateway.deliver(request());

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.SUPPRESSED);
        assertThat(outcome.error()).isEqualTo("POLICY_EVIDENCE_STALE");
    }

    @Test
    void peerFailureIsTransientAndPeerDeadLetterIsPermanent() {
        respond("""
                {"eventId":"school-core:absentee:row-1","status":"FAILED","dryRun":false,"provider":"msg91",
                 "attempts":2,"error":"MSG91 delivery failed"}
                """);
        respond("""
                {"eventId":"school-core:absentee:row-1","status":"DEAD_LETTER","dryRun":false,"provider":"msg91",
                 "attempts":8,"error":"MSG91 delivery failed"}
                """);

        DeliveryOutcome failed = gateway.deliver(request());
        DeliveryOutcome dead = gateway.deliver(request());

        assertThat(failed.kind()).isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(failed.error()).isEqualTo("MSG91 delivery failed");
        assertThat(dead.kind()).isEqualTo(DeliveryOutcome.Kind.PERMANENT_FAILURE);
    }

    @Test
    void transportAndAuthFailuresAreTransientAndNameTheStatus() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new java.net.SocketTimeoutException("read timed out")));

        DeliveryOutcome forbidden = gateway.deliver(request());
        DeliveryOutcome badGateway = gateway.deliver(request());
        DeliveryOutcome timeout = gateway.deliver(request());

        assertThat(forbidden.kind()).isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(forbidden.error()).contains("403");
        assertThat(badGateway.kind()).isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(badGateway.error()).contains("502");
        assertThat(timeout.kind()).isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(timeout.error()).contains("read timed out");
    }

    @Test
    void unparseableOrUnknownPeerAnswerIsTransient() {
        respond("""
                {"eventId":"school-core:absentee:row-1","status":"SOMETHING_NEW"}
                """);

        DeliveryOutcome outcome = gateway.deliver(request());

        assertThat(outcome.kind()).isEqualTo(DeliveryOutcome.Kind.TRANSIENT_FAILURE);
        assertThat(outcome.error()).contains("SOMETHING_NEW");
    }

    private void respond(String json) {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static AbsenteeDeliveryRequest request() {
        OffsetDateTime now = OffsetDateTime.now();
        DispatchDecision decision = DispatchDecision.allowed("guardian-1", "919999999999", "consent-1", "notice-v1",
                "WHATSAPP", 10L, 7L, now, now.plusSeconds(90), "school-core:absentee:row-1");
        return new AbsenteeDeliveryRequest("school-core:absentee:row-1", "row-1", 10L, 7L, "WHATSAPP",
                "919999999999", "guardian-1", LocalDate.of(2026, 9, 10),
                "Dear Parent, A was marked absent.", Map.copyOf(decision.evidence()));
    }
}
