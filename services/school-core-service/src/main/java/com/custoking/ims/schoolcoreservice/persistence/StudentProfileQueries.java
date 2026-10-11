package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository.StudentPhotoContent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;

/** Student detail/history/fee/photo reads; no command transactions or outbox ownership. */
@Repository
public class StudentProfileQueries {
    private final JdbcClient jdbc;
    private final StudentPhotoStorage photoStorage;

    public StudentProfileQueries(JdbcClient jdbc, StudentPhotoStorage photoStorage) {
        this.jdbc = jdbc;
        this.photoStorage = photoStorage;
    }

    public Map<String, Object> workspaceStudentDetail(Long id) {
        return jdbc.sql("""
                SELECT s.id, s.full_name, s.admission_no, s.roll_no, s.board_reg_no, s.dob, s.gender,
                       s.admission_date, s.father_name, s.father_contact, s.mother_name, s.phone, s.address,
                       s.house_number, s.street, s.locality, s.city, s.state, s.pin_code,
                       s.photo_url, s.fee_status, s.attendance_percent, s.school_id, s.class_id, s.section_id,
                       sc.name AS class_name, ss.name AS section_name, ay.label AS academic_year_label,
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
                WHERE s.id = :id AND s.deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, rowNum) -> {
                    String fullName = str(rs.getString("full_name"), "Student");
                    String classLabel = str(rs.getString("class_name"), "");
                    String sectionLabel = str(rs.getString("section_name"), "");
                    Double attendance = rs.getObject("attendance_percent", Double.class);
                    Map<String, Object> base = row("id", rs.getLong("id"),
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
                    LinkedHashMap<String, Object> detail = new LinkedHashMap<>(base);
                    detail.put("schoolId", rs.getLong("school_id"));
                    detail.put("classId", rs.getString("class_id"));
                    detail.put("sectionId", rs.getString("section_id"));
                    detail.put("dateOfBirth", rs.getObject("dob", LocalDate.class) == null ? null : rs.getObject("dob", LocalDate.class).toString());
                    detail.put("admissionDate", rs.getObject("admission_date", LocalDate.class) == null
                            ? null : rs.getObject("admission_date", LocalDate.class).toString());
                    detail.put("gender", rs.getString("gender"));
                    detail.put("boardRegistrationNumber", rs.getString("board_reg_no"));
                    detail.put("motherName", rs.getString("mother_name"));
                    detail.put("phone", rs.getString("phone"));
                    detail.put("address", row("houseNumber", rs.getString("house_number"),
                            "street", rs.getString("street"),
                            "locality", rs.getString("locality"),
                            "city", rs.getString("city"),
                            "state", rs.getString("state"),
                            "pinCode", rs.getString("pin_code"),
                            "full", rs.getString("address")));
                    detail.put("fee", currentFeeForStudent(id));
                    return detail;
                })
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
    }

    public Map<String, Object> studentHistory(Long id) {
        Map<String, Object> student = jdbc.sql("""
                SELECT id, school_id, admission_no, full_name
                FROM student.students
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "id", rs.getLong("id"),
                        "schoolId", rs.getLong("school_id"),
                        "admissionNumber", rs.getString("admission_no"),
                        "fullName", rs.getString("full_name")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        List<Map<String, Object>> enrollments = jdbc.sql("""
                SELECT e.id, e.academic_year_id, ay.label AS academic_year,
                       e.class_id, sc.name AS class_name, e.section_id, ss.name AS section_name,
                       e.roll_no, e.status, e.effective_from, e.effective_to, e.reason,
                       e.source_type, e.source_id, e.created_by, e.created_at
                FROM student.student_enrollments e
                LEFT JOIN tenant_school.academic_years ay ON ay.id = e.academic_year_id
                LEFT JOIN tenant_school.school_classes sc ON sc.id = e.class_id
                LEFT JOIN tenant_school.school_sections ss ON ss.id = e.section_id
                WHERE e.student_id = :id
                ORDER BY e.effective_from DESC NULLS LAST, e.created_at DESC
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "id", rs.getString("id"),
                        "academicYearId", rs.getString("academic_year_id"),
                        "academicYear", rs.getString("academic_year"),
                        "classId", rs.getString("class_id"),
                        "className", rs.getString("class_name"),
                        "sectionId", rs.getString("section_id"),
                        "sectionName", rs.getString("section_name"),
                        "rollNo", rs.getString("roll_no"),
                        "status", rs.getString("status"),
                        "effectiveFrom", rs.getObject("effective_from", LocalDate.class),
                        "effectiveTo", rs.getObject("effective_to", LocalDate.class),
                        "reason", rs.getString("reason"),
                        "sourceType", rs.getString("source_type"),
                        "sourceId", rs.getString("source_id"),
                        "createdBy", rs.getObject("created_by"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        List<Map<String, Object>> importRows = jdbc.sql("""
                SELECT ir.batch_id, ir.row_no, ir.status, ir.message, ir.applied_at,
                       ib.original_file_name, ib.created_at
                FROM student.import_rows ir
                JOIN student.import_batches ib ON ib.id = ir.batch_id
                WHERE ir.applied_student_id = :id
                   OR EXISTS (
                       SELECT 1 FROM student.students s
                       WHERE s.id = :id AND s.import_batch_id = ir.batch_id
                         AND lower(s.admission_no) = lower(ir.admission_no)
                   )
                ORDER BY ib.created_at DESC, ir.row_no ASC
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "batchId", rs.getString("batch_id"),
                        "rowNumber", rs.getInt("row_no"),
                        "status", rs.getString("status"),
                        "message", rs.getString("message"),
                        "appliedAt", rs.getObject("applied_at", OffsetDateTime.class),
                        "fileName", rs.getString("original_file_name"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        List<Map<String, Object>> promotions = jdbc.sql("""
                SELECT i.batch_id, i.action, i.status, i.reason, i.created_at,
                       b.source_academic_year_id, source_ay.label AS source_academic_year,
                       b.target_academic_year_id, target_ay.label AS target_academic_year,
                       b.applied_at
                FROM student.student_promotion_batch_items i
                JOIN student.student_promotion_batches b ON b.id = i.batch_id
                LEFT JOIN tenant_school.academic_years source_ay ON source_ay.id = b.source_academic_year_id
                LEFT JOIN tenant_school.academic_years target_ay ON target_ay.id = b.target_academic_year_id
                WHERE i.student_id = :id
                ORDER BY i.created_at DESC
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "batchId", rs.getString("batch_id"),
                        "action", rs.getString("action"),
                        "status", rs.getString("status"),
                        "reason", rs.getString("reason"),
                        "sourceAcademicYearId", rs.getString("source_academic_year_id"),
                        "sourceAcademicYear", rs.getString("source_academic_year"),
                        "targetAcademicYearId", rs.getString("target_academic_year_id"),
                        "targetAcademicYear", rs.getString("target_academic_year"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "appliedAt", rs.getObject("applied_at", OffsetDateTime.class)))
                .list();
        List<Map<String, Object>> feeAssignments = feeTablesAvailable() ? jdbc.sql("""
                SELECT fa.id, fa.academic_year_id, ay.label AS academic_year,
                       fa.schedule, fa.band_discount, fa.manual_discount, fa.surcharge,
                       fa.net_payable, fa.paid_amount,
                       GREATEST(fa.net_payable - fa.paid_amount, 0) AS due_amount,
                       fa.assigned_by, fa.assigned_at, fa.updated_by, fa.updated_at,
                       fb.id AS band_id, fb.name AS plan_name
                FROM fee.fee_assignments fa
                LEFT JOIN tenant_school.academic_years ay ON ay.id = fa.academic_year_id
                LEFT JOIN fee.fee_bands fb ON fb.id = fa.band_id
                WHERE fa.student_id = :id
                ORDER BY fa.assigned_at DESC NULLS LAST, fa.updated_at DESC NULLS LAST
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "id", rs.getString("id"),
                        "assignmentId", rs.getString("id"),
                        "academicYearId", rs.getString("academic_year_id"),
                        "academicYear", rs.getString("academic_year"),
                        "bandId", rs.getString("band_id"),
                        "planName", rs.getString("plan_name"),
                        "schedule", rs.getString("schedule"),
                        "bandDiscountPercent", rs.getDouble("band_discount"),
                        "manualDiscountPercent", rs.getDouble("manual_discount"),
                        "surchargePercent", rs.getDouble("surcharge"),
                        "netPayablePaise", rs.getLong("net_payable"),
                        "paidAmountPaise", rs.getLong("paid_amount"),
                        "dueAmountPaise", rs.getLong("due_amount"),
                        "status", rs.getLong("due_amount") <= 0 ? "Paid" : "Overdue",
                        "assignedBy", rs.getObject("assigned_by"),
                        "assignedAt", rs.getObject("assigned_at", OffsetDateTime.class),
                        "updatedBy", rs.getObject("updated_by"),
                        "updatedAt", rs.getObject("updated_at", OffsetDateTime.class)))
                .list() : List.of();
        List<Map<String, Object>> feePayments = feeTablesAvailable() ? jdbc.sql("""
                SELECT p.id, p.assignment_id, p.amount, p.mode, p.notes, p.paid_at,
                       p.recorded_by, p.receipt_number, p.created_at,
                       fa.academic_year_id, ay.label AS academic_year,
                       fb.id AS band_id, fb.name AS plan_name
                FROM fee.payment_records p
                LEFT JOIN fee.fee_assignments fa ON fa.id = p.assignment_id
                LEFT JOIN tenant_school.academic_years ay ON ay.id = fa.academic_year_id
                LEFT JOIN fee.fee_bands fb ON fb.id = fa.band_id
                WHERE p.student_id = :id
                ORDER BY p.paid_at DESC NULLS LAST, p.created_at DESC NULLS LAST
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "id", rs.getString("id"),
                        "paymentId", rs.getString("id"),
                        "assignmentId", rs.getString("assignment_id"),
                        "academicYearId", rs.getString("academic_year_id"),
                        "academicYear", rs.getString("academic_year"),
                        "bandId", rs.getString("band_id"),
                        "planName", rs.getString("plan_name"),
                        "amountPaise", rs.getLong("amount"),
                        "mode", rs.getString("mode"),
                        "notes", rs.getString("notes"),
                        "paidAt", rs.getObject("paid_at", OffsetDateTime.class),
                        "recordedBy", rs.getObject("recorded_by"),
                        "receiptNumber", rs.getString("receipt_number"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list() : List.of();
        long completedAcademicYears = completedAcademicYearCount(id);
        return row("student", student, "enrollments", enrollments, "imports", importRows, "promotions", promotions,
                "feeAssignments", feeAssignments, "feePayments", feePayments,
                "historyYears", historyYears(enrollments, promotions, feeAssignments, feePayments),
                "completedAcademicYears", completedAcademicYears);
    }

    private Map<String, Object> currentFeeForStudent(Long studentId) {
        if (!feeTablesAvailable()) {
            return row("assigned", false, "status", "Pending");
        }
        String currentYearId = AcademicCalendar.activeOrCurrentAcademicYearId(jdbc, studentSchoolId(studentId));
        return jdbc.sql("""
                SELECT fa.id, fa.academic_year_id, ay.label AS academic_year,
                       fa.schedule, fa.net_payable, fa.paid_amount,
                       GREATEST(fa.net_payable - fa.paid_amount, 0) AS due_amount,
                       fb.id AS band_id, fb.name AS plan_name
                FROM fee.fee_assignments fa
                LEFT JOIN tenant_school.academic_years ay ON ay.id = fa.academic_year_id
                LEFT JOIN fee.fee_bands fb ON fb.id = fa.band_id
                WHERE fa.student_id = :studentId
                ORDER BY CASE WHEN fa.academic_year_id = :currentYearId THEN 0 ELSE 1 END,
                         fa.assigned_at DESC NULLS LAST,
                         fa.updated_at DESC NULLS LAST
                LIMIT 1
                """)
                .param("studentId", studentId)
                .param("currentYearId", currentYearId)
                .query((rs, n) -> row(
                        "assigned", true,
                        "assignmentId", rs.getString("id"),
                        "academicYearId", rs.getString("academic_year_id"),
                        "academicYear", rs.getString("academic_year"),
                        "bandId", rs.getString("band_id"),
                        "planName", rs.getString("plan_name"),
                        "schedule", rs.getString("schedule"),
                        "netPayablePaise", rs.getLong("net_payable"),
                        "paidAmountPaise", rs.getLong("paid_amount"),
                        "dueAmountPaise", rs.getLong("due_amount"),
                        "status", rs.getLong("due_amount") <= 0 ? "Paid" : "Overdue"))
                .optional()
                .orElseGet(() -> row("assigned", false, "status", "Pending"));
    }

    private boolean feeTablesAvailable() {
        Boolean available = jdbc.sql("SELECT to_regclass('fee.fee_assignments') IS NOT NULL")
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(available);
    }

    private List<Map<String, Object>> historyYears(List<Map<String, Object>>... groups) {
        LinkedHashMap<String, String> years = new LinkedHashMap<>();
        for (List<Map<String, Object>> group : groups) {
            for (Map<String, Object> entry : group) {
                addHistoryYear(years, entry.get("academicYearId"), entry.get("academicYear"));
                addHistoryYear(years, entry.get("sourceAcademicYearId"), entry.get("sourceAcademicYear"));
                addHistoryYear(years, entry.get("targetAcademicYearId"), entry.get("targetAcademicYear"));
            }
        }
        return years.entrySet().stream()
                .map(entry -> row("id", entry.getKey(), "label", entry.getValue()))
                .toList();
    }

    private void addHistoryYear(LinkedHashMap<String, String> years, Object id, Object label) {
        String yearId = str(id, "").trim();
        if (yearId.isBlank()) return;
        years.putIfAbsent(yearId, str(label, yearId));
    }

    private long completedAcademicYearCount(Long studentId) {
        Long appliedPromotionCount = jdbc.sql("""
                SELECT COUNT(DISTINCT b.source_academic_year_id)
                FROM student.student_promotion_batch_items i
                JOIN student.student_promotion_batches b ON b.id = i.batch_id
                WHERE i.student_id = :studentId
                  AND i.status = 'APPLIED'
                  AND i.action = 'PROMOTE'
                  AND b.status = 'APPLIED'
                """)
                .param("studentId", studentId)
                .query(Long.class)
                .single();
        return appliedPromotionCount == null ? 0L : appliedPromotionCount;
    }

    private Long studentSchoolId(Long studentId) {
        return jdbc.sql("SELECT school_id FROM student.students WHERE id = :studentId")
                .param("studentId", studentId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    public Long schoolIdForStudent(Long id) {
        return jdbc.sql("SELECT school_id FROM student.students WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new StudentNotFoundException("student not found"));
    }

    public Optional<StudentPhotoContent> studentPhotoContent(Long id) {
        var owner = jdbc.sql("""
                SELECT st.photo_url, school.school_uid::text AS school_uid
                FROM student.students st JOIN tenant_school.schools school ON school.id=st.school_id
                WHERE st.id=:id AND st.deleted_at IS NULL
                """).param("id", id)
                .query((rs,n) -> new String[]{rs.getString("photo_url"),rs.getString("school_uid")}).optional();
        if (owner.isEmpty()) return Optional.empty();
        return photoStorage.readStoredPhoto(owner.get()[0], owner.get()[1], id)
                .map(photo -> new StudentPhotoContent(photo.data(), photo.contentType()));
    }
}
