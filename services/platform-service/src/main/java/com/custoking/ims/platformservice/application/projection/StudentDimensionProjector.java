package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.DimensionProjectionRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Projects student upserts and permanent-deletion tombstones from school-core into the
 * {@code reporting.dim_student} read model, per Reporting Decoupling SP5. Student is a
 * dimension like school/section/academic-year (SP1's {@link ReferenceDimensionProjector}), so
 * this mirrors that projector's shape exactly: not feed-worthy — student upserts must not
 * create command_center_feed rows.
 */
@Component
public class StudentDimensionProjector implements ReportingEventProjector {

    private static final String STUDENT_UPSERTED = "student.upserted.v1";
    private static final String STUDENT_DELETED = "student.deleted.v1";

    private final DimensionProjectionRepository dims;
    private final ObjectMapper objectMapper;

    public StudentDimensionProjector(DimensionProjectionRepository dims, ObjectMapper objectMapper) {
        this.dims = dims;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(STUDENT_UPSERTED, STUDENT_DELETED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        Long id = PayloadJson.longOrNull(payload, "id");
        if (id == null) {
            return;
        }
        if (STUDENT_DELETED.equals(event.eventType())) {
            dims.deleteStudent(id, eventTime(event));
            return;
        }
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String admissionNo = PayloadJson.textOrNull(payload, "admissionNo");
        String fullName = PayloadJson.textOrNull(payload, "fullName");
        String rollNo = PayloadJson.textOrNull(payload, "rollNo");
        String classId = PayloadJson.textOrNull(payload, "classId");
        String sectionId = PayloadJson.textOrNull(payload, "sectionId");
        String parentContact = PayloadJson.textOrNull(payload, "parentContact");
        String phone = PayloadJson.textOrNull(payload, "phone");
        boolean active = PayloadJson.boolOrFalse(payload, "active");
        java.math.BigDecimal attendancePercent = PayloadJson.decimalOrNull(payload, "attendancePercent");
        String fatherName = PayloadJson.textOrNull(payload, "fatherName");
        dims.upsertStudent(id, schoolId, admissionNo, fullName, rollNo, classId, sectionId,
                parentContact, phone, active, attendancePercent, fatherName);
    }

    private static OffsetDateTime eventTime(
            ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        return event.occurredAt() != null ? event.occurredAt() : event.receivedAt();
    }
}
