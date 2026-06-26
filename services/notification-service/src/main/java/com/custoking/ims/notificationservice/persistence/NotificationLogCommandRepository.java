package com.custoking.ims.notificationservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationLogCommandRepository {

    private final JdbcClient jdbc;

    public NotificationLogCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createRequestLog(Map<String, Object> request) {
        String id = str(request.get("id"), UUID.randomUUID().toString());
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO notification.notification_logs (
                    id, school_id, student_id, parent_contact, channel, notification_type,
                    message, status, sent_by, sent_at, created_at, updated_at
                ) VALUES (
                    :id, :schoolId, :studentId, :parentContact, :channel, :notificationType,
                    :message, :status, :sentBy, :sentAt, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("schoolId", longObj(request.get("schoolId")))
                .param("studentId", longObj(request.get("studentId")))
                .param("parentContact", str(request.get("parentContact"), null))
                .param("channel", required(request.get("channel"), "channel"))
                .param("notificationType", required(request.get("notificationType"), "notificationType"))
                .param("message", str(request.get("message"), null))
                .param("status", str(request.get("status"), "QUEUED"))
                .param("sentBy", longObj(request.get("sentBy")))
                .param("sentAt", now)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return row(id);
    }

    private Map<String, Object> row(String id) {
        return jdbc.sql("""
                SELECT id, school_id, student_id, parent_contact, channel, notification_type,
                       message, status, sent_by, sent_at, created_at, updated_at
                FROM notification.notification_logs
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("schoolId", rs.getObject("school_id"));
                    row.put("studentId", rs.getObject("student_id"));
                    row.put("parentContact", rs.getString("parent_contact"));
                    row.put("channel", rs.getString("channel"));
                    row.put("notificationType", rs.getString("notification_type"));
                    row.put("message", rs.getString("message"));
                    row.put("status", rs.getString("status"));
                    row.put("sentBy", rs.getObject("sent_by"));
                    row.put("sentAt", rs.getObject("sent_at", OffsetDateTime.class));
                    row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
                    row.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
                    return row;
                })
                .single();
    }

    private Long longObj(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String required(Object value, String field) {
        String text = str(value, "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
