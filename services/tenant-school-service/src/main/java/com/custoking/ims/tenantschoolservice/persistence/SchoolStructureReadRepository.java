package com.custoking.ims.tenantschoolservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class SchoolStructureReadRepository {

    private final JdbcClient jdbc;

    public SchoolStructureReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SchoolClassRow> classes() {
        return jdbc.sql("""
                        SELECT id, name, sort_order
                        FROM tenant_school.school_classes
                        ORDER BY sort_order, name
                        """)
                .query(SchoolClassRow.class)
                .list();
    }

    public List<SchoolSectionRow> sections(Long schoolId, String classId, Boolean active) {
        StringBuilder sql = new StringBuilder("""
                SELECT ss.id, ss.name, ss.teacher_name, ss.active, ss.school_class_id,
                       sc.name AS class_name, sc.sort_order, ss.school_id, s.name AS school_name
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                LEFT JOIN tenant_school.schools s ON s.id = ss.school_id
                WHERE 1 = 1
                """);
        if (schoolId != null) sql.append(" AND ss.school_id = :schoolId");
        if (classId != null && !classId.isBlank()) sql.append(" AND ss.school_class_id = :classId");
        if (active != null) sql.append(" AND ss.active = :active");
        sql.append(" ORDER BY sc.sort_order, ss.name");

        var spec = jdbc.sql(sql.toString());
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (classId != null && !classId.isBlank()) spec = spec.param("classId", classId);
        if (active != null) spec = spec.param("active", active);
        return spec.query(SchoolSectionRow.class).list();
    }

    public List<AcademicYearRow> academicYears(Boolean active) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, label, active
                FROM tenant_school.academic_years
                WHERE 1 = 1
                """);
        if (active != null) sql.append(" AND active = :active");
        sql.append(" ORDER BY label DESC, id DESC");

        var spec = jdbc.sql(sql.toString());
        if (active != null) spec = spec.param("active", active);
        return spec.query(AcademicYearRow.class).list();
    }

    public List<ZoneSchoolRow> zoneSchools(Long zoneId, Boolean active) {
        StringBuilder sql = new StringBuilder("""
                SELECT zsm.id, zsm.zone_id, z.name AS zone_name, z.code AS zone_code,
                       zsm.school_id, s.name AS school_name, s.short_code AS school_short_code,
                       s.city AS school_city, s.state AS school_state,
                       zsm.active, zsm.added_at, zsm.added_by
                FROM tenant_school.zone_school_mappings zsm
                JOIN tenant_school.zones z ON z.id = zsm.zone_id
                JOIN tenant_school.schools s ON s.id = zsm.school_id
                WHERE zsm.zone_id = :zoneId
                """);
        if (active != null) sql.append(" AND zsm.active = :active");
        sql.append(" ORDER BY s.name");

        var spec = jdbc.sql(sql.toString()).param("zoneId", zoneId);
        if (active != null) spec = spec.param("active", active);
        return spec.query(ZoneSchoolRow.class).list();
    }

    public List<ZoneAdminRow> zoneAdmins(Long zoneId, Boolean active) {
        StringBuilder sql = new StringBuilder("""
                SELECT zaa.id, zaa.zone_id, z.name AS zone_name, z.code AS zone_code,
                       zaa.user_id, '' AS full_name, '' AS email, zaa.active AS user_active,
                       zaa.active, zaa.assigned_at, zaa.assigned_by
                FROM tenant_school.zone_admin_assignments zaa
                JOIN tenant_school.zones z ON z.id = zaa.zone_id
                WHERE zaa.zone_id = :zoneId
                """);
        if (active != null) sql.append(" AND zaa.active = :active");
        sql.append(" ORDER BY u.full_name, u.email");

        var spec = jdbc.sql(sql.toString()).param("zoneId", zoneId);
        if (active != null) spec = spec.param("active", active);
        return spec.query(ZoneAdminRow.class).list();
    }

    public List<StaffMemberRow> staff(Long schoolId) {
        return jdbc.sql("""
                        SELECT sm.id, sm.name, sm.designation, sm.department, sm.monthly_salary,
                               sm.payroll_status, sm.school_id, s.name AS school_name
                        FROM tenant_school.staff_members sm
                        LEFT JOIN tenant_school.schools s ON s.id = sm.school_id
                        WHERE sm.school_id = :schoolId
                        ORDER BY sm.name
                        """)
                .param("schoolId", schoolId)
                .query(StaffMemberRow.class)
                .list();
    }

    public List<SuperadminSchoolStatsRow> schoolStats() {
        return jdbc.sql("""
                        SELECT s.id, s.name, s.short_code, s.city, s.active, s.created_at,
                               '' AS admin_email,
                               0 AS orders_ytd,
                               0 AS gmv_ytd
                        FROM tenant_school.schools s
                        ORDER BY s.name
                        """)
                .query((rs, rowNum) -> new SuperadminSchoolStatsRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("short_code"),
                        rs.getString("city"),
                        rs.getBoolean("active"),
                        rs.getString("admin_email") == null ? "" : rs.getString("admin_email"),
                        rs.getLong("orders_ytd"),
                        rs.getLong("gmv_ytd"),
                        erpSince(rs.getObject("created_at", OffsetDateTime.class))))
                .list();
    }

    @Transactional
    public Map<String, Object> createSchool(Map<String, Object> request) {
        String name = requiredString(request.get("name"), "name");
        String shortCode = requiredString(request.get("shortCode"), "shortCode").toUpperCase(Locale.ROOT);
        long duplicate = jdbc.sql("SELECT count(*) FROM tenant_school.schools WHERE upper(short_code) = :shortCode")
                .param("shortCode", shortCode)
                .query(Long.class)
                .single();
        if (duplicate > 0) {
            throw new IllegalArgumentException("School short code already exists");
        }
        int classCount = boundedInt(request.get("classCount"), 12, 1, 12);
        int sectionCount = boundedInt(request.get("sectionCount"), 2, 1, 26);
        Long id = jdbc.sql("""
                INSERT INTO tenant_school.schools (
                    name, short_code, city, state, contact_email, contact_phone, active,
                    configured_class_count, configured_section_count, created_at
                ) VALUES (
                    :name, :shortCode, :city, :state, :contactEmail, :contactPhone, true,
                    :classCount, :sectionCount, :createdAt
                )
                RETURNING id
                """)
                .param("name", name)
                .param("shortCode", shortCode)
                .param("city", trimToNull(str(request.get("city"), "")))
                .param("state", trimToNull(str(request.get("state"), "")))
                .param("contactEmail", trimToNull(str(request.get("contactEmail"), "")))
                .param("contactPhone", trimToNull(str(request.get("contactPhone"), "")))
                .param("classCount", classCount)
                .param("sectionCount", sectionCount)
                .param("createdAt", OffsetDateTime.now())
                .query(Long.class)
                .single();
        ensureSchoolSections(id, classCount, sectionCount);
        return schoolDetails(id);
    }

    @Transactional
    public Map<String, Object> updateSchool(Long schoolId, Map<String, Object> request) {
        requireSchool(schoolId);
        jdbc.sql("""
                UPDATE tenant_school.schools
                SET name = COALESCE(:name, name),
                    city = :city,
                    active = COALESCE(:active, active)
                WHERE id = :schoolId
                """)
                .param("schoolId", schoolId)
                .param("name", trimToNull(str(request.get("name"), "")))
                .param("city", request.containsKey("city") ? trimToNull(str(request.get("city"), "")) : currentSchoolCity(schoolId))
                .param("active", request.get("active"))
                .update();
        return schoolDetails(schoolId);
    }

    @Transactional
    public Map<String, Object> addStaff(Long schoolId, Map<String, Object> request) {
        requireSchool(schoolId);
        Long id = jdbc.sql("""
                INSERT INTO tenant_school.staff_members (
                    school_id, name, designation, department, monthly_salary, payroll_status
                ) VALUES (
                    :schoolId, :name, :designation, :department, :monthlySalary, :payrollStatus
                )
                RETURNING id
                """)
                .param("schoolId", schoolId)
                .param("name", str(request.get("name"), ""))
                .param("designation", str(request.get("designation"), ""))
                .param("department", str(request.get("department"), ""))
                .param("monthlySalary", longNum(request.get("monthlySalary"), 0))
                .param("payrollStatus", str(request.get("payrollStatus"), "Pending"))
                .query(Long.class)
                .single();
        return staffRow(id);
    }

    @Transactional
    public Map<String, Object> addTimetableEntry(Long schoolId, Map<String, Object> request) {
        requireSchool(schoolId);
        Long id = jdbc.sql("""
                INSERT INTO tenant_school.school_timetable_entries (
                    school_id, day_name, period_label, class_section, subject, teacher
                ) VALUES (
                    :schoolId, :day, :period, :classSection, :subject, :teacher
                )
                RETURNING id
                """)
                .param("schoolId", schoolId)
                .param("day", requiredString(request.get("day"), "day"))
                .param("period", requiredString(request.get("period"), "period"))
                .param("classSection", requiredString(request.get("classSection"), "classSection"))
                .param("subject", trimToNull(str(request.get("subject"), "")))
                .param("teacher", trimToNull(str(request.get("teacher"), "")))
                .query(Long.class)
                .single();
        return timetableRow(id);
    }

    private void ensureSchoolSections(Long schoolId, int classCount, int sectionCount) {
        List<SchoolClassRow> selectedClasses = jdbc.sql("""
                        SELECT id, name, sort_order
                        FROM tenant_school.school_classes
                        ORDER BY sort_order, name
                        LIMIT :limit
                        """)
                .param("limit", classCount)
                .query(SchoolClassRow.class)
                .list();
        for (SchoolClassRow schoolClass : selectedClasses) {
            for (int idx = 0; idx < sectionCount; idx++) {
                String sectionName = String.valueOf((char) ('A' + idx));
                String sectionId = schoolId + "-" + schoolClass.id() + "-" + sectionName;
                jdbc.sql("""
                        INSERT INTO tenant_school.school_sections (
                            id, school_id, school_class_id, name, teacher_name, active
                        ) VALUES (
                            :id, :schoolId, :classId, :name, '', true
                        )
                        ON CONFLICT (id) DO NOTHING
                        """)
                        .param("id", sectionId)
                        .param("schoolId", schoolId)
                        .param("classId", schoolClass.id())
                        .param("name", sectionName)
                        .update();
            }
        }
    }

    private Map<String, Object> schoolDetails(Long schoolId) {
        return jdbc.sql("""
                SELECT id, name, short_code, city, state, active,
                       configured_class_count, configured_section_count
                FROM tenant_school.schools
                WHERE id = :schoolId
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "id", rs.getLong("id"),
                        "name", rs.getString("name"),
                        "shortCode", rs.getString("short_code"),
                        "city", rs.getString("city") == null ? "" : rs.getString("city"),
                        "state", rs.getString("state") == null ? "" : rs.getString("state"),
                        "active", rs.getBoolean("active"),
                        "configuredClassCount", rs.getObject("configured_class_count"),
                        "configuredSectionCount", rs.getObject("configured_section_count")))
                .single();
    }

    private Map<String, Object> staffRow(Long id) {
        return jdbc.sql("""
                SELECT id, name, designation, department, monthly_salary, payroll_status
                FROM tenant_school.staff_members
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getLong("id"),
                        "name", rs.getString("name"),
                        "designation", rs.getString("designation"),
                        "department", rs.getString("department"),
                        "monthlySalary", rs.getLong("monthly_salary"),
                        "payrollStatus", rs.getString("payroll_status")))
                .single();
    }

    private Map<String, Object> timetableRow(Long id) {
        return jdbc.sql("""
                SELECT id, day_name, period_label, class_section, subject, teacher
                FROM tenant_school.school_timetable_entries
                WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", String.valueOf(rs.getLong("id")),
                        "day", rs.getString("day_name"),
                        "period", rs.getString("period_label"),
                        "classSection", rs.getString("class_section"),
                        "subject", rs.getString("subject") == null ? "" : rs.getString("subject"),
                        "teacher", rs.getString("teacher") == null ? "" : rs.getString("teacher")))
                .single();
    }

    private void requireSchool(Long schoolId) {
        if (schoolId == null || jdbc.sql("SELECT count(*) FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId).query(Long.class).single() == 0) {
            throw new IllegalArgumentException("School not found");
        }
    }

    private String currentSchoolCity(Long schoolId) {
        return jdbc.sql("SELECT city FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId).query(String.class).optional().orElse(null);
    }

    private String erpSince(OffsetDateTime createdAt) {
        if (createdAt == null) {
            return "";
        }
        return createdAt.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + createdAt.getYear();
    }

    private String requiredString(Object value, String field) {
        String text = str(value, "").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private int boundedInt(Object value, int fallback, int min, int max) {
        int parsed = (int) longNum(value, fallback);
        return Math.max(min, Math.min(max, parsed));
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    public record SchoolClassRow(String id, String name, Integer sortOrder) {}

    public record SchoolSectionRow(
            String id,
            String name,
            String teacherName,
            Boolean active,
            String schoolClassId,
            String className,
            Integer sortOrder,
            Long schoolId,
            String schoolName) {}

    public record AcademicYearRow(String id, String label, Boolean active) {}

    public record ZoneSchoolRow(
            Long id,
            Long zoneId,
            String zoneName,
            String zoneCode,
            Long schoolId,
            String schoolName,
            String schoolShortCode,
            String schoolCity,
            String schoolState,
            Boolean active,
            OffsetDateTime addedAt,
            Long addedBy) {}

    public record ZoneAdminRow(
            Long id,
            Long zoneId,
            String zoneName,
            String zoneCode,
            Long userId,
            String fullName,
            String email,
            Boolean userActive,
            Boolean active,
            OffsetDateTime assignedAt,
            Long assignedBy) {}

    public record StaffMemberRow(
            Long id,
            String name,
            String designation,
            String department,
            Long monthlySalary,
            String payrollStatus,
            Long schoolId,
            String schoolName) {}

    public record SuperadminSchoolStatsRow(
            Long id,
            String name,
            String shortCode,
            String city,
            Boolean active,
            String adminEmail,
            Long ordersYTD,
            Long gmvYTD,
            String erpSince) {}
}
