package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns review-item invalidation and its reporting event so every write path applies
 * the same reset semantics and emits the projection update in the caller's transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class StudentReviewInvalidationService {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public StudentReviewInvalidationService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public void invalidateProfile(Long studentId) {
        List<OutboxWriter.Event> events = jdbc.sql("""
                        UPDATE student.student_review_items i
                        SET verified_full_name = false,
                            verified_admission_no = false,
                            verified_class_section = false,
                            verified_roll_no = false,
                            verified_father_name = false,
                            verified_father_contact = false,
                            verified_address = false,
                            verified_blood_group = false,
                            current_full_name = s.full_name,
                            status = 'PENDING',
                            correction_requested = false,
                            correction_notes = NULL,
                            suggested_full_name = NULL,
                            completed_at = NULL,
                            updated_at = now()
                        FROM student.student_review_campaigns c,
                             student.students s
                        WHERE i.campaign_id = c.id
                          AND i.student_id = s.id
                          AND i.student_id = :studentId
                          AND c.review_type = 'PROFILE_VERIFICATION'
                          AND c.status = 'ACTIVE'
                        RETURNING i.id, i.school_id, i.campaign_id, i.status
                        """)
                .param("studentId", studentId)
                .query((rs, rowNum) -> event(
                        rs.getString("id"),
                        rs.getLong("school_id"),
                        rs.getString("campaign_id"),
                        rs.getString("status")))
                .list();
        outbox.appendAll(events);
    }

    public void invalidatePhoto(Long studentId) {
        List<OutboxWriter.Event> events = jdbc.sql("""
                        UPDATE student.student_review_items i
                        SET verified_photo = false,
                            status = 'PENDING',
                            correction_requested = false,
                            correction_notes = NULL,
                            suggested_full_name = NULL,
                            completed_at = NULL,
                            updated_at = now()
                        FROM student.student_review_campaigns c
                        WHERE i.campaign_id = c.id
                          AND i.student_id = :studentId
                          AND c.review_type = 'PHOTO_VERIFICATION'
                          AND c.status = 'ACTIVE'
                        RETURNING i.id, i.school_id, i.campaign_id, i.status
                        """)
                .param("studentId", studentId)
                .query((rs, rowNum) -> event(
                        rs.getString("id"),
                        rs.getLong("school_id"),
                        rs.getString("campaign_id"),
                        rs.getString("status")))
                .list();
        outbox.appendAll(events);
    }

    /** Emits the reporting projection event after another review-item mutation. */
    public void emitUpserted(String itemId) {
        Optional<OutboxWriter.Event> event = jdbc.sql("""
                        SELECT id, school_id, campaign_id, status
                        FROM student.student_review_items
                        WHERE id = :id
                        """)
                .param("id", itemId)
                .query((rs, rowNum) -> event(
                        rs.getString("id"),
                        rs.getLong("school_id"),
                        rs.getString("campaign_id"),
                        rs.getString("status")))
                .optional();
        event.ifPresent(outboxEvent -> outbox.appendAll(List.of(outboxEvent)));
    }

    private static OutboxWriter.Event event(String itemId, Long schoolId, String campaignId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", itemId);
        payload.put("schoolId", schoolId);
        payload.put("campaignId", campaignId);
        payload.put("status", status);
        return new OutboxWriter.Event(
                "student-review-item.upserted.v1",
                "StudentReviewItemUpserted:" + itemId,
                "StudentReviewItem",
                itemId,
                schoolId,
                payload);
    }
}
