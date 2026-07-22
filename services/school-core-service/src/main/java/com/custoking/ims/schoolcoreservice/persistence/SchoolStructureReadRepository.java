package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class SchoolStructureReadRepository {
    public static final int DEFAULT_CONFIGURED_CLASS_COUNT = 15;
    public static final int MAX_CONFIGURED_CLASS_COUNT = 15;
    public static final int MAX_CONFIGURED_SECTION_COUNT = 26;
    private static final Pattern ACADEMIC_YEAR_PATTERN =
            Pattern.compile("(?:^|[^0-9])(\\d{4})[_\\-/ ](\\d{2}|\\d{4})(?:$|[^0-9])");

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public SchoolStructureReadRepository(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public List<SchoolClassRow> classes(Long schoolId) {
        if (schoolId == null) {
            return jdbc.sql("""
                            SELECT id, name, sort_order
                            FROM tenant_school.school_classes
                            ORDER BY sort_order, name
                            """)
                    .query(SchoolClassRow.class)
                    .list();
        }
        Integer count = jdbc.sql(
                        "SELECT configured_class_count FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        int limit = (count == null) ? Integer.MAX_VALUE : count;
        // A school's classes = the first N configured classes UNION any class that
        // actually has students (onboarding/import can place students in classes
        // beyond the configured count; those must never be hidden from pickers).
        return jdbc.sql("""
                        SELECT id, name, sort_order
                        FROM tenant_school.school_classes sc
                        WHERE sc.id IN (
                            SELECT id FROM tenant_school.school_classes
                            ORDER BY sort_order, name
                            LIMIT :limit
                        )
                        OR sc.id IN (
                            SELECT DISTINCT class_id
                            FROM student.students
                            WHERE school_id = :schoolId AND deleted_at IS NULL
                        )
                        ORDER BY sc.sort_order, sc.name
                        """)
                .param("limit", limit)
                .param("schoolId", schoolId)
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
        return academicYears(null, active);
    }

    public List<AcademicYearRow> academicYears(Long schoolId, Boolean active) {
        if (schoolId != null) {
            AcademicCalendar.AcademicYear current = AcademicCalendar.currentAcademicYear(jdbc, schoolId);
            String currentKey = academicYearKey(current.id(), current.label());
            Map<String, AcademicYearRow> byYear = new LinkedHashMap<>();
            for (AcademicYearRow row : queryAcademicYears(null)) {
                String key = academicYearKey(row.id(), row.label());
                if (key.equals(currentKey)) {
                    byYear.put(key, new AcademicYearRow(current.id(), current.label(), true));
                } else {
                    byYear.putIfAbsent(key, new AcademicYearRow(row.id(), row.label(), false));
                }
            }
            byYear.putIfAbsent(currentKey, new AcademicYearRow(current.id(), current.label(), true));
            List<AcademicYearRow> rows = new ArrayList<>(byYear.values());
            rows.sort(Comparator
                    .comparingInt((AcademicYearRow row) -> academicYearStart(row.id(), row.label())).reversed()
                    .thenComparing(AcademicYearRow::label, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(AcademicYearRow::id, Comparator.reverseOrder()));
            if (active != null) {
                return rows.stream()
                        .filter(row -> active.equals(Boolean.TRUE.equals(row.active())))
                        .toList();
            }
            return rows;
        }
        return queryAcademicYears(active);
    }

    private List<AcademicYearRow> queryAcademicYears(Boolean active) {
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

    private String academicYearKey(String id, String label) {
        Matcher matcher = ACADEMIC_YEAR_PATTERN.matcher((id == null ? "" : id) + " " + (label == null ? "" : label));
        if (matcher.find()) {
            return matcher.group(1) + "-" + endYearSuffix(matcher.group(2));
        }
        String fallback = trimToNull(label);
        return fallback == null ? str(id, "").trim().toLowerCase(Locale.ROOT) : fallback.toLowerCase(Locale.ROOT);
    }

    private int academicYearStart(String id, String label) {
        Matcher matcher = ACADEMIC_YEAR_PATTERN.matcher((id == null ? "" : id) + " " + (label == null ? "" : label));
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return Integer.MIN_VALUE;
    }

    private String endYearSuffix(String value) {
        return value.length() == 2 ? value : value.substring(value.length() - 2);
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
                        SELECT s.id, s.school_uid, s.name, s.short_code, s.city, s.active, s.created_at,
                               s.academic_year_start_month, s.financial_year_start_month,
                               '' AS admin_email,
                               0 AS orders_ytd,
                               0 AS gmv_ytd
                        FROM tenant_school.schools s
                        ORDER BY s.name
                        """)
                .query((rs, rowNum) -> new SuperadminSchoolStatsRow(
                        rs.getLong("id"),
                        rs.getObject("school_uid", UUID.class),
                        rs.getString("name"),
                        rs.getString("short_code"),
                        rs.getString("city"),
                        rs.getBoolean("active"),
                        rs.getString("admin_email") == null ? "" : rs.getString("admin_email"),
                        rs.getLong("orders_ytd"),
                        rs.getLong("gmv_ytd"),
                        rs.getObject("academic_year_start_month", Integer.class),
                        rs.getObject("financial_year_start_month", Integer.class),
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
        int classCount = boundedInt(
                request.get("classCount"),
                DEFAULT_CONFIGURED_CLASS_COUNT,
                1,
                MAX_CONFIGURED_CLASS_COUNT);
        int sectionCount = boundedInt(request.get("sectionCount"), 2, 1, MAX_CONFIGURED_SECTION_COUNT);
        int academicYearStartMonth = boundedInt(
                request.get("academicYearStartMonth"), AcademicCalendar.DEFAULT_ACADEMIC_YEAR_START_MONTH, 1, 12);
        int financialYearStartMonth = boundedInt(
                request.get("financialYearStartMonth"), AcademicCalendar.DEFAULT_FINANCIAL_YEAR_START_MONTH, 1, 12);
        Long id = jdbc.sql("""
                INSERT INTO tenant_school.schools (
                    name, short_code, city, state, contact_email, contact_phone, active,
                    configured_class_count, configured_section_count, academic_year_start_month,
                    financial_year_start_month, created_at
                ) VALUES (
                    :name, :shortCode, :city, :state, :contactEmail, :contactPhone, true,
                    :classCount, :sectionCount, :academicYearStartMonth, :financialYearStartMonth, :createdAt
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
                .param("academicYearStartMonth", academicYearStartMonth)
                .param("financialYearStartMonth", financialYearStartMonth)
                .param("createdAt", OffsetDateTime.now())
                .query(Long.class)
                .single();
        ensureSchoolSections(id, classCount, sectionCount);
        Map<String, Object> details = schoolDetails(id);
        emitSchoolUpserted(details);
        return details;
    }

    @Transactional
    public Map<String, Object> updateSchool(Long schoolId, Map<String, Object> request) {
        requireSchool(schoolId);
        jdbc.sql("""
                UPDATE tenant_school.schools
                SET name = COALESCE(:name, name),
                    city = :city,
                    academic_year_start_month = COALESCE(:academicYearStartMonth, academic_year_start_month),
                    financial_year_start_month = COALESCE(:financialYearStartMonth, financial_year_start_month),
                    active = COALESCE(:active, active)
                WHERE id = :schoolId
                """)
                .param("schoolId", schoolId)
                .param("name", trimToNull(str(request.get("name"), "")))
                .param("city", request.containsKey("city") ? trimToNull(str(request.get("city"), "")) : currentSchoolCity(schoolId))
                .param("academicYearStartMonth", request.containsKey("academicYearStartMonth")
                        ? boundedInt(request.get("academicYearStartMonth"), AcademicCalendar.DEFAULT_ACADEMIC_YEAR_START_MONTH, 1, 12)
                        : null)
                .param("financialYearStartMonth", request.containsKey("financialYearStartMonth")
                        ? boundedInt(request.get("financialYearStartMonth"), AcademicCalendar.DEFAULT_FINANCIAL_YEAR_START_MONTH, 1, 12)
                        : null)
                .param("active", request.get("active"))
                .update();
        Map<String, Object> details = schoolDetails(schoolId);
        emitSchoolUpserted(details);
        return details;
    }

    private void emitSchoolUpserted(Map<String, Object> details) {
        Long id = ((Number) details.get("id")).longValue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", details.get("id"));
        payload.put("schoolUid", details.get("schoolUid"));
        payload.put("name", details.get("name"));
        payload.put("shortCode", details.get("shortCode"));
        payload.put("city", details.get("city"));
        payload.put("state", details.get("state"));
        payload.put("active", details.get("active"));
        payload.put("academicYearStartMonth", details.get("academicYearStartMonth"));
        payload.put("financialYearStartMonth", details.get("financialYearStartMonth"));
        outbox.append("school.upserted.v1", "SchoolUpserted:" + id, "School", String.valueOf(id), id, payload);
    }

    @Transactional
    public Map<String, Object> updateStructure(Long schoolId, int classCount, int sectionCount) {
        requireSchool(schoolId);

        // Compute the target in-range class ids (first N) and active letters up front,
        // so the occupancy guard mirrors exactly what the apply step will deactivate.
        List<String> inRangeClassIds = jdbc.sql("""
                        SELECT id FROM tenant_school.school_classes
                        ORDER BY sort_order, name
                        LIMIT :classCount
                        """)
                .param("classCount", classCount)
                .query(String.class)
                .list();
        List<String> activeLetters = SchoolStructureDelta.activeLetters(sectionCount);

        // Block if ANY student sits in a section this edit would deactivate: an
        // out-of-range class, or a section name not among the active letters
        // (including non-A..Z names created via import). Mirrors the apply UPDATE below.
        var offender = jdbc.sql("""
                        SELECT sc.name AS class_name, ss.name AS section_name, count(*) AS n
                        FROM student.students st
                        JOIN tenant_school.school_sections ss ON ss.id = st.section_id
                        JOIN tenant_school.school_classes sc ON sc.id = st.class_id
                        WHERE st.school_id = :schoolId AND st.deleted_at IS NULL
                          AND NOT (ss.school_class_id IN (:classIds) AND ss.name IN (:letters))
                        GROUP BY sc.name, ss.name, sc.sort_order
                        ORDER BY sc.sort_order, ss.name
                        LIMIT 1
                        """)
                .param("schoolId", schoolId)
                .param("classIds", inRangeClassIds)
                .param("letters", activeLetters)
                .query((rs, n) -> new Object[] {rs.getString("class_name"), rs.getString("section_name"), rs.getLong("n")})
                .optional();
        if (offender.isPresent()) {
            Object[] o = offender.get();
            throw new StructureInUseException(
                    "Cannot reduce structure: class '" + o[0] + "' section '" + o[1] + "' has " + o[2] + " student(s)");
        }

        // Apply: persist counts, create any missing in-range sections, then set the
        // active flag so only in-range class/section combinations are visible.
        jdbc.sql("""
                        UPDATE tenant_school.schools
                        SET configured_class_count = :classCount, configured_section_count = :sectionCount
                        WHERE id = :schoolId
                        """)
                .param("classCount", classCount)
                .param("sectionCount", sectionCount)
                .param("schoolId", schoolId)
                .update();

        ensureSchoolSections(schoolId, classCount, sectionCount);

        jdbc.sql("""
                        UPDATE tenant_school.school_sections
                        SET active = (school_class_id IN (:classIds) AND name IN (:letters))
                        WHERE school_id = :schoolId
                        """)
                .param("classIds", inRangeClassIds)
                .param("letters", activeLetters)
                .param("schoolId", schoolId)
                .update();

        emitSectionsUpserted(schoolId);

        return schoolDetails(schoolId);
    }

    /** Re-reads and re-emits every section of a school after a bulk mutation (grow/shrink toggle). */
    private void emitSectionsUpserted(Long schoolId) {
        jdbc.sql("""
                        SELECT ss.id, ss.name, ss.teacher_name, ss.active, ss.school_class_id, sc.name AS class_name
                        FROM tenant_school.school_sections ss
                        JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                        WHERE ss.school_id = :schoolId
                        """)
                .param("schoolId", schoolId)
                .query((rs, n) -> {
                    emitSectionUpserted(rs.getString("id"), rs.getString("name"), schoolId,
                            rs.getString("school_class_id"), rs.getString("class_name"),
                            rs.getBoolean("active"), rs.getString("teacher_name"));
                    return null;
                })
                .list();
    }

    private void emitSectionUpserted(String sectionId, String name, Long schoolId, String classId,
                                      String className, boolean active, String teacherName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", sectionId);
        payload.put("name", name);
        payload.put("schoolId", schoolId);
        payload.put("classId", classId);
        payload.put("className", className);
        payload.put("active", active);
        payload.put("teacherName", teacherName);
        outbox.append("school-section.upserted.v1", "SchoolSectionUpserted:" + sectionId, "SchoolSection",
                sectionId, schoolId, payload);
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
                emitSectionUpserted(sectionId, sectionName, schoolId, schoolClass.id(), schoolClass.name(), true, "");
            }
        }
    }

    private Map<String, Object> schoolDetails(Long schoolId) {
        return jdbc.sql("""
                SELECT id, school_uid, name, short_code, city, state, active,
                       configured_class_count, configured_section_count,
                       academic_year_start_month, financial_year_start_month
                FROM tenant_school.schools
                WHERE id = :schoolId
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "id", rs.getLong("id"),
                        "schoolUid", rs.getObject("school_uid", UUID.class),
                        "name", rs.getString("name"),
                        "shortCode", rs.getString("short_code"),
                        "city", rs.getString("city") == null ? "" : rs.getString("city"),
                        "state", rs.getString("state") == null ? "" : rs.getString("state"),
                        "active", rs.getBoolean("active"),
                        "configuredClassCount", rs.getObject("configured_class_count"),
                        "configuredSectionCount", rs.getObject("configured_section_count"),
                        "academicYearStartMonth", rs.getObject("academic_year_start_month", Integer.class),
                        "financialYearStartMonth", rs.getObject("financial_year_start_month", Integer.class)))
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
            UUID schoolUid,
            String name,
            String shortCode,
            String city,
            Boolean active,
            String adminEmail,
            Long ordersYTD,
            Long gmvYTD,
            Integer academicYearStartMonth,
            Integer financialYearStartMonth,
            String erpSince) {}
}
