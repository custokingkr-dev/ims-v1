package com.custoking.ims.attendanceservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AttendanceReadRepository {

    private final JdbcClient jdbc;
    private final String dailyTable;
    private final String recordsTable;

    public AttendanceReadRepository(
            JdbcClient jdbc,
            @Value("${attendance.db.schema:public}") String schema) {
        this.jdbc = jdbc;
        this.dailyTable = qualifiedTable(schema, "attendance_daily");
        this.recordsTable = qualifiedTable(schema, "attendance_student_records");
    }

    public List<DailyAttendanceRow> daily(String sectionId, String academicYearId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, attendance_date, total_enrolled, present_count, absent_count,
                       recorded_by, recorded_at, updated_by, updated_at, locked,
                       school_class_id, section_id, academic_year_id
                FROM %s
                WHERE 1=1
                """.formatted(dailyTable));
        if (sectionId != null && !sectionId.isBlank()) sql.append(" AND section_id = :sectionId");
        if (academicYearId != null && !academicYearId.isBlank()) sql.append(" AND academic_year_id = :academicYearId");
        sql.append(" ORDER BY attendance_date DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (sectionId != null && !sectionId.isBlank()) spec = spec.param("sectionId", sectionId);
        if (academicYearId != null && !academicYearId.isBlank()) spec = spec.param("academicYearId", academicYearId);
        return spec.query(DailyAttendanceRow.class).list();
    }

    public List<StudentAttendanceRow> records(Long studentId, Long schoolId, LocalDate date, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, attendance_daily_id, student_id, school_id, attendance_date,
                       academic_year_id, class_id, section_id, status, remarks,
                       recorded_by, recorded_at, updated_by, updated_at
                FROM %s
                WHERE 1=1
                """.formatted(recordsTable));
        if (studentId != null) sql.append(" AND student_id = :studentId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (date != null) sql.append(" AND attendance_date = :date");
        sql.append(" ORDER BY attendance_date DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 1000)));
        if (studentId != null) spec = spec.param("studentId", studentId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (date != null) spec = spec.param("date", date);
        return spec.query(StudentAttendanceRow.class).list();
    }

    public Map<String, Object> dailySummary(LocalDate date, Long schoolId) {
        String academicYearId = currentAcademicYearId();
        List<Map<String, Object>> sections = jdbc.sql("""
                SELECT ss.id, ss.name, ss.teacher_name, ss.school_class_id, sc.name AS class_name,
                       ad.total_enrolled, ad.present_count, ad.absent_count, ad.recorded_at, ad.updated_at, ad.locked
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                LEFT JOIN %s ad
                       ON ad.section_id = ss.id
                      AND ad.attendance_date = :date
                      AND ad.academic_year_id = :academicYearId
                WHERE ss.school_id = :schoolId
                ORDER BY sc.sort_order, ss.name
                """.formatted(dailyTable))
                .param("date", date)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> {
                    Integer totalEnrolled = rs.getObject("total_enrolled", Integer.class);
                    Integer presentCount = rs.getObject("present_count", Integer.class);
                    Integer absentCount = rs.getObject("absent_count", Integer.class);
                    long totalStudents = countStudents(rs.getString("id"));
                    boolean emptySection = totalStudents == 0;
                    boolean sectionLocked = emptySection || Boolean.TRUE.equals(rs.getObject("locked", Boolean.class));
                    String status = emptySection
                            ? "Submitted"
                            : presentCount == null ? "Pending" : sectionLocked ? "Submitted" : "Saved";
                    Double presentPercent = null;
                    if (emptySection) {
                        presentPercent = 0.0;
                    } else if (presentCount != null && totalEnrolled != null && totalEnrolled > 0) {
                        presentPercent = round(presentCount * 100.0 / totalEnrolled);
                    }
                    return row("sectionId", rs.getString("id"),
                            "classId", rs.getString("school_class_id"),
                            "sectionName", rs.getString("class_name") + "-" + rs.getString("name"),
                            "totalStudents", totalStudents,
                            "presentPercent", presentPercent,
                            "presentCount", presentCount == null ? 0 : presentCount,
                            "absentCount", absentCount == null ? 0 : absentCount,
                            "teacherName", rs.getString("teacher_name"),
                            "status", status,
                            "locked", sectionLocked);
                })
                .list();
        double overall = sections.stream()
                .filter(section -> section.get("presentPercent") != null)
                .mapToDouble(section -> doubleNum(section.get("presentPercent")))
                .average()
                .orElse(0);
        return row("date", date.toString(),
                "dateLabel", date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
                "overallPercent", round(overall),
                "sections", sections,
                "allSubmitted", sections.stream().noneMatch(section ->
                        longNum(section.get("totalStudents"), 0) > 0 && "Pending".equals(section.get("status"))),
                "nonWorkingDay", date.getDayOfWeek() == DayOfWeek.SUNDAY);
    }

    public Map<String, Object> sectionRegister(LocalDate date, String classId, String sectionId, Long schoolId) {
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = longNum(section.get("schoolId"), 0);
        if (schoolId != null && !schoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }
        Map<String, Object> daily = dailyRecord(date, sectionId, academicYearId);
        List<Map<String, Object>> students = jdbc.sql("""
                SELECT s.id, s.admission_no, s.roll_no, s.full_name, s.photo_url,
                       ar.status, ar.remarks
                FROM student.students s
                LEFT JOIN %s ar
                       ON ar.student_id = s.id
                      AND ar.attendance_date = :date
                      AND ar.academic_year_id = :academicYearId
                WHERE s.school_id = :schoolId
                  AND s.class_id = :classId
                  AND s.section_id = :sectionId
                  AND s.deleted_at IS NULL
                ORDER BY NULLIF(regexp_replace(COALESCE(s.roll_no, ''), '[^0-9]', '', 'g'), '')::int NULLS LAST,
                         s.roll_no NULLS LAST, s.full_name
                """.formatted(recordsTable))
                .param("date", date)
                .param("academicYearId", academicYearId)
                .param("schoolId", sectionSchoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .query((rs, rowNum) -> row(
                        "studentId", rs.getLong("id"),
                        "admissionNo", rs.getString("admission_no"),
                        "rollNo", rs.getString("roll_no"),
                        "fullName", rs.getString("full_name"),
                        "photoUrl", rs.getString("photo_url"),
                        "status", rs.getString("status"),
                        "remarks", rs.getString("remarks") == null ? "" : rs.getString("remarks")))
                .list();
        int present = students.stream().filter(student -> "PRESENT".equals(student.get("status"))).toList().size();
        int absent = students.stream().filter(student -> "ABSENT".equals(student.get("status"))).toList().size();
        int total = students.size();
        return row("date", date.toString(),
                "classId", classId,
                "sectionId", sectionId,
                "sectionName", section.get("className") + "-" + section.get("name"),
                "locked", daily != null && Boolean.TRUE.equals(daily.get("locked")),
                "totalStudents", total,
                "presentCount", present,
                "absentCount", absent,
                "presentPercent", round(total == 0 ? 0 : present * 100.0 / total),
                "students", students);
    }

    public Map<String, Object> sectionInfo(LocalDate date, String sectionId, Long schoolId) {
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = jdbc.sql("""
                SELECT id, teacher_name, school_id
                FROM tenant_school.school_sections
                WHERE id = :sectionId
                """)
                .param("sectionId", sectionId)
                .query((rs, rowNum) -> row("id", rs.getString("id"),
                        "teacherName", rs.getString("teacher_name"),
                        "schoolId", rs.getLong("school_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Section not found"));
        Long sectionSchoolId = longNum(section.get("schoolId"), 0);
        if (schoolId != null && sectionSchoolId != null && !schoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }
        Map<String, Object> record = jdbc.sql("""
                SELECT present_count, recorded_at, updated_at
                FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                """.formatted(dailyTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> row("presentCount", rs.getInt("present_count"),
                        "savedAt", (rs.getObject("updated_at", OffsetDateTime.class) == null
                                ? rs.getObject("recorded_at", OffsetDateTime.class)
                                : rs.getObject("updated_at", OffsetDateTime.class)).toString()))
                .optional()
                .orElse(null);
        return row("totalEnrolled", countStudents(sectionId),
                "teacherName", section.get("teacherName"),
                "existingRecord", record);
    }

    @Transactional
    public Map<String, Object> saveDailyAttendance(Map<String, Object> request) {
        LocalDate date = LocalDate.parse(str(request.get("date"), LocalDate.now().toString()));
        String classId = requireText(request.get("classId"), "Class not found");
        String sectionId = requireText(request.get("sectionId"), "Section not found");
        String academicYearId = currentAcademicYearId();
        Map<String, Object> schoolClass = classRecord(classId);
        Map<String, Object> section = sectionRecord(sectionId);
        Long requestedSchoolId = request.containsKey("schoolId") ? longNum(request.get("schoolId"), 0) : null;
        if (requestedSchoolId != null && !requestedSchoolId.equals(longNum(section.get("schoolId"), 0))) {
            throw new SecurityException("You do not have access to this section");
        }
        int total = (int) longNum(request.get("totalEnrolled"), countStudents(sectionId));
        int present = (int) longNum(request.get("presentCount"), 0);
        if (present < 0 || present > total) {
            throw new IllegalArgumentException("Present count is invalid");
        }
        Long actorId = request.containsKey("actorId") ? longNum(request.get("actorId"), 0) : null;
        OffsetDateTime now = OffsetDateTime.now();
        String id = jdbc.sql("""
                SELECT id FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                """.formatted(dailyTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query(String.class)
                .optional()
                .orElse(UUID.randomUUID().toString());
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM " + dailyTable + " WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single() > 0;
        if (exists) {
            jdbc.sql("""
                    UPDATE %s
                    SET total_enrolled = :total, present_count = :present, absent_count = :absent,
                        updated_by = :actorId, updated_at = :updatedAt
                    WHERE id = :id
                    """.formatted(dailyTable))
                    .param("id", id)
                    .param("total", total)
                    .param("present", present)
                    .param("absent", Math.max(total - present, 0))
                    .param("actorId", actorId)
                    .param("updatedAt", now)
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO %s(id, attendance_date, total_enrolled, present_count, absent_count,
                                                 recorded_by, recorded_at, updated_by, updated_at, locked,
                                                 school_class_id, section_id, academic_year_id)
                    VALUES (:id, :date, :total, :present, :absent, :actorId, :recordedAt, :actorId,
                            :updatedAt, false, :classId, :sectionId, :academicYearId)
                    """.formatted(dailyTable))
                    .param("id", id)
                    .param("date", date)
                    .param("total", total)
                    .param("present", present)
                    .param("absent", Math.max(total - present, 0))
                    .param("actorId", actorId)
                    .param("recordedAt", now)
                    .param("updatedAt", now)
                    .param("classId", classId)
                    .param("sectionId", sectionId)
                    .param("academicYearId", academicYearId)
                    .update();
        }
        return row(
                "ok", true,
                "message", "Saved - " + schoolClass.get("name") + "-" + section.get("name")
                        + " - " + present + "/" + total + " present ("
                        + round(total == 0 ? 0 : present * 100.0 / total) + "%)",
                "attendanceDailyId", id);
    }

    @Transactional
    public Map<String, Object> saveSectionRegister(Map<String, Object> request) {
        LocalDate date = LocalDate.parse(str(request.get("date"), LocalDate.now().toString()));
        String classId = requireText(request.get("classId"), "Class not found");
        String sectionId = requireText(request.get("sectionId"), "Section not found");
        Long actorId = request.containsKey("actorId") ? longNum(request.get("actorId"), 0) : null;
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = longNum(section.get("schoolId"), 0);
        Long requestedSchoolId = request.containsKey("schoolId") ? longNum(request.get("schoolId"), 0) : null;
        if (requestedSchoolId != null && !requestedSchoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }
        Map<String, Object> existingDaily = dailyRecord(date, sectionId, academicYearId);
        if (existingDaily != null && Boolean.TRUE.equals(existingDaily.get("locked"))) {
            throw new IllegalArgumentException("Attendance is locked for this section");
        }
        List<Map<String, Object>> records = records(request.get("records"));
        int total = (int) countStudents(sectionId);
        int submittedPresent = (int) records.stream().filter(row -> "PRESENT".equals(str(row.get("status"), ""))).count();
        String dailyId = upsertDaily(date, classId, sectionId, academicYearId, total, submittedPresent, actorId, false);
        OffsetDateTime now = OffsetDateTime.now();
        for (Map<String, Object> record : records) {
            Long studentId = longNum(record.get("studentId"), 0);
            String status = requireText(record.get("status"), "Status is required");
            if (!"PRESENT".equals(status) && !"ABSENT".equals(status)) {
                throw new IllegalArgumentException("Invalid attendance status");
            }
            ensureStudentInSection(studentId, sectionSchoolId, classId, sectionId);
            jdbc.sql("""
                    INSERT INTO %s(id, attendance_daily_id, student_id, school_id, attendance_date,
                                   academic_year_id, class_id, section_id, status, remarks,
                                   recorded_by, recorded_at, updated_by, updated_at)
                    VALUES (:id, :dailyId, :studentId, :schoolId, :date, :academicYearId, :classId, :sectionId,
                            :status, :remarks, :actorId, :recordedAt, :actorId, :updatedAt)
                    ON CONFLICT (student_id, attendance_date, academic_year_id) DO UPDATE SET
                        attendance_daily_id = EXCLUDED.attendance_daily_id,
                        school_id = EXCLUDED.school_id,
                        class_id = EXCLUDED.class_id,
                        section_id = EXCLUDED.section_id,
                        status = EXCLUDED.status,
                        remarks = EXCLUDED.remarks,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = EXCLUDED.updated_at
                    """.formatted(recordsTable))
                    .param("id", UUID.randomUUID().toString())
                    .param("dailyId", dailyId)
                    .param("studentId", studentId)
                    .param("schoolId", sectionSchoolId)
                    .param("date", date)
                    .param("academicYearId", academicYearId)
                    .param("classId", classId)
                    .param("sectionId", sectionId)
                    .param("status", status)
                    .param("remarks", str(record.get("remarks"), ""))
                    .param("actorId", actorId)
                    .param("recordedAt", now)
                    .param("updatedAt", now)
                    .update();
        }
        int persistedPresent = (int) jdbc.sql("""
                SELECT COUNT(*) FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                  AND status = 'PRESENT'
                """.formatted(recordsTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query(Long.class)
                .single()
                .longValue();
        upsertDaily(date, classId, sectionId, academicYearId, total, persistedPresent, actorId, false);
        return sectionRegister(date, classId, sectionId, sectionSchoolId);
    }

    @Transactional
    public Map<String, Object> submitAttendanceSection(Map<String, Object> request) {
        LocalDate date = LocalDate.parse(str(request.get("date"), LocalDate.now().toString()));
        String classId = requireText(request.get("classId"), "Class not found");
        String sectionId = requireText(request.get("sectionId"), "Section not found");
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = longNum(section.get("schoolId"), 0);
        Long requestedSchoolId = request.containsKey("schoolId") ? longNum(request.get("schoolId"), 0) : null;
        if (requestedSchoolId != null && !requestedSchoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }
        int total = (int) countStudents(sectionId);
        int present = (int) jdbc.sql("""
                SELECT COUNT(*) FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                  AND status = 'PRESENT'
                """.formatted(recordsTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query(Long.class)
                .single()
                .longValue();
        upsertDaily(date, classId, sectionId, academicYearId, total, present,
                request.containsKey("actorId") ? longNum(request.get("actorId"), 0) : null, true);
        return sectionRegister(date, classId, sectionId, sectionSchoolId);
    }

    @Transactional
    public Map<String, Object> submitAttendanceDay(String dateText) {
        return submitAttendanceDay(dateText, null, null);
    }

    @Transactional
    public Map<String, Object> submitAttendanceDay(String dateText, Long schoolId, Long actorId) {
        LocalDate date = parseDate(dateText);
        String academicYearId = currentAcademicYearId();
        String schoolPredicate = schoolId == null ? "" : """
                AND EXISTS (
                    SELECT 1
                    FROM tenant_school.school_sections ss
                    WHERE ss.id = ad.section_id
                      AND ss.school_id = :schoolId
                )
                """;
        var updateSpec = jdbc.sql("""
                UPDATE %s
                SET locked = true, updated_by = :actorId, updated_at = :updatedAt
                WHERE attendance_date = :date AND academic_year_id = :academicYearId
                %s
                """.formatted(dailyTable + " ad", schoolPredicate))
                .param("updatedAt", OffsetDateTime.now())
                .param("actorId", actorId)
                .param("date", date)
                .param("academicYearId", academicYearId);
        if (schoolId != null) {
            updateSpec = updateSpec.param("schoolId", schoolId);
        }
        int updated = updateSpec.update();

        var metricsSpec = jdbc.sql("""
                SELECT COUNT(*) AS record_count,
                       COALESCE(SUM(ad.present_count), 0) AS present_count,
                       COALESCE(SUM(ad.absent_count), 0) AS absent_count,
                       COALESCE(SUM(ad.total_enrolled), 0) AS total_enrolled,
                       MIN(ss.school_id) AS school_id
                FROM %s ad
                LEFT JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
                WHERE ad.attendance_date = :date
                  AND ad.academic_year_id = :academicYearId
                %s
                """.formatted(dailyTable, schoolId == null ? "" : "AND ss.school_id = :schoolId"))
                .param("date", date)
                .param("academicYearId", academicYearId);
        if (schoolId != null) {
            metricsSpec = metricsSpec.param("schoolId", schoolId);
        }
        Map<String, Object> metrics = metricsSpec
                .query((rs, rowNum) -> row(
                        "recordCount", rs.getInt("record_count"),
                        "presentCount", rs.getInt("present_count"),
                        "absentCount", rs.getInt("absent_count"),
                        "totalEnrolled", rs.getInt("total_enrolled"),
                        "schoolId", rs.getObject("school_id", Long.class)))
                .single();
        return row("ok", true,
                "submitted", updated,
                "submissionId", "attendance-day:" + date + ":" + (schoolId == null ? "all" : schoolId),
                "attendanceDate", date.toString(),
                "schoolId", metrics.get("schoolId"),
                "academicYearId", academicYearId,
                "recordCount", metrics.get("recordCount"),
                "presentCount", metrics.get("presentCount"),
                "absentCount", metrics.get("absentCount"),
                "totalEnrolled", metrics.get("totalEnrolled"),
                "actorId", actorId);
    }

    public record DailyAttendanceRow(
            String id,
            LocalDate attendanceDate,
            Integer totalEnrolled,
            Integer presentCount,
            Integer absentCount,
            Long recordedBy,
            OffsetDateTime recordedAt,
            Long updatedBy,
            OffsetDateTime updatedAt,
            Boolean locked,
            String schoolClassId,
            String sectionId,
            String academicYearId) {
    }

    public record StudentAttendanceRow(
            String id,
            String attendanceDailyId,
            Long studentId,
            Long schoolId,
            LocalDate attendanceDate,
            String academicYearId,
            String classId,
            String sectionId,
            String status,
            String remarks,
            Long recordedBy,
            OffsetDateTime recordedAt,
            Long updatedBy,
            OffsetDateTime updatedAt) {
    }

    private String currentAcademicYearId() {
        return jdbc.sql("SELECT id FROM tenant_school.academic_years WHERE active = true ORDER BY id LIMIT 1")
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private Map<String, Object> classRecord(String classId) {
        return jdbc.sql("SELECT id, name FROM tenant_school.school_classes WHERE id = :id")
                .param("id", classId)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Class not found"));
    }

    private Map<String, Object> sectionRecord(String sectionId) {
        return jdbc.sql("""
                SELECT ss.id, ss.name, ss.teacher_name, ss.school_id, ss.school_class_id, sc.name AS class_name
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                WHERE ss.id = :id
                """)
                .param("id", sectionId)
                .query((rs, rowNum) -> row("id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "teacherName", rs.getString("teacher_name"),
                        "schoolId", rs.getLong("school_id"),
                        "classId", rs.getString("school_class_id"),
                        "className", rs.getString("class_name")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Section not found"));
    }

    private Map<String, Object> dailyRecord(LocalDate date, String sectionId, String academicYearId) {
        return jdbc.sql("""
                SELECT id, locked
                FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                """.formatted(dailyTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "locked", rs.getBoolean("locked")))
                .optional()
                .orElse(null);
    }

    private String upsertDaily(LocalDate date, String classId, String sectionId, String academicYearId,
                               int total, int present, Long actorId, boolean locked) {
        OffsetDateTime now = OffsetDateTime.now();
        String id = dailyRecord(date, sectionId, academicYearId) == null
                ? UUID.randomUUID().toString()
                : String.valueOf(dailyRecord(date, sectionId, academicYearId).get("id"));
        jdbc.sql("""
                INSERT INTO %s(id, attendance_date, total_enrolled, present_count, absent_count,
                               recorded_by, recorded_at, updated_by, updated_at, locked,
                               school_class_id, section_id, academic_year_id)
                VALUES (:id, :date, :total, :present, :absent, :actorId, :recordedAt, :actorId,
                        :updatedAt, :locked, :classId, :sectionId, :academicYearId)
                ON CONFLICT (attendance_date, section_id, academic_year_id) DO UPDATE SET
                    total_enrolled = EXCLUDED.total_enrolled,
                    present_count = EXCLUDED.present_count,
                    absent_count = EXCLUDED.absent_count,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at,
                    locked = EXCLUDED.locked
                """.formatted(dailyTable))
                .param("id", id)
                .param("date", date)
                .param("total", total)
                .param("present", present)
                .param("absent", Math.max(total - present, 0))
                .param("actorId", actorId)
                .param("recordedAt", now)
                .param("updatedAt", now)
                .param("locked", locked)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .update();
        return jdbc.sql("""
                SELECT id FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                """.formatted(dailyTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query(String.class)
                .single();
    }

    private void ensureStudentInSection(Long studentId, Long schoolId, String classId, String sectionId) {
        long count = jdbc.sql("""
                SELECT COUNT(*)
                FROM student.students
                WHERE id = :studentId AND school_id = :schoolId AND class_id = :classId
                  AND section_id = :sectionId AND deleted_at IS NULL
                """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new IllegalArgumentException("Student not found in section");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        throw new IllegalArgumentException("records must be an array");
    }

    private long countStudents(String sectionId) {
        return jdbc.sql("SELECT COUNT(*) FROM student.students WHERE section_id = :sectionId")
                .param("sectionId", sectionId)
                .query(Long.class)
                .single();
    }

    private LocalDate parseDate(String input) {
        if (input == null || input.isBlank() || "today".equalsIgnoreCase(input)) {
            return LocalDate.now();
        }
        return LocalDate.parse(input);
    }

    private String requireText(Object value, String message) {
        String text = str(value, "").trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private String qualifiedTable(String schema, String table) {
        String normalizedSchema = identifier(schema == null || schema.isBlank() ? "public" : schema);
        return normalizedSchema + "." + identifier(table);
    }

    private String identifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid database identifier: " + identifier);
        }
        return identifier;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double doubleNum(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
