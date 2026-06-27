package com.custoking.ims.reportingservice.api;

import com.custoking.ims.reportingservice.api.compat.ReportingPublicCompatibilityController;
import com.custoking.ims.reportingservice.api.internal.ReportingPubSubPushController;
import com.custoking.ims.reportingservice.persistence.ReportingCommandRepository;
import com.custoking.ims.reportingservice.persistence.ReportingEventInboxRepository;
import com.custoking.ims.reportingservice.persistence.ReportingEventInboxRepository.ReportingEventInboxRecord;
import com.custoking.ims.reportingservice.persistence.ReportingReadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportingReadControllerTest {

    private final ReportingReadRepository reporting = mock(ReportingReadRepository.class);
    private final ReportingCommandRepository commands = mock(ReportingCommandRepository.class);
    private final ReportingReadController controller = new ReportingReadController(reporting, commands, "reporting-token");

    @Test
    void summaryRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.summary("wrong-token", 4L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(reporting, never()).summary(4L);
    }

    @Test
    void feeDefaultersDelegatesFiltersWithValidToken() {
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
        UUID actionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Map<String, Object> request = Map.of("actorId", "9", "schoolId", 4L, "superAdmin", "true");
        Map<String, Object> result = Map.of("id", actionId, "status", "ACCEPTED");
        when(commands.acceptAction(actionId, 9L, 4L, true)).thenReturn(result);

        Map<String, Object> response = controller.acceptAction("reporting-token", actionId, request);

        assertThat(response).isSameAs(result);
        verify(commands).acceptAction(actionId, 9L, 4L, true);
    }

    @Test
    void dismissActionMapsNotFoundCommandToNotFound() {
        UUID actionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(commands.dismissAction(actionId, 9L, "duplicate", 4L, false))
                .thenThrow(new IllegalArgumentException("Action not found"));

        assertThatThrownBy(() -> controller.dismissAction(
                "reporting-token",
                actionId,
                Map.of("actorId", 9L, "schoolId", 4L, "reason", "duplicate")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("Action not found");
                });
    }

    @Test
    void reminderTargetsMapsValidationFailureToBadRequest() {
        Map<String, Object> request = Map.of("schoolId", 4L);
        when(commands.eventPaymentReminderTargets(request))
                .thenThrow(new IllegalArgumentException("eventId is required"));

        assertThatThrownBy(() -> controller.eventPaymentReminderTargets("reporting-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("eventId is required");
                });
    }

    @Test
    void compatibilityWorkspaceBuildsLegacyPayloadFromSummary() {
        ReportingPublicCompatibilityController compat =
                new ReportingPublicCompatibilityController(reporting, commands, "reporting-token");
        when(reporting.summary(4L)).thenReturn(Map.of(
                "students", 125,
                "sections", "8",
                "attendancePercent", 91.5,
                "feeOverdueCount", 3,
                "firefightingActive", 2,
                "pendingApprovals", 1));
        when(reporting.timetable(4L)).thenReturn(List.of(Map.of("id", "1", "day", "Monday")));

        Map<String, Object> response = compat.workspace("reporting-token", 4L);

        assertThat(response).containsKeys("school", "dashboard", "students", "fees", "attendance");
        assertThat(response).containsEntry("timetable", List.of(Map.of("id", "1", "day", "Monday")));
        assertThat(response.get("dashboard"))
                .asInstanceOf(MAP)
                .containsEntry("students", 125.0)
                .containsEntry("sections", 8.0)
                .containsEntry("attendancePercent", 91.5);
    }

    @Test
    void compatibilityAcceptActionMapsValidationFailureToBadRequest() {
        ReportingPublicCompatibilityController compat =
                new ReportingPublicCompatibilityController(reporting, commands, "reporting-token");
        UUID actionId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(commands.acceptAction(actionId, null, null, false))
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
}
