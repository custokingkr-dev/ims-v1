package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.AttendanceFactReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * Projects {@code attendance-daily.upserted.v1} events (school-core-service outbox) into
 * {@code reporting.fact_attendance_daily}, per Reporting Decoupling SP3. Not feed-worthy:
 * attendance-daily upserts are routine per-section-per-day rollups, not the kind of one-off
 * business event the command center feed surfaces (mirrors the SP1 ReferenceDimensionProjector
 * posture for high-volume/no-feed projections).
 */
@Component
public class AttendanceFactProjector implements ReportingEventProjector {

    private static final String ATTENDANCE_DAILY_UPSERTED = "attendance-daily.upserted.v1";

    private final AttendanceFactReadRepository attendanceFacts;
    private final ObjectMapper objectMapper;

    public AttendanceFactProjector(AttendanceFactReadRepository attendanceFacts, ObjectMapper objectMapper) {
        this.attendanceFacts = attendanceFacts;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(ATTENDANCE_DAILY_UPSERTED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String dateText = PayloadJson.textOrNull(payload, "date");
        LocalDate attendanceDate = dateText == null || dateText.isBlank() ? null : LocalDate.parse(dateText);
        String classId = PayloadJson.textOrNull(payload, "classId");
        String sectionId = PayloadJson.textOrNull(payload, "sectionId");
        String academicYearId = PayloadJson.textOrNull(payload, "academicYearId");
        Integer presentCount = PayloadJson.intOrNull(payload, "presentCount");
        Integer absentCount = PayloadJson.intOrNull(payload, "absentCount");
        Integer lateCount = PayloadJson.intOrNull(payload, "lateCount");
        Integer leaveCount = PayloadJson.intOrNull(payload, "leaveCount");
        Integer totalEnrolled = PayloadJson.intOrNull(payload, "totalEnrolled");
        attendanceFacts.upsert(id, schoolId, attendanceDate, classId, sectionId, academicYearId,
                presentCount, absentCount, lateCount, leaveCount, totalEnrolled);
    }
}
