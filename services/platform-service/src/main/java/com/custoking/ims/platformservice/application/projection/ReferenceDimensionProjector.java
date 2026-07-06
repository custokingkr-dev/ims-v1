package com.custoking.ims.platformservice.application.projection;

import com.custoking.ims.platformservice.persistence.DimensionProjectionRepository;
import com.custoking.ims.platformservice.persistence.ReportingEventInboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Projects tenant-school reference/dimension events ({@code school.upserted.v1},
 * {@code school-section.upserted.v1}, {@code academic-year.upserted.v1}) into the
 * {@code reporting.dim_*} read models. Not feed-worthy: reference-dimension events must not
 * create command_center_feed rows (SP1 behavior, preserved here).
 */
@Component
public class ReferenceDimensionProjector implements ReportingEventProjector {

    private static final String SCHOOL_UPSERTED = "school.upserted.v1";
    private static final String SCHOOL_SECTION_UPSERTED = "school-section.upserted.v1";
    private static final String ACADEMIC_YEAR_UPSERTED = "academic-year.upserted.v1";

    private final DimensionProjectionRepository dims;
    private final ObjectMapper objectMapper;

    public ReferenceDimensionProjector(DimensionProjectionRepository dims, ObjectMapper objectMapper) {
        this.dims = dims;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(SCHOOL_UPSERTED, SCHOOL_SECTION_UPSERTED, ACADEMIC_YEAR_UPSERTED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        String eventType = event.eventType() == null ? "" : event.eventType();
        switch (eventType) {
            case SCHOOL_UPSERTED -> projectSchool(event);
            case SCHOOL_SECTION_UPSERTED -> projectSection(event);
            case ACADEMIC_YEAR_UPSERTED -> projectAcademicYear(event);
            default -> { /* unreachable: handledEventTypes() restricts routing */ }
        }
    }

    private void projectSchool(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        Long id = PayloadJson.longOrNull(payload, "id");
        if (id == null) {
            return;
        }
        String name = PayloadJson.textOrNull(payload, "name");
        String shortCode = PayloadJson.textOrNull(payload, "shortCode");
        String city = PayloadJson.textOrNull(payload, "city");
        String state = PayloadJson.textOrNull(payload, "state");
        boolean active = PayloadJson.boolOrFalse(payload, "active");
        dims.upsertSchool(id, name, shortCode, city, state, active);
    }

    private void projectSection(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        String name = PayloadJson.textOrNull(payload, "name");
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String classId = PayloadJson.textOrNull(payload, "classId");
        String className = PayloadJson.textOrNull(payload, "className");
        boolean active = PayloadJson.boolOrFalse(payload, "active");
        String teacherName = PayloadJson.textOrNull(payload, "teacherName");
        dims.upsertSection(id, name, schoolId, classId, className, active, teacherName);
    }

    private void projectAcademicYear(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        String label = PayloadJson.textOrNull(payload, "label");
        boolean active = PayloadJson.boolOrFalse(payload, "active");
        dims.upsertAcademicYear(id, label, active);
    }
}
