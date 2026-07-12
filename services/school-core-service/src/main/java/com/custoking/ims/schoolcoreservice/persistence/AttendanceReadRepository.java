package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class AttendanceReadRepository {

    private final JdbcClient jdbc;
    private final StudentPhotoStorage photoStorage;
    private final OutboxWriter outbox;
    private final String dailyTable;
    private final String recordsTable;
    private final String absenteeTable;

    static final Set<String> ALLOWED_STATUSES = Set.of("PRESENT", "ABSENT", "LATE", "LEAVE");

    public AttendanceReadRepository(
            JdbcClient jdbc,
            StudentPhotoStorage photoStorage,
            OutboxWriter outbox,
            @Value("${attendance.db.schema:attendance}") String schema) {
        this.jdbc = jdbc;
        this.photoStorage = photoStorage;
        this.outbox = outbox;
        this.dailyTable = qualifiedTable(schema, "attendance_daily");
        this.recordsTable = qualifiedTable(schema, "attendance_student_records");
        this.absenteeTable = qualifiedTable(schema, "absentee_notifications");
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
                       ad.total_enrolled, ad.present_count, ad.absent_count,
                       ad.late_count, ad.leave_count, ad.recorded_at, ad.updated_at, ad.locked
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
                    Integer lateCount = rs.getObject("late_count", Integer.class);
                    Integer leaveCount = rs.getObject("leave_count", Integer.class);
                    long totalStudents = countStudents(rs.getString("id"));
                    boolean emptySection = totalStudents == 0;
                    boolean sectionLocked = emptySection || Boolean.TRUE.equals(rs.getObject("locked", Boolean.class));
                    String status = emptySection
                            ? "Submitted"
                            : presentCount == null ? "Pending" : sectionLocked ? "Submitted" : "Saved";
                    Double presentPercent = null;
                    if (emptySection) {
                        presentPercent = 0.0;
                    } else if (presentCount != null) {
                        presentPercent = attendancePercent(
                                presentCount,
                                lateCount == null ? 0 : lateCount,
                                absentCount == null ? 0 : absentCount);
                    }
                    return row("sectionId", rs.getString("id"),
                            "classId", rs.getString("school_class_id"),
                            "sectionName", rs.getString("class_name") + "-" + rs.getString("name"),
                            "totalStudents", totalStudents,
                            "presentPercent", presentPercent,
                            "presentCount", presentCount == null ? 0 : presentCount,
                            "lateCount", lateCount == null ? 0 : lateCount,
                            "leaveCount", leaveCount == null ? 0 : leaveCount,
                            "absentCount", absentCount == null ? 0 : absentCount,
                            "teacherName", rs.getString("teacher_name"),
                            "status", status,
                            "locked", sectionLocked);
                })
                .list();
        // Sections with no enrolled students have nobody to mark; showing them as "Submitted"
        // clutters the grid and reads as "attendance was taken". Over-configured section counts
        // (many empty sections per class) made every date look fully submitted. Drop the empties.
        sections = sections.stream()
                .filter(section -> longNum(section.get("totalStudents"), 0) > 0)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int overallAttended = 0;
        int overallDenom = 0;
        for (Map<String, Object> section : sections) {
            int p = (int) longNum(section.get("presentCount"), 0);
            int l = (int) longNum(section.get("lateCount"), 0);
            int a = (int) longNum(section.get("absentCount"), 0);
            overallAttended += p + l;
            overallDenom += p + l + a;
        }
        double overall = overallDenom == 0 ? 0 : round(overallAttended * 100.0 / overallDenom);
        return row("date", date.toString(),
                "dateLabel", date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
                "overallPercent", overall,
                "sections", sections,
                "allSubmitted", sections.stream().noneMatch(section ->
                        longNum(section.get("totalStudents"), 0) > 0 && "Pending".equals(section.get("status"))),
                "nonWorkingDay", date.getDayOfWeek() == DayOfWeek.SUNDAY);
    }

    public Map<String, Object> registerReport(String month, String classId, String sectionId, Long schoolId) {
        YearMonth ym = YearMonth.parse(month);   // invalid → DateTimeParseException → 400 at controller
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
        if (schoolId != null && !schoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }

        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            days.add(row("date", d.toString(),
                    "dayOfMonth", d.getDayOfMonth(),
                    "weekday", d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    "nonWorkingDay", d.getDayOfWeek() == DayOfWeek.SUNDAY));
        }

        List<Map<String, Object>> students = jdbc.sql("""
                SELECT id, admission_no, roll_no, full_name
                FROM student.students
                WHERE school_id = :schoolId AND class_id = :classId AND section_id = :sectionId
                  AND deleted_at IS NULL
                ORDER BY NULLIF(regexp_replace(COALESCE(roll_no, ''), '[^0-9]', '', 'g'), '')::int NULLS LAST,
                         roll_no NULLS LAST, full_name
                """)
                .param("schoolId", sectionSchoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .query((rs, n) -> row("studentId", rs.getLong("id"),
                        "admissionNo", rs.getString("admission_no"),
                        "rollNo", rs.getString("roll_no"),
                        "fullName", rs.getString("full_name")))
                .list();

        // studentId -> (date -> status)
        Map<Long, Map<String, String>> byStudent = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT student_id, attendance_date, status
                FROM %s
                WHERE section_id = :sectionId AND academic_year_id = :academicYearId
                  AND attendance_date BETWEEN :first AND :last
                """.formatted(recordsTable))
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .param("first", first)
                .param("last", last)
                .query((rs, n) -> {
                    byStudent.computeIfAbsent(rs.getLong("student_id"), k -> new LinkedHashMap<>())
                            .put(rs.getObject("attendance_date", LocalDate.class).toString(), rs.getString("status"));
                    return null;
                })
                .list();

        int[] totP = new int[days.size()], totL = new int[days.size()], totE = new int[days.size()], totA = new int[days.size()];
        List<Map<String, Object>> studentRows = new ArrayList<>();
        int sumP = 0, sumL = 0, sumE = 0, sumA = 0;
        for (Map<String, Object> student : students) {
            Long sid = longNum(student.get("studentId"), 0);
            Map<String, String> byDate = byStudent.getOrDefault(sid, Map.of());
            List<Map<String, Object>> cells = new ArrayList<>();
            int p = 0, l = 0, e = 0, a = 0;
            for (int i = 0; i < days.size(); i++) {
                String date = str(days.get(i).get("date"), "");
                String status = byDate.get(date);
                cells.add(row("date", date, "status", status));
                if ("PRESENT".equals(status)) { p++; totP[i]++; }
                else if ("LATE".equals(status)) { l++; totL[i]++; }
                else if ("LEAVE".equals(status)) { e++; totE[i]++; }
                else if ("ABSENT".equals(status)) { a++; totA[i]++; }
            }
            sumP += p; sumL += l; sumE += e; sumA += a;
            studentRows.add(row("studentId", sid,
                    "admissionNo", student.get("admissionNo"),
                    "rollNo", student.get("rollNo"),
                    "fullName", student.get("fullName"),
                    "cells", cells,
                    "presentCount", p, "lateCount", l, "leaveCount", e, "absentCount", a,
                    "presentPercent", attendancePercent(p, l, a)));
        }

        List<Map<String, Object>> dayTotals = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            dayTotals.add(row("date", str(days.get(i).get("date"), ""),
                    "presentCount", totP[i], "lateCount", totL[i], "leaveCount", totE[i], "absentCount", totA[i]));
        }

        return row("month", month,
                "monthLabel", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear(),
                "classId", classId,
                "sectionId", sectionId,
                "sectionName", section.get("className") + "-" + section.get("name"),
                "teacherName", section.get("teacherName"),
                "days", days,
                "students", studentRows,
                "dayTotals", dayTotals,
                "totals", row("presentCount", sumP, "lateCount", sumL, "leaveCount", sumE, "absentCount", sumA,
                        "presentPercent", attendancePercent(sumP, sumL, sumA)));
    }

    public Map<String, Object> studentHistory(Long studentId, LocalDate from, LocalDate to, Long schoolId) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        String academicYearId = currentAcademicYearId();
        Map<String, Object> student = jdbc.sql("""
                SELECT s.id, s.admission_no, s.roll_no, s.full_name, s.school_id,
                       sec.name AS section_name, sc.name AS class_name
                FROM student.students s
                LEFT JOIN tenant_school.school_sections sec ON sec.id = s.section_id
                LEFT JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                WHERE s.id = :id AND s.deleted_at IS NULL
                """)
                .param("id", studentId)
                .query((rs, n) -> row("studentId", rs.getLong("id"),
                        "admissionNo", rs.getString("admission_no"),
                        "rollNo", rs.getString("roll_no"),
                        "fullName", rs.getString("full_name"),
                        "schoolId", rs.getLong("school_id"),
                        "sectionName", (rs.getString("class_name") == null ? "" : rs.getString("class_name") + "-")
                                + (rs.getString("section_name") == null ? "" : rs.getString("section_name"))))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        Long studentSchoolId = longNum(student.get("schoolId"), 0);
        if (schoolId != null && !schoolId.equals(studentSchoolId)) {
            throw new SecurityException("You do not have access to this student");
        }

        int[] buckets = new int[4]; // P, L, E, A
        List<Map<String, Object>> days = jdbc.sql("""
                SELECT attendance_date, status, remarks
                FROM %s
                WHERE student_id = :id AND academic_year_id = :academicYearId
                  AND attendance_date BETWEEN :from AND :to
                ORDER BY attendance_date
                """.formatted(recordsTable))
                .param("id", studentId)
                .param("academicYearId", academicYearId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> {
                    LocalDate d = rs.getObject("attendance_date", LocalDate.class);
                    String status = rs.getString("status");
                    if ("PRESENT".equals(status)) buckets[0]++;
                    else if ("LATE".equals(status)) buckets[1]++;
                    else if ("LEAVE".equals(status)) buckets[2]++;
                    else if ("ABSENT".equals(status)) buckets[3]++;
                    return row("date", d.toString(),
                            "weekday", d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                            "status", status,
                            "remarks", rs.getString("remarks") == null ? "" : rs.getString("remarks"),
                            "nonWorkingDay", d.getDayOfWeek() == DayOfWeek.SUNDAY);
                })
                .list();

        Map<String, Object> studentInfo = row("studentId", student.get("studentId"),
                "admissionNo", student.get("admissionNo"),
                "rollNo", student.get("rollNo"),
                "fullName", student.get("fullName"),
                "sectionName", student.get("sectionName"));
        return row("student", studentInfo,
                "from", from.toString(), "to", to.toString(),
                "days", days,
                "presentCount", buckets[0], "lateCount", buckets[1], "leaveCount", buckets[2], "absentCount", buckets[3],
                "presentPercent", attendancePercent(buckets[0], buckets[1], buckets[3]),
                "daysRecorded", days.size());
    }

    public Map<String, Object> sectionSummary(LocalDate from, LocalDate to, Long schoolId) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        String academicYearId = currentAcademicYearId();
        String scopeFilter = schoolId == null ? "" : " AND ad.school_id = :schoolId";
        var spec = jdbc.sql("""
                SELECT ss.id AS section_id, ss.school_class_id AS class_id, ss.name AS section_name,
                       ss.teacher_name, sc.name AS class_name,
                       COALESCE(SUM(ad.present_count), 0) AS p,
                       COALESCE(SUM(ad.late_count), 0)    AS l,
                       COALESCE(SUM(ad.leave_count), 0)   AS e,
                       COALESCE(SUM(ad.absent_count), 0)  AS a,
                       COUNT(ad.id) AS days_recorded
                FROM %s ad
                JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                WHERE ad.academic_year_id = :academicYearId
                  AND ad.attendance_date BETWEEN :from AND :to
                  %s
                GROUP BY ss.id, ss.school_class_id, ss.name, ss.teacher_name, sc.name
                """.formatted(dailyTable, scopeFilter))
                .param("academicYearId", academicYearId)
                .param("from", from)
                .param("to", to);
        if (schoolId != null) {
            spec = spec.param("schoolId", schoolId);
        }
        List<Map<String, Object>> sections = new ArrayList<>(spec
                .query((rs, n) -> {
                    int p = rs.getInt("p"), l = rs.getInt("l"), e = rs.getInt("e"), a = rs.getInt("a");
                    return row("classId", rs.getString("class_id"),
                            "sectionId", rs.getString("section_id"),
                            "sectionName", rs.getString("class_name") + "-" + rs.getString("section_name"),
                            "teacherName", rs.getString("teacher_name"),
                            "presentCount", p, "lateCount", l, "leaveCount", e, "absentCount", a,
                            "presentPercent", attendancePercent(p, l, a),
                            "daysRecorded", rs.getInt("days_recorded"));
                })
                .list());

        sections.sort((x, y) -> {
            int cmp = Double.compare(doubleNum(y.get("presentPercent")), doubleNum(x.get("presentPercent")));
            return cmp != 0 ? cmp : str(x.get("sectionName"), "").compareTo(str(y.get("sectionName"), ""));
        });

        int sp = 0, sl = 0, se = 0, sa = 0;
        for (Map<String, Object> s : sections) {
            sp += (int) longNum(s.get("presentCount"), 0);
            sl += (int) longNum(s.get("lateCount"), 0);
            se += (int) longNum(s.get("leaveCount"), 0);
            sa += (int) longNum(s.get("absentCount"), 0);
        }
        return row("from", from.toString(), "to", to.toString(),
                "sections", sections,
                "overall", row("presentCount", sp, "lateCount", sl, "leaveCount", se, "absentCount", sa,
                        "presentPercent", attendancePercent(sp, sl, sa)));
    }

    public Map<String, Object> sectionRegister(LocalDate date, String classId, String sectionId, Long schoolId) {
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
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
                        "photoUrl", photoStorage.toDisplayUrl(rs.getString("photo_url")),
                        "status", rs.getString("status"),
                        "remarks", rs.getString("remarks") == null ? "" : rs.getString("remarks")))
                .list();
        int present = (int) students.stream().filter(s -> "PRESENT".equals(s.get("status"))).count();
        int late = (int) students.stream().filter(s -> "LATE".equals(s.get("status"))).count();
        int leave = (int) students.stream().filter(s -> "LEAVE".equals(s.get("status"))).count();
        int absent = (int) students.stream().filter(s -> "ABSENT".equals(s.get("status"))).count();
        int total = students.size();
        return row("date", date.toString(),
                "classId", classId,
                "sectionId", sectionId,
                "sectionName", section.get("className") + "-" + section.get("name"),
                "locked", daily != null && Boolean.TRUE.equals(daily.get("locked")),
                "totalStudents", total,
                "presentCount", present,
                "lateCount", late,
                "leaveCount", leave,
                "absentCount", absent,
                "presentPercent", attendancePercent(present, late, absent),
                "students", students);
    }

    public Map<String, Object> absentees(LocalDate date, String classId, String sectionId, Long schoolId) {
        List<Map<String, Object>> students = absenteeRows(date, classId, sectionId, schoolId);
        long queued = students.stream().filter(s -> Boolean.TRUE.equals(s.get("alreadyQueued"))).count();
        return row("date", date.toString(),
                "sectionId", sectionId,
                "students", students,
                "totalAbsent", students.size(),
                "queuedCount", queued);
    }

    private List<Map<String, Object>> absenteeRows(LocalDate date, String classId, String sectionId, Long schoolId) {
        String academicYearId = currentAcademicYearId();
        StringBuilder sql = new StringBuilder("""
                SELECT s.id AS student_id, s.full_name, s.admission_no, s.roll_no,
                       s.school_id, ar.class_id, ar.section_id,
                       sc.name AS class_name, ss.name AS section_name,
                       COALESCE(NULLIF(s.father_contact, ''), NULLIF(s.phone, ''), '') AS parent_contact,
                       EXISTS (SELECT 1 FROM %s an
                                WHERE an.student_id = s.id AND an.attendance_date = :date) AS already_queued
                FROM %s ar
                JOIN student.students s ON s.id = ar.student_id AND s.deleted_at IS NULL
                JOIN tenant_school.school_sections ss ON ss.id = ar.section_id
                JOIN tenant_school.school_classes sc ON sc.id = ar.class_id
                WHERE ar.attendance_date = :date AND ar.academic_year_id = :academicYearId
                  AND ar.status = 'ABSENT'
                """.formatted(absenteeTable, recordsTable));
        if (schoolId != null) sql.append(" AND ar.school_id = :schoolId");
        if (classId != null && !classId.isBlank()) sql.append(" AND ar.class_id = :classId");
        if (sectionId != null && !sectionId.isBlank()) sql.append(" AND ar.section_id = :sectionId");
        sql.append("""
                 ORDER BY NULLIF(regexp_replace(COALESCE(s.roll_no, ''), '[^0-9]', '', 'g'), '')::int NULLS LAST,
                          s.roll_no NULLS LAST, s.full_name
                """);
        var spec = jdbc.sql(sql.toString())
                .param("date", date)
                .param("academicYearId", academicYearId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (classId != null && !classId.isBlank()) spec = spec.param("classId", classId);
        if (sectionId != null && !sectionId.isBlank()) spec = spec.param("sectionId", sectionId);
        return spec.query((rs, n) -> {
            String parent = rs.getString("parent_contact");
            return row("studentId", rs.getLong("student_id"),
                    "fullName", rs.getString("full_name"),
                    "admissionNo", rs.getString("admission_no"),
                    "rollNo", rs.getString("roll_no"),
                    "classSection", rs.getString("class_name") + "-" + rs.getString("section_name"),
                    "schoolId", rs.getLong("school_id"),
                    "classId", rs.getString("class_id"),
                    "sectionId", rs.getString("section_id"),
                    "parentContact", parent,
                    "hasContact", parent != null && !parent.isBlank(),
                    "alreadyQueued", rs.getBoolean("already_queued"));
        }).list();
    }

    @Transactional
    public Map<String, Object> notifyAbsentees(LocalDate date, String classId, String sectionId, Long schoolId, Long actorId) {
        String academicYearId = currentAcademicYearId();
        List<Map<String, Object>> students = absenteeRows(date, classId, sectionId, schoolId);
        Map<Long, String> schoolNameCache = new java.util.HashMap<>();
        String dateLabel = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        int queued = 0, skippedNoContact = 0, skippedAlreadyQueued = 0;
        for (Map<String, Object> s : students) {
            if (Boolean.TRUE.equals(s.get("alreadyQueued"))) { skippedAlreadyQueued++; continue; }
            if (!Boolean.TRUE.equals(s.get("hasContact"))) { skippedNoContact++; continue; }
            long rowSchoolId = longNum(s.get("schoolId"), 0);
            String schoolName = schoolNameFor(rowSchoolId, schoolNameCache);
            String message = "Dear Parent, " + s.get("fullName") + " (" + s.get("classSection")
                    + ") was marked absent on " + dateLabel + " at " + schoolName
                    + ". Please contact the school if this is unexpected.";
            int inserted = jdbc.sql("""
                    INSERT INTO %s(id, school_id, student_id, class_id, section_id, academic_year_id,
                                   attendance_date, parent_contact, channel, message, status, queued_by)
                    VALUES (:id, :schoolId, :studentId, :classId, :sectionId, :academicYearId,
                            :date, :parentContact, 'WHATSAPP', :message, 'QUEUED', :actorId)
                    ON CONFLICT (student_id, attendance_date) DO NOTHING
                    """.formatted(absenteeTable))
                    .param("id", UUID.randomUUID().toString())
                    .param("schoolId", longNum(s.get("schoolId"), 0))
                    .param("studentId", longNum(s.get("studentId"), 0))
                    .param("classId", s.get("classId"))
                    .param("sectionId", s.get("sectionId"))
                    .param("academicYearId", academicYearId)
                    .param("date", date)
                    .param("parentContact", s.get("parentContact"))
                    .param("message", message)
                    .param("actorId", actorId)
                    .update();
            if (inserted > 0) queued++; else skippedAlreadyQueued++;
        }
        return row("date", date.toString(), "queued", queued,
                "skippedNoContact", skippedNoContact, "skippedAlreadyQueued", skippedAlreadyQueued);
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
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
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
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
        Long requestedSchoolId = request.containsKey("schoolId") ? longNum(request.get("schoolId"), 0) : null;
        if (requestedSchoolId != null && !requestedSchoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }
        int total = (int) longNum(request.get("totalEnrolled"), countStudents(sectionId));
        int present = (int) longNum(request.get("presentCount"), 0);
        if (present < 0 || present > total) {
            throw new IllegalArgumentException("Present count is invalid");
        }
        Long actorId = request.get("actorId") != null ? longNum(request.get("actorId"), 0) : null;
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
                        late_count = 0, leave_count = 0,
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
                                                 school_class_id, section_id, academic_year_id, school_id)
                    VALUES (:id, :date, :total, :present, :absent, :actorId, :recordedAt, :actorId,
                            :updatedAt, false, :classId, :sectionId, :academicYearId, :schoolId)
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
                    .param("schoolId", sectionSchoolId)
                    .update();
        }
        emitDailyUpsertedEvent(id, sectionSchoolId, date, classId, sectionId, academicYearId,
                present, Math.max(total - present, 0), 0, 0, total);
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
        Long actorId = request.get("actorId") != null ? longNum(request.get("actorId"), 0) : null;
        String academicYearId = currentAcademicYearId();
        Map<String, Object> section = sectionRecord(sectionId);
        if (!classId.equals(section.get("classId"))) {
            throw new IllegalArgumentException("Section does not belong to class");
        }
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
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
        int subPresent = (int) records.stream().filter(r -> "PRESENT".equals(str(r.get("status"), ""))).count();
        int subLate = (int) records.stream().filter(r -> "LATE".equals(str(r.get("status"), ""))).count();
        int subLeave = (int) records.stream().filter(r -> "LEAVE".equals(str(r.get("status"), ""))).count();
        int subAbsent = (int) records.stream().filter(r -> "ABSENT".equals(str(r.get("status"), ""))).count();
        String dailyId = upsertDaily(date, classId, sectionId, academicYearId, total,
                subPresent, subAbsent, subLate, subLeave, actorId, false, sectionSchoolId);
        OffsetDateTime now = OffsetDateTime.now();
        for (Map<String, Object> record : records) {
            Long studentId = longNum(record.get("studentId"), 0);
            String status = requireText(record.get("status"), "Status is required");
            if (!ALLOWED_STATUSES.contains(status)) {
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
        int present = countStatus(date, sectionId, academicYearId, "PRESENT");
        int late = countStatus(date, sectionId, academicYearId, "LATE");
        int leave = countStatus(date, sectionId, academicYearId, "LEAVE");
        int absent = countStatus(date, sectionId, academicYearId, "ABSENT");
        upsertDaily(date, classId, sectionId, academicYearId, total, present, absent, late, leave,
                actorId, false, sectionSchoolId);
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
        Long sectionSchoolId = requireSectionSchool(section, sectionId);
        Long requestedSchoolId = request.containsKey("schoolId") ? longNum(request.get("schoolId"), 0) : null;
        if (requestedSchoolId != null && !requestedSchoolId.equals(sectionSchoolId)) {
            throw new SecurityException("You do not have access to this section");
        }
        int total = (int) countStudents(sectionId);
        int present = countStatus(date, sectionId, academicYearId, "PRESENT");
        int late = countStatus(date, sectionId, academicYearId, "LATE");
        int leave = countStatus(date, sectionId, academicYearId, "LEAVE");
        int absent = countStatus(date, sectionId, academicYearId, "ABSENT");
        upsertDaily(date, classId, sectionId, academicYearId, total, present, absent, late, leave,
                request.get("actorId") != null ? longNum(request.get("actorId"), 0) : null, true, sectionSchoolId);
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
        return AcademicCalendar.activeOrCurrentAcademicYearId(jdbc);
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
                               int total, int present, int absent, int late, int leave,
                               Long actorId, boolean locked, Long schoolId) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> existing = dailyRecord(date, sectionId, academicYearId);
        String id = existing == null ? UUID.randomUUID().toString() : String.valueOf(existing.get("id"));
        jdbc.sql("""
                INSERT INTO %s(id, attendance_date, total_enrolled, present_count, absent_count,
                               late_count, leave_count, recorded_by, recorded_at, updated_by, updated_at,
                               locked, school_class_id, section_id, academic_year_id, school_id)
                VALUES (:id, :date, :total, :present, :absent, :late, :leave, :actorId, :recordedAt,
                        :actorId, :updatedAt, :locked, :classId, :sectionId, :academicYearId, :schoolId)
                ON CONFLICT (attendance_date, section_id, academic_year_id) DO UPDATE SET
                    total_enrolled = EXCLUDED.total_enrolled,
                    present_count = EXCLUDED.present_count,
                    absent_count = EXCLUDED.absent_count,
                    late_count = EXCLUDED.late_count,
                    leave_count = EXCLUDED.leave_count,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at,
                    locked = EXCLUDED.locked
                """.formatted(dailyTable))
                .param("id", id)
                .param("date", date)
                .param("total", total)
                .param("present", present)
                .param("absent", absent)
                .param("late", late)
                .param("leave", leave)
                .param("actorId", actorId)
                .param("recordedAt", now)
                .param("updatedAt", now)
                .param("locked", locked)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .update();
        String upsertedId = jdbc.sql("""
                SELECT id FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId AND academic_year_id = :academicYearId
                """.formatted(dailyTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query(String.class)
                .single();
        emitDailyUpsertedEvent(upsertedId, schoolId, date, classId, sectionId, academicYearId,
                present, absent, late, leave, total);
        return upsertedId;
    }

    /**
     * Emits {@code attendance-daily.upserted.v1} to the shared tenant_school outbox in the same
     * transaction as the {@code attendance.attendance_daily} write it accompanies (Reporting
     * Decoupling SP3). The reporting-service projects this into {@code reporting.fact_attendance_daily}.
     */
    private void emitDailyUpsertedEvent(String id, Long schoolId, LocalDate date, String classId,
                                        String sectionId, String academicYearId,
                                        int present, int absent, int late, int leave, int total) {
        if (outbox == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("schoolId", schoolId);
        payload.put("date", date == null ? null : date.toString());
        payload.put("classId", classId);
        payload.put("sectionId", sectionId);
        payload.put("academicYearId", academicYearId);
        payload.put("presentCount", present);
        payload.put("absentCount", absent);
        payload.put("lateCount", late);
        payload.put("leaveCount", leave);
        payload.put("totalEnrolled", total);
        outbox.append("attendance-daily.upserted.v1", "AttendanceDailyUpserted:" + id,
                "AttendanceDaily", id, schoolId, payload);
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

    /** Package-private for unit testing; treat as private. */
    Long requireSectionSchool(Map<String, Object> section, String sectionId) {
        Long schoolId = longNum(section.get("schoolId"), 0);
        if (schoolId == null || schoolId <= 0) {
            throw new IllegalStateException(
                    "Section " + sectionId + " has no owning school_id; cannot scope attendance");
        }
        return schoolId;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String schoolNameFor(long schoolId, Map<Long, String> cache) {
        if (schoolId <= 0) {
            return "your school";
        }
        return cache.computeIfAbsent(schoolId, id -> jdbc.sql(
                        "SELECT name FROM tenant_school.schools WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse("your school"));
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

    /** The one place the attended/denominator percent rule lives. LEAVE is excused and never passed here. */
    static double attendancePercent(int present, int late, int absent) {
        int attended = present + late;
        int denom = present + late + absent;
        return denom == 0 ? 0.0 : Math.round(attended * 100.0 / denom * 10.0) / 10.0;
    }

    private int countStatus(LocalDate date, String sectionId, String academicYearId, String status) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM %s
                WHERE attendance_date = :date AND section_id = :sectionId
                  AND academic_year_id = :academicYearId AND status = :status
                """.formatted(recordsTable))
                .param("date", date)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .param("status", status)
                .query(Long.class)
                .single()
                .intValue();
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
