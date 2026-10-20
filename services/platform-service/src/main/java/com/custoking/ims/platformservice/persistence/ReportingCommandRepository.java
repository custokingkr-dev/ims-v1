package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ReportingCommandRepository {

    private final JdbcClient jdbc;
    private final com.custoking.ims.platformservice.infrastructure.SchoolCoreBroadcastRecipientPolicy recipientPolicy;

    public ReportingCommandRepository(JdbcClient jdbc) {
        this(jdbc,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ReportingCommandRepository(JdbcClient jdbc,com.custoking.ims.platformservice.infrastructure.SchoolCoreBroadcastRecipientPolicy recipientPolicy) {
        this.jdbc = jdbc;this.recipientPolicy=recipientPolicy;
    }

    @Transactional
    public Map<String, Object> acceptAction(UUID id, Long actorId) {
        return acceptAction(id, actorId, null, false);
    }

    @Transactional
    public Map<String, Object> acceptAction(UUID id, Long actorId, Long actorSchoolId, boolean superAdmin) {
        requireActionAccess(id, actorSchoolId, superAdmin);
        jdbc.sql("""
                UPDATE reporting.command_center_actions
                SET status = 'ACCEPTED', accepted_by = :actorId,
                    accepted_at = :now, updated_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.now())
                .update();
        Map<String,Object> saved=actionRow(id);
        TrustedCommandAudit.record(jdbc,"COMMAND_ACTION_ACCEPTED",id.toString(),actorId,longObj(saved.get("schoolId")));
        return saved;
    }

    @Transactional
    public Map<String, Object> dismissAction(UUID id, Long actorId, String reason) {
        return dismissAction(id, actorId, reason, null, false);
    }

    @Transactional
    public Map<String, Object> dismissAction(UUID id, Long actorId, String reason, Long actorSchoolId, boolean superAdmin) {
        requireActionAccess(id, actorSchoolId, superAdmin);
        jdbc.sql("""
                UPDATE reporting.command_center_actions
                SET status = 'DISMISSED', dismissed_by = :actorId,
                    dismissed_at = :now, dismissed_reason = :reason, updated_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("actorId", actorId)
                .param("reason", reason)
                .param("now", OffsetDateTime.now())
                .update();
        Map<String,Object> saved=actionRow(id);
        TrustedCommandAudit.record(jdbc,"COMMAND_ACTION_DISMISSED",id.toString(),actorId,longObj(saved.get("schoolId")));
        return saved;
    }

    @Transactional
    public Map<String, Object> recordFeed(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reporting.command_center_feed (
                    id, school_id, module, event_type, title, message, severity,
                    entity_type, entity_id, actor_user_id, created_at
                ) VALUES (
                    :id, :schoolId, :module, :eventType, :title, :message, :severity,
                    :entityType, :entityId, :actorUserId, :createdAt
                )
                """)
                .param("id", id)
                .param("schoolId", longObj(request.get("schoolId")))
                .param("module", str(request.get("module"), "system"))
                .param("eventType", str(request.get("eventType"), "EVENT"))
                .param("title", str(request.get("title"), "Event"))
                .param("message", str(request.get("message"), null))
                .param("severity", str(request.get("severity"), "info"))
                .param("entityType", str(request.get("entityType"), null))
                .param("entityId", str(request.get("entityId"), null))
                .param("actorUserId", longObj(request.get("actorUserId")))
                .param("createdAt", OffsetDateTime.now())
                .update();
        TrustedCommandAudit.record(jdbc,"COMMAND_FEED_RECORDED",id.toString(),null,longObj(request.get("schoolId")));
        return feedRow(id);
    }

    public boolean feedSourceExists(String sourceType, String sourceId) {
        if (sourceType == null || sourceId == null) return false;
        return jdbc.sql("""
                        SELECT 1
                        FROM reporting.command_center_feed
                        WHERE source_type = :sourceType
                          AND source_id = :sourceId
                        """)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Transactional
    public void recordProjectedFeed(ProjectedFeedCommand command) {
        jdbc.sql("""
                INSERT INTO reporting.command_center_feed (
                    id, school_id, module, event_type, title, message, severity,
                    entity_type, entity_id, actor_user_id, created_at, source_type, source_id
                ) VALUES (
                    :id, :schoolId, :module, :eventType, :title, :message, :severity,
                    :entityType, :entityId, :actorUserId, :createdAt, :sourceType, :sourceId
                )
                ON CONFLICT (source_type, source_id) WHERE source_type IS NOT NULL AND source_id IS NOT NULL DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("schoolId", command.schoolId())
                .param("module", command.module())
                .param("eventType", truncate(command.eventType(), 80))
                .param("title", command.title())
                .param("message", command.message())
                .param("severity", command.severity())
                .param("entityType", truncate(command.entityType(), 80))
                .param("entityId", truncate(command.entityId(), 100))
                .param("actorUserId", command.actorUserId())
                .param("createdAt", command.createdAt() == null ? OffsetDateTime.now() : command.createdAt())
                .param("sourceType", command.sourceType())
                .param("sourceId", command.sourceId())
                .update();
    }

    @Transactional
    public Map<String, Object> markEventContributionReminders(Map<String, Object> request) {
        String eventId = str(request.get("eventId"), null);
        Long schoolId = longObj(request.get("schoolId"));
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        List<Long> studentIds = longList(request.get("studentIds"));
        if (studentIds.isEmpty()) {
            return Map.of("updated", 0);
        }
        int updated = jdbc.sql("""
                        UPDATE reporting.event_student_contributions
                        SET last_reminder_sent_at = :now, updated_at = :now
                        WHERE event_id = :eventId
                          AND school_id = :schoolId
                          AND student_id IN (:studentIds)
                        """)
                .param("now", OffsetDateTime.now())
                .param("eventId", eventId)
                .param("schoolId", schoolId)
                .param("studentIds", studentIds)
                .update();
        TrustedCommandAudit.record(jdbc,"EVENT_REMINDERS_MARKED",eventId,null,schoolId);
        return Map.of("updated", updated);
    }

    public Map<String, Object> eventPaymentReminderTargets(Map<String, Object> request) {
        String eventId = str(request.get("eventId"), null);
        Long schoolId = longObj(request.get("schoolId"));
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (schoolId == null) {
            throw new IllegalArgumentException("schoolId is required");
        }
        Map<String, Object> event = jdbc.sql("""
                        SELECT id, title, status, school_id
                        FROM reporting.academic_events
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> row(
                        "eventId", rs.getString("id"),
                        "title", rs.getString("title"),
                        "status", rs.getString("status"),
                        "schoolId", rs.getLong("school_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));
        if (!schoolId.equals(longObj(event.get("schoolId")))) {
            throw new IllegalArgumentException("Cross-school access denied");
        }
        if (!"ACTIVE".equals(String.valueOf(event.get("status")))) {
            throw new IllegalArgumentException("Reminders can only be sent for ACTIVE events");
        }

        List<Long> studentIds = longList(request.get("studentIds"));
        if (studentIds.isEmpty()) {
            return row("eventId", eventId, "eventTitle", event.get("title"), "targets", List.of(), "failed", List.of());
        }
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT c.student_id, c.expected_amount, c.paid_amount,
                               s.full_name, s.father_name,
                               NULL::text AS parent_contact
                        FROM reporting.event_student_contributions c
                        JOIN reporting.dim_student s ON s.id = c.student_id AND s.school_id=c.school_id
                        WHERE c.event_id = :eventId
                          AND c.school_id = :schoolId
                          AND c.student_id IN (:studentIds)
                        """)
                .param("eventId", eventId)
                .param("schoolId", schoolId)
                .param("studentIds", studentIds)
                .query((rs, rowNum) -> row(
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("full_name"),
                        "fatherName", rs.getString("father_name"),
                        "parentContact", rs.getString("parent_contact"),
                        "expectedAmount", rs.getLong("expected_amount"),
                        "paidAmount", rs.getLong("paid_amount")))
                .list();
        java.util.Set<Long> found = rows.stream()
                .map(row -> longObj(row.get("studentId")))
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> failed = new java.util.ArrayList<>();
        for (Long studentId : studentIds) {
            if (!found.contains(studentId)) {
                failed.add(row("studentId", studentId, "reason", "Student not found in this event"));
            }
        }
        if(recipientPolicy==null || !recipientPolicy.configured()) throw new IllegalStateException("Owner fee-reminder policy unavailable");
        List<Long> selected=rows.stream().map(target->longObj(target.get("studentId"))).toList();
        if(selected.size()>100) throw new IllegalArgumentException("Fee-reminder audience exceeds review limit");
        var decisions=selected.isEmpty() ? List.<com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient>of()
                : recipientPolicy.resolveFeeReminders(schoolId,eventId,selected);
        Map<Long,com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient> current=decisions.stream().collect(java.util.stream.Collectors.toMap(d->d.studentId(),d->d));
        List<Map<String,Object>> allowed=new java.util.ArrayList<>();
        for(var target:rows) {
            long student=longObj(target.get("studentId")); var decision=current.get(student);
            if(decision==null || !decision.allowed()) { failed.add(row("studentId",student,"reason",decision!=null && decision.reason()!=null && decision.reason().matches("[A-Z_]{3,100}") ? decision.reason():"OWNER_POLICY_DENIED"));continue; }
            target.put("parentContact",decision.destination());target.put("guardianId",decision.guardianId());target.put("policyEvidence",decision.policyEvidence());allowed.add(target);
        }
        return row("eventId", eventId, "eventTitle", event.get("title"), "targets", allowed, "failed", failed);
    }

    private Map<String, Object> actionRow(UUID id) {
        return jdbc.sql("""
                SELECT id, school_id, module, urgency, confidence, title, reason, impact,
                       current_state, target_state, cta_label, status, source_type, source_id, created_at
                FROM reporting.command_center_actions
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getObject("id", UUID.class),
                        "schoolId", rs.getObject("school_id"),
                        "module", rs.getString("module"),
                        "urgency", rs.getString("urgency"),
                        "confidence", rs.getInt("confidence"),
                        "title", rs.getString("title"),
                        "reason", rs.getString("reason"),
                        "impact", rs.getString("impact"),
                        "currentState", rs.getString("current_state"),
                        "targetState", rs.getString("target_state"),
                        "ctaLabel", rs.getString("cta_label"),
                        "status", rs.getString("status"),
                        "sourceType", rs.getString("source_type"),
                        "sourceId", rs.getString("source_id"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }

    private Map<String, Object> feedRow(UUID id) {
        return jdbc.sql("""
                SELECT id, school_id, module, event_type, title, message, severity,
                       entity_type, entity_id, created_at
                FROM reporting.command_center_feed
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getObject("id", UUID.class),
                        "schoolId", rs.getObject("school_id"),
                        "module", rs.getString("module"),
                        "eventType", rs.getString("event_type"),
                        "title", rs.getString("title"),
                        "message", rs.getString("message"),
                        "severity", rs.getString("severity"),
                        "entityType", rs.getString("entity_type"),
                        "entityId", rs.getString("entity_id"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }

    private void requireActionAccess(UUID id, Long actorSchoolId, boolean superAdmin) {
        Map<String, Object> action = jdbc.sql("SELECT school_id FROM reporting.command_center_actions WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> row("schoolId", rs.getObject("school_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Action not found"));
        Long schoolId = longObj(action.get("schoolId"));
        if (!superAdmin && schoolId != null && actorSchoolId != null && !schoolId.equals(actorSchoolId)) {
            throw new IllegalArgumentException("Access denied to this action");
        }
    }

    private Long longObj(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private List<Long> longList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            return list.stream().map(item -> {
                if (item instanceof Number number) return number.longValue();
                return Long.parseLong(String.valueOf(item));
            }).toList();
        }
        return List.of(longObj(value));
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    public record ProjectedFeedCommand(
            Long schoolId,
            String module,
            String eventType,
            String title,
            String message,
            String severity,
            String entityType,
            String entityId,
            Long actorUserId,
            OffsetDateTime createdAt,
            String sourceType,
            String sourceId
    ) {
    }
}
