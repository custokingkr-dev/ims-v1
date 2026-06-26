package com.custoking.ims.notificationservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationBroadcastCommandRepository {

    private final JdbcClient jdbc;

    public NotificationBroadcastCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO notification.notification_broadcasts (
                    id, school_id, module, title, message, audience_type, channels,
                    status, scheduled_at, created_by, created_at, updated_at
                ) VALUES (
                    :id, :schoolId, :module, :title, :message, :audienceType, :channels,
                    'DRAFT', :scheduledAt, :createdBy, :createdAt, :updatedAt
                )
                """)
                .param("id", id)
                .param("schoolId", longObj(request.get("schoolId")))
                .param("module", str(request.get("module"), null))
                .param("title", required(request.get("title"), "title"))
                .param("message", required(request.get("message"), "message"))
                .param("audienceType", str(request.get("audienceType"), "ALL"))
                .param("channels", channels(request.get("channels")))
                .param("scheduledAt", offsetDateTime(request.get("scheduledAt")))
                .param("createdBy", longObj(request.get("createdBy")))
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        return row(id);
    }

    @Transactional
    public Map<String, Object> approve(UUID id, Long actorId) {
        requireBroadcast(id);
        jdbc.sql("""
                UPDATE notification.notification_broadcasts
                SET status = 'SCHEDULED', approved_by = :actorId,
                    approved_at = :now, updated_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.now())
                .update();
        return row(id);
    }

    @Transactional
    public Map<String, Object> send(UUID id, Long actorId) {
        requireBroadcast(id);
        jdbc.sql("""
                UPDATE notification.notification_broadcasts
                SET status = 'SENT', sent_by = :actorId,
                    sent_at = :now, updated_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.now())
                .update();
        return row(id);
    }

    public Map<String, Object> deliveryStatus(UUID id) {
        requireBroadcast(id);
        List<Map<String, Object>> logs = jdbc.sql("""
                SELECT channel, status
                FROM notification.notification_delivery_logs
                WHERE broadcast_id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "channel", rs.getString("channel"),
                        "status", rs.getString("status")))
                .list();
        long delivered = logs.stream()
                .filter(row -> "DELIVERED".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .count();
        long failed = logs.stream()
                .filter(row -> "FAILED".equalsIgnoreCase(String.valueOf(row.get("status"))))
                .count();
        long pending = logs.size() - delivered - failed;
        List<String> channels = logs.stream()
                .map(row -> row.get("channel"))
                .filter(channel -> channel != null && !String.valueOf(channel).isBlank())
                .map(String::valueOf)
                .distinct()
                .toList();
        return row(
                "broadcastId", id,
                "total", logs.size(),
                "delivered", delivered,
                "failed", failed,
                "pending", pending,
                "channels", channels);
    }

    public List<Map<String, Object>> list(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module, title, message, audience_type, channels,
                       status, scheduled_at, sent_at, created_at
                FROM notification.notification_broadcasts
                WHERE 1=1
                """);
        if (schoolId != null) {
            sql.append(" AND school_id = :schoolId");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND upper(status) = upper(:status)");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) {
            spec = spec.param("schoolId", schoolId);
        }
        if (status != null && !status.isBlank()) {
            spec = spec.param("status", status);
        }
        return spec.query((rs, rowNum) -> row(
                "id", rs.getObject("id", UUID.class),
                "schoolId", rs.getObject("school_id"),
                "module", rs.getString("module"),
                "title", rs.getString("title"),
                "message", rs.getString("message"),
                "audienceType", rs.getString("audience_type"),
                "channels", splitChannels(rs.getString("channels")),
                "status", rs.getString("status"),
                "scheduledAt", rs.getObject("scheduled_at", OffsetDateTime.class),
                "sentAt", rs.getObject("sent_at", OffsetDateTime.class),
                "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.sql("""
                SELECT id, school_id, module, title, message, audience_type, channels,
                       status, scheduled_at, sent_at, created_at
                FROM notification.notification_broadcasts
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getObject("id", UUID.class),
                        "schoolId", rs.getObject("school_id"),
                        "module", rs.getString("module"),
                        "title", rs.getString("title"),
                        "message", rs.getString("message"),
                        "audienceType", rs.getString("audience_type"),
                        "channels", splitChannels(rs.getString("channels")),
                        "status", rs.getString("status"),
                        "scheduledAt", rs.getObject("scheduled_at", OffsetDateTime.class),
                        "sentAt", rs.getObject("sent_at", OffsetDateTime.class),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .single();
    }

    private void requireBroadcast(UUID id) {
        long count = jdbc.sql("SELECT count(*) FROM notification.notification_broadcasts WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new IllegalArgumentException("Broadcast not found");
        }
    }

    @SuppressWarnings("unchecked")
    private String channels(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "SMS" : String.join(",", list.stream().map(String::valueOf).toList());
        }
        return str(value, "SMS");
    }

    private List<String> splitChannels(String channels) {
        if (channels == null || channels.isBlank()) {
            return List.of();
        }
        return Arrays.asList(channels.split(","));
    }

    private OffsetDateTime offsetDateTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(String.valueOf(value));
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

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
