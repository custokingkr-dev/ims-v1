package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.StudentRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;

/** Directory and workspace list reads, including tenant filters and pagination. */
@Repository
public class StudentDirectoryQueries {
    private final JdbcClient jdbc;

    public StudentDirectoryQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT id, admission_no, roll_no, board_reg_no, full_name, dob, gender,
                   father_name, father_contact, mother_name, phone, address,
                   house_number, street, locality, city, state, pin_code, photo_url,
                   fee_status, attendance_percent, imported_at, import_batch_id,
                   created_at, updated_at, school_id, class_id, section_id, academic_year_id,
                   deleted_at
            FROM student.students
            """;

    public List<StudentRow> list(Long schoolId, String classId, String sectionId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE deleted_at IS NULL");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (classId != null && !classId.isBlank()) sql.append(" AND class_id = :classId");
        if (sectionId != null && !sectionId.isBlank()) sql.append(" AND section_id = :sectionId");
        sql.append(" ORDER BY full_name ASC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (classId != null && !classId.isBlank()) spec = spec.param("classId", classId);
        if (sectionId != null && !sectionId.isBlank()) spec = spec.param("sectionId", sectionId);
        return spec.query(StudentRow.class).list().stream().map(this::signPhoto).toList();
    }

    public Optional<StudentRow> find(Long id) {
        return jdbc.sql(SELECT + " WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(StudentRow.class)
                .optional()
                .map(this::signPhoto);
    }

    private StudentRow signPhoto(StudentRow row) {
        return row.withPhotoUrl(photoReference(row.id(), row.photoUrl()));
    }

    public Map<String, Object> workspaceStudents(Long schoolId, String className, String sectionName,
                                                 String feeStatus, int page, int size) {
        return workspaceStudents(schoolId, className, sectionName, feeStatus, null, page, size);
    }

    public Map<String, Object> workspaceStudents(Long schoolId, String className, String sectionName,
                                                 String feeStatus, String search, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 500));
        WorkspaceStudentStats stats = workspaceStudentStats(
                schoolId, className, sectionName, feeStatus, search);
        List<Map<String, Object>> items = workspaceStudentRowsPage(
                schoolId, className, sectionName, feeStatus, search,
                safeSize, (long) safePage * safeSize);
        return row("items", items,
                "page", safePage,
                "size", safeSize,
                "totalItems", stats.total(),
                "totalPages", (int) Math.ceil(stats.total() / (double) safeSize),
                "filteredCount", stats.total(),
                "filteredSections", stats.sections(),
                "search", str(search, "").trim(),
                "filters", row("classes", classesForSchool(schoolId),
                        "sections", sectionNamesForSchool(schoolId, className),
                        "feeStatuses", List.of("Paid", "Overdue", "Pending", "Partial")));
    }

    public List<Map<String, Object>> workspaceStudentRows(Long schoolId, String className, String sectionName,
                                                          String feeStatus) {
        return workspaceStudentRowsPage(schoolId, className, sectionName, feeStatus, null, null, 0);
    }

    private List<Map<String, Object>> workspaceStudentRowsPage(
            Long schoolId, String className, String sectionName, String feeStatus,
            String search, Integer limit, long offset) {
        String searchTerm = str(search, "").trim().toLowerCase(Locale.ROOT);
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.full_name, s.admission_no, s.roll_no, s.board_reg_no, s.dob, s.gender,
                       s.father_name, s.father_contact, s.mother_name, s.phone, s.address,
                       s.house_number, s.street, s.locality, s.city, s.state, s.pin_code,
                       s.photo_url, s.fee_status, s.attendance_percent, s.school_id,
                       sc.id AS class_id, sc.name AS class_name, sc.sort_order,
                       ss.id AS section_id, ss.name AS section_name,
                       ay.label AS academic_year_label,
                       profile_review.status AS profile_verification_status,
                       photo_review.status AS photo_verification_status
                FROM student.students s
                JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                JOIN tenant_school.school_sections ss ON ss.id = s.section_id
                JOIN tenant_school.academic_years ay ON ay.id = s.academic_year_id
                LEFT JOIN LATERAL (
                    SELECT i.status
                    FROM student.student_review_items i
                    JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                    WHERE i.student_id = s.id AND i.school_id = s.school_id
                      AND c.review_type = 'PROFILE_VERIFICATION' AND c.status = 'ACTIVE'
                    ORDER BY i.updated_at DESC NULLS LAST, i.created_at DESC NULLS LAST, i.id DESC
                    LIMIT 1
                ) profile_review ON TRUE
                LEFT JOIN LATERAL (
                    SELECT i.status
                    FROM student.student_review_items i
                    JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                    WHERE i.student_id = s.id AND i.school_id = s.school_id
                      AND c.review_type = 'PHOTO_VERIFICATION' AND c.status = 'ACTIVE'
                    ORDER BY i.updated_at DESC NULLS LAST, i.created_at DESC NULLS LAST, i.id DESC
                    LIMIT 1
                ) photo_review ON TRUE
                WHERE
                """)
                .append(" s.deleted_at IS NULL")
                .append("""
                  AND s.school_id = :schoolId
                """);
        if (!blankOrAll(className)) sql.append(" AND lower(sc.name) = lower(:className)");
        if (!blankOrAll(sectionName)) sql.append(" AND lower(ss.name) = lower(:sectionName)");
        if (!blankOrAll(feeStatus)) sql.append(" AND lower(s.fee_status) = lower(:feeStatus)");
        if (!searchTerm.isBlank()) {
            sql.append("""
                     AND (
                       lower(s.full_name) LIKE :search
                       OR lower(s.admission_no) LIKE :search
                       OR lower(COALESCE(s.roll_no, '')) LIKE :search
                       OR lower(COALESCE(s.board_reg_no, '')) LIKE :search
                       OR lower(COALESCE(s.father_name, '')) LIKE :search
                       OR lower(COALESCE(s.father_contact, '')) LIKE :search
                       OR lower(COALESCE(s.mother_name, '')) LIKE :search
                       OR lower(COALESCE(s.phone, '')) LIKE :search
                       OR lower(COALESCE(s.address, '')) LIKE :search
                       OR lower(COALESCE(s.house_number, '')) LIKE :search
                       OR lower(COALESCE(s.street, '')) LIKE :search
                       OR lower(COALESCE(s.locality, '')) LIKE :search
                       OR lower(COALESCE(s.city, '')) LIKE :search
                       OR lower(COALESCE(s.state, '')) LIKE :search
                       OR lower(COALESCE(s.pin_code, '')) LIKE :search
                       OR lower(sc.name) LIKE :search
                       OR lower(ss.name) LIKE :search
                     )
                    """);
        }
        sql.append(" ORDER BY lower(s.full_name), s.id");
        if (limit != null) sql.append(" LIMIT :limit OFFSET :offset");

        var spec = jdbc.sql(sql.toString()).param("schoolId", schoolId);
        if (!blankOrAll(className)) spec = spec.param("className", className);
        if (!blankOrAll(sectionName)) spec = spec.param("sectionName", sectionName);
        if (!blankOrAll(feeStatus)) spec = spec.param("feeStatus", feeStatus);
        if (!searchTerm.isBlank()) spec = spec.param("search", "%" + searchTerm + "%");
        if (limit != null) spec = spec.param("limit", limit).param("offset", Math.max(0, offset));
        return spec.query((rs, rowNum) -> {
            String fullName = str(rs.getString("full_name"), "Student");
            String classLabel = str(rs.getString("class_name"), "");
            String sectionLabel = str(rs.getString("section_name"), "");
            Double attendance = rs.getObject("attendance_percent", Double.class);
            return row("id", rs.getLong("id"),
                    "name", fullName,
                    "fullName", fullName,
                    "avatarInitials", initials(fullName),
                    "photoUrl", photoReference(rs.getLong("id"), rs.getString("photo_url")),
                    "className", classLabel,
                    "sectionName", sectionLabel,
                    "classSection", classLabel.replace("Class ", "") + (sectionLabel.isBlank() ? "" : "-" + sectionLabel),
                    "academicYear", rs.getString("academic_year_label"),
                    "admissionNumber", rs.getString("admission_no"),
                    "rollNo", rs.getString("roll_no"),
                    "fatherName", rs.getString("father_name"),
                    "fatherContact", rs.getString("father_contact"),
                    "feeStatus", rs.getString("fee_status"),
                    "profileVerificationStatus", rs.getString("profile_verification_status"),
                    "photoVerificationStatus", rs.getString("photo_verification_status"),
                    "attendancePercent", attendance == null ? 0 : round(attendance));
        }).list();
    }

    private WorkspaceStudentStats workspaceStudentStats(
            Long schoolId, String className, String sectionName, String feeStatus,
            String search) {
        String searchTerm = str(search, "").trim().toLowerCase(Locale.ROOT);
        boolean needsStructureJoin = !blankOrAll(className)
                || !blankOrAll(sectionName)
                || !searchTerm.isBlank();
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS total,
                       COUNT(DISTINCT s.section_id) AS sections
                FROM student.students s
                """);
        if (needsStructureJoin) {
            sql.append("""
                    JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                    JOIN tenant_school.school_sections ss ON ss.id = s.section_id
                    """);
        }
        sql.append(" WHERE")
                .append(" s.deleted_at IS NULL")
                .append(" AND s.school_id = :schoolId");
        if (!blankOrAll(className)) sql.append(" AND lower(sc.name) = lower(:className)");
        if (!blankOrAll(sectionName)) sql.append(" AND lower(ss.name) = lower(:sectionName)");
        if (!blankOrAll(feeStatus)) sql.append(" AND lower(s.fee_status) = lower(:feeStatus)");
        if (!searchTerm.isBlank()) {
            sql.append("""
                     AND (
                       lower(s.full_name) LIKE :search
                       OR lower(s.admission_no) LIKE :search
                       OR lower(COALESCE(s.roll_no, '')) LIKE :search
                       OR lower(COALESCE(s.board_reg_no, '')) LIKE :search
                       OR lower(COALESCE(s.father_name, '')) LIKE :search
                       OR lower(COALESCE(s.father_contact, '')) LIKE :search
                       OR lower(COALESCE(s.mother_name, '')) LIKE :search
                       OR lower(COALESCE(s.phone, '')) LIKE :search
                       OR lower(COALESCE(s.address, '')) LIKE :search
                       OR lower(COALESCE(s.house_number, '')) LIKE :search
                       OR lower(COALESCE(s.street, '')) LIKE :search
                       OR lower(COALESCE(s.locality, '')) LIKE :search
                       OR lower(COALESCE(s.city, '')) LIKE :search
                       OR lower(COALESCE(s.state, '')) LIKE :search
                       OR lower(COALESCE(s.pin_code, '')) LIKE :search
                       OR lower(sc.name) LIKE :search
                       OR lower(ss.name) LIKE :search
                     )
                    """);
        }
        var spec = jdbc.sql(sql.toString()).param("schoolId", schoolId);
        if (!blankOrAll(className)) spec = spec.param("className", className);
        if (!blankOrAll(sectionName)) spec = spec.param("sectionName", sectionName);
        if (!blankOrAll(feeStatus)) spec = spec.param("feeStatus", feeStatus);
        if (!searchTerm.isBlank()) spec = spec.param("search", "%" + searchTerm + "%");
        return spec.query((rs, rowNum) -> new WorkspaceStudentStats(
                rs.getLong("total"), rs.getLong("sections"))).single();
    }

    private List<String> classesForSchool(Long schoolId) {
        return jdbc.sql("""
                SELECT sc.name
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                WHERE ss.school_id = :schoolId
                  AND ss.active = true
                GROUP BY sc.name
                ORDER BY min(sc.sort_order), sc.name
                """)
                .param("schoolId", schoolId)
                .query(String.class)
                .list();
    }

    private List<String> sectionNamesForSchool(Long schoolId, String className) {
        if (blankOrAll(className)) return List.of();
        return jdbc.sql("""
                SELECT DISTINCT ss.name
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                WHERE ss.school_id = :schoolId
                  AND ss.active = true
                  AND sc.name = :className
                ORDER BY ss.name
                """)
                .param("schoolId", schoolId)
                .param("className", className.trim())
                .query(String.class)
                .list();
    }

    private record WorkspaceStudentStats(long total, long sections) {
    }
}
