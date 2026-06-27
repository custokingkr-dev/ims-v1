package com.custoking.ims.tenantschoolservice.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Repository
public class ZoneCommandRepository {

    private final JdbcClient jdbc;

    public ZoneCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createZone(Map<String, Object> request) {
        String name = required(request.get("name"), "name");
        String code = required(request.get("code"), "code").toUpperCase(Locale.ROOT);
        try {
            Long id = jdbc.sql("""
                    INSERT INTO tenant_school.zones (name, code, city, state, description, active, created_at, updated_at, created_by)
                    VALUES (:name, :code, :city, :state, :description, true, :now, :now, :createdBy)
                    RETURNING id
                    """)
                    .param("name", name)
                    .param("code", code)
                    .param("city", trimToNull(str(request.get("city"), "")))
                    .param("state", trimToNull(str(request.get("state"), "")))
                    .param("description", trimToNull(str(request.get("description"), "")))
                    .param("now", OffsetDateTime.now())
                    .param("createdBy", longObj(request.get("createdBy")))
                    .query(Long.class)
                    .single();
            return zoneRow(id);
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("Zone code or name already exists", ex);
        }
    }

    @Transactional
    public Map<String, Object> updateZone(Long zoneId, Map<String, Object> request) {
        requireZone(zoneId);
        jdbc.sql("""
                UPDATE tenant_school.zones
                SET name = COALESCE(:name, name),
                    city = :city,
                    state = :state,
                    description = :description,
                    active = COALESCE(:active, active),
                    updated_at = :updatedAt
                WHERE id = :zoneId
                """)
                .param("zoneId", zoneId)
                .param("name", trimToNull(str(request.get("name"), "")))
                .param("city", request.containsKey("city") ? trimToNull(str(request.get("city"), "")) : current(zoneId, "city"))
                .param("state", request.containsKey("state") ? trimToNull(str(request.get("state"), "")) : current(zoneId, "state"))
                .param("description", request.containsKey("description") ? trimToNull(str(request.get("description"), "")) : current(zoneId, "description"))
                .param("active", request.get("active"))
                .param("updatedAt", OffsetDateTime.now())
                .update();
        return zoneRow(zoneId);
    }

    @Transactional
    public void assignSchool(Long zoneId, Long schoolId, Long assignedBy) {
        requireZone(zoneId);
        requireSchool(schoolId);
        jdbc.sql("""
                INSERT INTO tenant_school.zone_school_mappings (zone_id, school_id, active, added_at, added_by)
                VALUES (:zoneId, :schoolId, true, :addedAt, :addedBy)
                ON CONFLICT (zone_id, school_id)
                DO UPDATE SET active = true, added_by = EXCLUDED.added_by
                """)
                .param("zoneId", zoneId)
                .param("schoolId", schoolId)
                .param("addedAt", OffsetDateTime.now())
                .param("addedBy", assignedBy)
                .update();
    }

    @Transactional
    public void removeSchool(Long zoneId, Long schoolId) {
        int updated = jdbc.sql("""
                DELETE FROM tenant_school.zone_school_mappings
                WHERE zone_id = :zoneId AND school_id = :schoolId
                """)
                .param("zoneId", zoneId)
                .param("schoolId", schoolId)
                .update();
        if (updated == 0) {
            throw new IllegalArgumentException("School not assigned to this zone");
        }
    }

    private Map<String, Object> zoneRow(Long zoneId) {
        return jdbc.sql("""
                SELECT z.id, z.name, z.code, z.city, z.state, z.description, z.active,
                       COALESCE(COUNT(zsm.id), 0) AS school_count,
                       '' AS admin_email
                FROM tenant_school.zones z
                LEFT JOIN tenant_school.zone_school_mappings zsm ON zsm.zone_id = z.id AND zsm.active = true
                LEFT JOIN tenant_school.zone_admin_assignments zaa ON zaa.zone_id = z.id AND zaa.active = true
                WHERE z.id = :zoneId
                GROUP BY z.id, z.name, z.code, z.city, z.state, z.description, z.active
                """)
                .param("zoneId", zoneId)
                .query((rs, rowNum) -> row(
                        "id", rs.getLong("id"),
                        "name", rs.getString("name"),
                        "code", rs.getString("code"),
                        "city", rs.getString("city") == null ? "" : rs.getString("city"),
                        "state", rs.getString("state") == null ? "" : rs.getString("state"),
                        "description", rs.getString("description") == null ? "" : rs.getString("description"),
                        "active", rs.getBoolean("active"),
                        "schoolCount", rs.getLong("school_count"),
                        "adminEmail", rs.getString("admin_email") == null ? "" : rs.getString("admin_email")))
                .single();
    }

    private void requireZone(Long zoneId) {
        if (zoneId == null || jdbc.sql("SELECT count(*) FROM tenant_school.zones WHERE id = :id")
                .param("id", zoneId).query(Long.class).single() == 0) {
            throw new IllegalArgumentException("Zone not found");
        }
    }

    private void requireSchool(Long schoolId) {
        if (schoolId == null || jdbc.sql("SELECT count(*) FROM tenant_school.schools WHERE id = :id")
                .param("id", schoolId).query(Long.class).single() == 0) {
            throw new IllegalArgumentException("School not found");
        }
    }

    private String current(Long zoneId, String column) {
        String sql = switch (column) {
            case "city" -> "SELECT city FROM tenant_school.zones WHERE id = :zoneId";
            case "state" -> "SELECT state FROM tenant_school.zones WHERE id = :zoneId";
            case "description" -> "SELECT description FROM tenant_school.zones WHERE id = :zoneId";
            default -> throw new IllegalArgumentException("Unsupported column");
        };
        return jdbc.sql(sql).param("zoneId", zoneId).query(String.class).optional().orElse(null);
    }

    private String required(Object value, String field) {
        String text = str(value, "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private Long longObj(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
