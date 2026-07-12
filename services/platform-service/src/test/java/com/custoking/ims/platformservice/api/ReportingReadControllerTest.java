package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.api.compat.ReportingPublicCompatibilityController;
import com.custoking.ims.platformservice.api.dto.EventContributionReminderTargetsRequest;
import com.custoking.ims.platformservice.api.internal.ReportingPubSubPushController;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository.ReportingEventInboxRecord;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.api.dto.CommandCenterActionRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportingReadControllerTest {

    private final ReportingReadRepository reporting = mock(ReportingReadRepository.class);
    private final ReportingCommandRepository commands = mock(ReportingCommandRepository.class);
    private final ReportingReadController controller = new ReportingReadController(reporting, commands, "reporting-token");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void summaryRejectsInvalidTokenBeforeQuerying() {
        // Token check fires before TenantScope; no TenantContext needed.
        assertThatThrownBy(() -> controller.summary("wrong-token", 4L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(reporting, never()).summary(4L);
    }

    @Test
    void feeDefaultersDelegatesFiltersWithValidToken() {
        // SUPERADMIN context so TenantScope passes through the requested schoolId.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> result = Map.of("content", List.of(), "totalElements", 0);
        when(reporting.feeDefaulters(4L, "class-9", "section-a", 30, "PENDING", 1, 25)).thenReturn(result);

        Map<String, Object> response = controller.feeDefaulters(
                "reporting-token",
                4L,
                "class-9",
                "section-a",
                30,
                "PENDING",
                1,
                25);

        assertThat(response).isSameAs(result);
        verify(reporting).feeDefaulters(4L, "class-9", "section-a", 30, "PENDING", 1, 25);
    }

    @Test
    void acceptActionParsesActorContextAndDelegates() {
        // SUPERADMIN context: isSuperAdmin()=true, resolveSchoolId(4L)=4L; body superAdmin flag ignored.
        // actor comes from the authenticated principal (TenantContext userId=1L), not the request body's actorId.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        UUID actionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CommandCenterActionRequest request = new CommandCenterActionRequest(9L, 4L, null);
        Map<String, Object> result = Map.of("id", actionId, "status", "ACCEPTED");
        when(commands.acceptAction(actionId, 1L, 4L, true)).thenReturn(result);

        Map<String, Object> response = controller.acceptAction("reporting-token", actionId, request);

        assertThat(response).isSameAs(result);
        verify(commands).acceptAction(actionId, 1L, 4L, true);
    }

    @Test
    void dismissActionMapsNotFoundCommandToNotFound() {
        // ADMIN school 4 context: isSuperAdmin()=false, resolveSchoolId(4L)=4L.
        TenantContext.set(new TenantContext(9L, "a@x", "ADMIN", 4L, null));
        UUID actionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(commands.dismissAction(actionId, 9L, "duplicate", 4L, false))
                .thenThrow(new IllegalArgumentException("Action not found"));

        assertThatThrownBy(() -> controller.dismissAction(
                "reporting-token",
                actionId,
                new CommandCenterActionRequest(9L, 4L, "duplicate")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("Action not found");
                });
    }

    @Test
    void reminderTargets_repoBusinessLogicException_mapsToBadRequest() {
        // eventId and schoolId are valid; repo throws a business-logic BAD_REQUEST (e.g. ACTIVE check).
        // The DTO pre-validates required fields, so repo-level IllegalArgumentException still maps → 400.
        EventContributionReminderTargetsRequest req =
                new EventContributionReminderTargetsRequest("event-1", 4L, null);
        when(commands.eventPaymentReminderTargets(anyMap()))
                .thenThrow(new IllegalArgumentException("Reminders can only be sent for ACTIVE events"));

        assertThatThrownBy(() -> controller.eventPaymentReminderTargets("reporting-token", req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Reminders can only be sent for ACTIVE events");
                });
    }

    @Test
    void compatibilityWorkspaceBuildsLegacyPayloadFromSummary() {
        // SUPERADMIN context so TenantScope passes through the requested schoolId.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        ReportingPublicCompatibilityController compat =
                new ReportingPublicCompatibilityController(reporting, commands, "reporting-token");
        when(reporting.summary(4L)).thenReturn(Map.of(
                "students", 125,
                "sections", "8",
                "attendancePercent", 91.5,
                "feeOverdueCount", 3,
                "firefightingActive", 2,
                "pendingApprovals", 1,
                "academicYearStartMonth", 6,
                "financialYearStartMonth", 7));
        Map<String, Object> response = compat.workspace("reporting-token", 4L);

        assertThat(response).containsKeys("school", "dashboard", "students", "fees", "attendance");
        assertThat(response.get("school"))
                .asInstanceOf(MAP)
                .containsEntry("academicYearStartMonth", 6.0)
                .containsEntry("financialYearStartMonth", 7.0);
        assertThat(response.get("dashboard"))
                .asInstanceOf(MAP)
                .containsEntry("students", 125.0)
                .containsEntry("sections", 8.0)
                .containsEntry("attendancePercent", 91.5);
    }

    @Test
    void compatibilityAcceptActionMapsValidationFailureToBadRequest() {
        // SUPERADMIN context: body=null → actorSchoolId=null, resolveSchoolId(null)=null, isSuperAdmin()=true.
        // actor comes from the authenticated principal (TenantContext userId=1L), not the (absent) request body.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        ReportingPublicCompatibilityController compat =
                new ReportingPublicCompatibilityController(reporting, commands, "reporting-token");
        UUID actionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(commands.acceptAction(actionId, 1L, null, true))
                .thenThrow(new IllegalArgumentException("Access denied to this action"));

        assertThatThrownBy(() -> compat.acceptAction("reporting-token", actionId, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Access denied to this action");
                });
    }

    @Test
    void pubSubRejectsInvalidPushTokenBeforeInboxAccess() throws Exception {
        ReportingEventInboxRepository inbox = mock(ReportingEventInboxRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ReportingPubSubPushController pubSub = new ReportingPubSubPushController(inbox, mapper, "push-token");

        assertThatThrownBy(() -> pubSub.receiveReportingEvent("wrong-token", null, directEnvelope(mapper)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(inbox, never()).exists("event-1");
    }

    @Test
    void pubSubRecordsDirectEventEnvelope() throws Exception {
        ReportingEventInboxRepository inbox = mock(ReportingEventInboxRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ReportingPubSubPushController pubSub = new ReportingPubSubPushController(inbox, mapper, "push-token");
        when(inbox.exists("event-1")).thenReturn(false);

        pubSub.receiveReportingEvent("push-token", null, directEnvelope(mapper));

        ArgumentCaptor<ReportingEventInboxRecord> captor = ArgumentCaptor.forClass(ReportingEventInboxRecord.class);
        verify(inbox).record(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("event-1");
        assertThat(captor.getValue().eventType()).isEqualTo("student.created");
        assertThat(captor.getValue().schoolId()).isEqualTo(4L);
        assertThat(captor.getValue().actorUserId()).isEqualTo(9L);
        assertThat(captor.getValue().occurredAt()).isPresent();
        assertThat(captor.getValue().payload()).contains("\"studentId\":101");
    }

    @Test
    void pubSubRecordsTraceContextAttributesFromPubSubMessage() throws Exception {
        ReportingEventInboxRepository inbox = mock(ReportingEventInboxRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ReportingPubSubPushController pubSub = new ReportingPubSubPushController(inbox, mapper, "push-token");
        when(inbox.exists("event-1")).thenReturn(false);

        pubSub.receiveReportingEvent(null, "push-token", pubSubEnvelopeWithTrace(mapper));

        ArgumentCaptor<ReportingEventInboxRecord> captor = ArgumentCaptor.forClass(ReportingEventInboxRecord.class);
        verify(inbox).record(captor.capture());
        assertThat(captor.getValue().traceParent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(captor.getValue().traceState()).isEqualTo("vendor=value");
    }

    @Test
    void pubSubDecodesPubSubMessageDataAndSkipsExistingEvent() throws Exception {
        ReportingEventInboxRepository inbox = mock(ReportingEventInboxRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        ReportingPubSubPushController pubSub = new ReportingPubSubPushController(inbox, mapper, "push-token");
        when(inbox.exists("event-1")).thenReturn(true);

        pubSub.receiveReportingEvent(null, "push-token", pubSubEnvelope(mapper));

        verify(inbox).exists("event-1");
        verify(inbox, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lowAttendanceSectionsDelegatesDateFilter() {
        // SUPERADMIN context so TenantScope passes through the requested schoolId.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        LocalDate date = LocalDate.parse("2026-02-02");
        Map<String, Object> result = Map.of("sections", List.of());
        when(reporting.lowAttendanceSections(4L, date)).thenReturn(result);

        Map<String, Object> response = controller.lowAttendanceSections("reporting-token", 4L, date);

        assertThat(response).isSameAs(result);
        verify(reporting).lowAttendanceSections(4L, date);
    }

    private JsonNode directEnvelope(ObjectMapper mapper) throws Exception {
        return mapper.readTree("""
                {
                  "schemaVersion": "ims.event-envelope.v1",
                  "eventId": "event-1",
                  "eventKey": "student:event-1",
                  "eventType": "student.created",
                  "eventVersion": "1",
                  "aggregateType": "student",
                  "aggregateId": "101",
                  "schoolId": 4,
                  "actorUserId": 9,
                  "occurredAt": "2026-06-27T00:00:00Z",
                  "payload": {
                    "studentId": 101
                  }
                }
                """);
    }

    private JsonNode pubSubEnvelope(ObjectMapper mapper) throws Exception {
        String data = Base64.getEncoder().encodeToString(
                mapper.writeValueAsString(directEnvelope(mapper)).getBytes(StandardCharsets.UTF_8));
        return mapper.readTree("""
                {
                  "message": {
                    "messageId": "pubsub-message-1",
                    "data": "%s"
                  }
                }
                """.formatted(data));
    }

    private JsonNode pubSubEnvelopeWithTrace(ObjectMapper mapper) throws Exception {
        String data = Base64.getEncoder().encodeToString(
                mapper.writeValueAsString(directEnvelope(mapper)).getBytes(StandardCharsets.UTF_8));
        return mapper.readTree("""
                {
                  "message": {
                    "messageId": "pubsub-message-1",
                    "data": "%s",
                    "attributes": {
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                      "tracestate": "vendor=value"
                    }
                  }
                }
                """.formatted(data));
    }
}

