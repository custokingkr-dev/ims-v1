package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.erasure.StudentErasureJournal;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentValues.*;
import static com.custoking.ims.schoolcoreservice.persistence.StudentRequestActor.actorId;

/**
 * Student persistence facade; imports, promotions, reviews and reads delegate to focused components.
 * Write callers must use StudentCommands or an explicit recovery/test transaction.
 * Managed writes require an existing transaction; this repository never starts one.
 * Directory reads are delegated to StudentDirectoryQueries for API compatibility.
 */
@Repository
public class StudentReadRepository {

    private final JdbcClient jdbc;
    private final StudentDirectoryQueries directory;
    private final StudentProfileQueries profiles;
    private final StudentStructurePersistence structure;
    private final StudentEnrollmentPersistence enrollment;
    private final StudentImportPersistence imports;
    private final StudentPromotionPersistence promotions;
    private final StudentReviewPersistence reviews;
    private final StudentPhotoStorage photoStorage;
    private final OutboxWriter outbox;
    private final StudentReviewInvalidationService reviewInvalidation;
    private final LegacyGuardianSynchronizer guardianSynchronizer;
    private StudentErasureJournal erasureJournal;

    public StudentReadRepository(JdbcClient jdbc, StudentPhotoStorage photoStorage, OutboxWriter outbox) {
        this(jdbc, photoStorage, outbox, new StudentReviewInvalidationService(jdbc, outbox));
    }

    public StudentReadRepository(JdbcClient jdbc, StudentPhotoStorage photoStorage, OutboxWriter outbox,
                                 StudentReviewInvalidationService reviewInvalidation) {
        this(jdbc, photoStorage, outbox, reviewInvalidation, new StudentDirectoryQueries(jdbc));
    }

    public StudentReadRepository(JdbcClient jdbc, StudentPhotoStorage photoStorage, OutboxWriter outbox,
                                 StudentReviewInvalidationService reviewInvalidation,
                                 StudentDirectoryQueries directory) {
        this(jdbc, photoStorage, outbox, reviewInvalidation, directory, new StudentProfileQueries(jdbc, photoStorage));
    }

    public StudentReadRepository(JdbcClient jdbc, StudentPhotoStorage photoStorage, OutboxWriter outbox,
                                 StudentReviewInvalidationService reviewInvalidation,
                                 StudentDirectoryQueries directory, StudentProfileQueries profiles) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.profiles = profiles;
        this.photoStorage = photoStorage;
        this.outbox = outbox;
        this.reviewInvalidation = reviewInvalidation;
        this.guardianSynchronizer = new LegacyGuardianSynchronizer(jdbc);
        this.structure = new StudentStructurePersistence(jdbc);
        this.enrollment = new StudentEnrollmentPersistence(jdbc, outbox, structure);
        this.imports = new StudentImportPersistence(jdbc, photoStorage, structure, enrollment);
        this.promotions = new StudentPromotionPersistence(jdbc, structure, enrollment);
        this.reviews = new StudentReviewPersistence(jdbc, outbox, reviewInvalidation, structure);
    }

    @Autowired
    public StudentReadRepository(JdbcClient jdbc, StudentPhotoStorage photoStorage, OutboxWriter outbox,
                                 StudentReviewInvalidationService reviewInvalidation,
                                 StudentDirectoryQueries directory, StudentProfileQueries profiles,
                                 StudentStructurePersistence structure, StudentEnrollmentPersistence enrollment,
                                 StudentImportPersistence imports, StudentPromotionPersistence promotions,
                                 StudentReviewPersistence reviews) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.profiles = profiles;
        this.photoStorage = photoStorage;
        this.outbox = outbox;
        this.reviewInvalidation = reviewInvalidation;
        this.guardianSynchronizer = new LegacyGuardianSynchronizer(jdbc);
        this.structure = structure;
        this.enrollment = enrollment;
        this.imports = imports;
        this.promotions = promotions;
        this.reviews = reviews;
    }

    @Autowired(required = false)
    void setImportProgress(StudentImportProgressStore importProgress) {
        imports.setImportProgress(importProgress);
    }

    @Autowired
    public void setErasureJournal(StudentErasureJournal erasureJournal) {
        this.erasureJournal = erasureJournal;
    }

    public List<StudentRow> list(Long schoolId, String classId, String sectionId, int limit) {
        return directory.list(schoolId, classId, sectionId, limit);
    }

    public Optional<StudentRow> find(Long id) {
        return directory.find(id);
    }

    public Map<String, Object> workspaceStudents(Long schoolId, String className, String sectionName,
                                                 String feeStatus, int page, int size) {
        return directory.workspaceStudents(schoolId, className, sectionName, feeStatus, page, size);
    }

    public Map<String, Object> workspaceStudents(Long schoolId, String className, String sectionName,
                                                 String feeStatus, String search, int page, int size) {
        return directory.workspaceStudents(schoolId, className, sectionName, feeStatus, search, page, size);
    }

    public List<Map<String, Object>> workspaceStudentRows(Long schoolId, String className, String sectionName,
                                                          String feeStatus) {
        return directory.workspaceStudentRows(schoolId, className, sectionName, feeStatus);
    }

    public Map<String, Object> workspaceStudentDetail(Long id) {
        return profiles.workspaceStudentDetail(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> createStudent(Map<String, Object> request) {
        String suppliedPhoto = str(request.get("photoUrl"), "").trim();
        if (!suppliedPhoto.isEmpty() && !suppliedPhoto.matches("(?i)^https?://.*")) {
            throw new IllegalArgumentException("Private photo keys may only be assigned by the photo upload workflow");
        }
        String admissionNo = requireText(firstPresent(request, "admissionNumber", "admissionNo"), "Admission Number is mandatory");
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId == null) {
            throw new IllegalArgumentException("School not found");
        }
        structure.requireSchool(schoolId);
        Long duplicate = jdbc.sql("SELECT COUNT(*) FROM student.students WHERE lower(admission_no) = lower(:admissionNo) AND school_id = :schoolId")
                .param("admissionNo", admissionNo)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (duplicate != null && duplicate > 0) {
            throw new IllegalArgumentException("Admission Number already exists");
        }
        String fullName = requireText(request.get("fullName"), "Full name is mandatory");
        String requestedClassId = str(firstPresent(request, "classId", "class_id"), "").trim();
        Map<String, Object> schoolClass = requestedClassId.isBlank()
                ? structure.classByName(str(firstPresent(request, "gradeLevel", "className"), "Class 9"))
                    .or(() -> structure.classById(String.valueOf(classSortOrder(str(request.get("gradeLevel"), "9")))))
                    .orElseThrow(() -> new IllegalArgumentException("Class not found"))
                : structure.classById(requestedClassId)
                    .orElseThrow(() -> new IllegalArgumentException("Class not found"));
        String classId = String.valueOf(schoolClass.get("id"));
        String requestedSectionId = str(firstPresent(request, "sectionId", "section_id"), "").trim();
        Map<String, Object> section = requestedSectionId.isBlank()
                ? structure.getOrCreateSection(schoolId, classId, str(request.get("sectionName"), "A"))
                : structure.sectionByIdForClassSchool(schoolId, classId, requestedSectionId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected section does not belong to the class and school"));
        String academicYearId = structure.currentAcademicYearId(schoolId);
        OffsetDateTime now = OffsetDateTime.now();
        Long id;
        try {
            id = jdbc.sql("""
                    INSERT INTO student.students(admission_no, roll_no, board_reg_no, full_name, dob, admission_date, gender,
                                         father_name, father_contact, mother_name, phone, address,
                                         house_number, street, locality, city, state, pin_code, photo_url,
                                         fee_status, attendance_percent, created_at, updated_at,
                                         school_id, class_id, section_id, academic_year_id, version)
                    VALUES (:admissionNo, :rollNo, :boardRegNo, :fullName, :dob, :admissionDate, :gender,
                            :fatherName, :fatherContact, :motherName, :phone, :address,
                            :houseNumber, :street, :locality, :city, :state, :pinCode, :photoUrl,
                            'Pending', 0, :createdAt, :updatedAt,
                            :schoolId, :classId, :sectionId, :academicYearId, 0)
                    RETURNING id
                    """)
                    .param("admissionNo", admissionNo)
                    .param("rollNo", str(request.get("rollNo"), String.valueOf(structure.countBySection(String.valueOf(section.get("id"))) + 1)))
                    .param("boardRegNo", str(request.get("boardRegistrationNumber"), ""))
                    .param("fullName", fullName)
                    .param("dob", parseDateOfBirth(str(request.get("dateOfBirth"), "")))
                    .param("admissionDate", parseDate(str(request.get("admissionDate"), "")))
                    .param("gender", str(request.get("gender"), "Unspecified"))
                    .param("fatherName", str(request.get("fatherName"), ""))
                    .param("fatherContact", str(firstPresent(request, "fatherContactNumber", "fatherContact"), ""))
                    .param("motherName", str(request.get("motherName"), ""))
                    .param("phone", str(request.get("phone"), str(firstPresent(request, "fatherContactNumber", "fatherContact"), "")))
                    .param("address", preferredAddress(request.get("address"),
                            str(request.get("houseNumber"), ""),
                            str(request.get("street"), ""),
                            str(request.get("locality"), ""),
                            str(request.get("city"), ""),
                            str(request.get("state"), ""),
                            str(request.get("pinCode"), "")))
                    .param("houseNumber", str(request.get("houseNumber"), ""))
                    .param("street", str(request.get("street"), ""))
                    .param("locality", str(request.get("locality"), ""))
                    .param("city", str(request.get("city"), ""))
                    .param("state", str(request.get("state"), ""))
                    .param("pinCode", str(request.get("pinCode"), ""))
                    .param("photoUrl", str(request.get("photoUrl"), null))
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .param("schoolId", schoolId)
                    .param("classId", classId)
                    .param("sectionId", section.get("id"))
                    .param("academicYearId", academicYearId)
                    .query(Long.class)
                    .single();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Backstop for the (school_id, admission_no) unique constraint.
            throw new IllegalArgumentException("Admission Number already exists");
        }
        guardianSynchronizer.syncFromLegacy(id);
        enrollment.recordEnrollmentFromCurrentStudent(id, "Enrolled", "STUDENT_CREATE", String.valueOf(id));
        enrollment.emitStudentUpserted(id);
        return studentDetail(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> updateStudent(Long id, Map<String, Object> request) {
        Map<String, Object> current = jdbc.sql("""
                SELECT id, school_id, academic_year_id, class_id, section_id, roll_no
                FROM student.students
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "id", rs.getLong("id"),
                        "schoolId", rs.getLong("school_id"),
                        "academicYearId", rs.getString("academic_year_id"),
                        "classId", rs.getString("class_id"),
                        "sectionId", rs.getString("section_id"),
                        "rollNo", rs.getString("roll_no")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        Long studentSchool = longValue(current.get("schoolId"), null);
        Long resolved = longValue(request.get("schoolId"), null);
        if (resolved != null && !resolved.equals(studentSchool)) {
            throw new SecurityException("You do not have access to this student");
        }

        String fullName = requireText(request.get("fullName"), "Full name is mandatory");
        String admissionNo = requireText(firstPresent(request, "admissionNumber", "admissionNo"),
                "Admission Number is mandatory");
        Long dup = jdbc.sql("""
                SELECT COUNT(*) FROM student.students
                WHERE lower(admission_no) = lower(:admissionNo) AND id <> :id AND deleted_at IS NULL
                  AND school_id = :studentSchool
                """)
                .param("admissionNo", admissionNo).param("id", id).param("studentSchool", studentSchool)
                .query(Long.class).single();
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("Admission Number already exists");
        }

        String classId = requireText(firstPresent(request, "classId", "class_id"), "Class is required");
        String sectionId = requireText(firstPresent(request, "sectionId", "section_id"), "Section is required");
        long sectionOk = jdbc.sql("""
                SELECT COUNT(*) FROM tenant_school.school_sections
                WHERE id = :sectionId AND school_class_id = :classId AND school_id = :schoolId
                """)
                .param("sectionId", sectionId).param("classId", classId).param("schoolId", studentSchool)
                .query(Long.class).single();
        if (sectionOk == 0) {
            throw new IllegalArgumentException("Selected section does not belong to the class and school");
        }

        String phone = requireText(request.get("phone"), "Phone is required");
        String address = preferredAddress(request.get("address"),
                str(request.get("houseNumber"), ""), str(request.get("street"), ""),
                str(request.get("locality"), ""), str(request.get("city"), ""),
                str(request.get("state"), ""), str(request.get("pinCode"), ""));

        try {
            jdbc.sql("""
                    UPDATE student.students SET
                        full_name = :fullName, roll_no = :rollNo, admission_no = :admissionNo,
                        board_reg_no = :boardRegNo, dob = :dob, admission_date = :admissionDate, gender = :gender,
                        father_name = :fatherName, father_contact = :fatherContact, mother_name = :motherName,
                        phone = :phone, address = :address, house_number = :houseNumber, street = :street,
                        locality = :locality, city = :city, state = :state, pin_code = :pinCode,
                        class_id = :classId, section_id = :sectionId,
                        updated_at = :now, updated_by = :updatedBy, version = version + 1
                    WHERE id = :id AND deleted_at IS NULL
                    """)
                    .param("id", id)
                    .param("fullName", fullName)
                    .param("rollNo", str(request.get("rollNo"), ""))
                    .param("admissionNo", admissionNo)
                    .param("boardRegNo", str(firstPresent(request, "boardRegistrationNumber", "boardRegNo"), ""))
                    .param("dob", parseDateOfBirth(str(firstPresent(request, "dateOfBirth", "dob"), "")))
                    .param("admissionDate", parseDate(str(request.get("admissionDate"), "")))
                    .param("gender", str(request.get("gender"), "Unspecified"))
                    .param("fatherName", str(request.get("fatherName"), ""))
                    .param("fatherContact", str(firstPresent(request, "fatherContactNumber", "fatherContact"), ""))
                    .param("motherName", str(request.get("motherName"), ""))
                    .param("phone", phone)
                    .param("address", address)
                    .param("houseNumber", str(request.get("houseNumber"), ""))
                    .param("street", str(request.get("street"), ""))
                    .param("locality", str(request.get("locality"), ""))
                    .param("city", str(request.get("city"), ""))
                    .param("state", str(request.get("state"), ""))
                    .param("pinCode", str(request.get("pinCode"), ""))
                    .param("classId", classId)
                    .param("sectionId", sectionId)
                    .param("now", OffsetDateTime.now())
                    .param("updatedBy", com.custoking.ims.schoolcoreservice.security.TenantContext.get() != null
                            && com.custoking.ims.schoolcoreservice.security.TenantContext.get().userId() != null
                            ? String.valueOf(com.custoking.ims.schoolcoreservice.security.TenantContext.get().userId())
                            : null)
                    .update();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // Backstop for the (school_id, admission_no) unique constraint.
            throw new IllegalArgumentException("Admission Number already exists");
        }
        LegacyGuardianSynchronizer.SyncResult guardianSync = guardianSynchronizer.syncFromLegacy(id);
        if (!classId.equals(str(current.get("classId"), ""))
                || !sectionId.equals(str(current.get("sectionId"), ""))) {
            enrollment.closeActiveEnrollment(id, "Placement changed from student profile edit");
            enrollment.recordEnrollment(id, studentSchool, str(current.get("academicYearId"), structure.currentAcademicYearId(studentSchool)),
                    classId, sectionId, str(request.get("rollNo"), ""), "ACTIVE",
                    "TRANSFERRED", "STUDENT_UPDATE", String.valueOf(id));
        } else {
            enrollment.refreshActiveEnrollmentRollNo(id, str(request.get("rollNo"), ""));
        }
        reviewInvalidation.invalidateProfile(id);
        enrollment.emitStudentUpserted(id);
        guardianSync.affectedStudentIds().stream()
                .filter(affectedStudentId -> !affectedStudentId.equals(id))
                .forEach(affectedStudentId ->
                        reviewInvalidation.invalidateProfile(affectedStudentId));
        guardianSync.projectionChangedStudentIds().stream()
                .filter(affectedStudentId -> !affectedStudentId.equals(id))
                .forEach(enrollment::emitStudentUpserted);
        return studentDetail(id);
    }

    public Long schoolIdForStudent(Long id) {
        return profiles.schoolIdForStudent(id);
    }

    public Optional<StudentPhotoContent> studentPhotoContent(Long id) {
        return profiles.studentPhotoContent(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> attachPhoto(Long id, byte[] data, String contentType) {
        String schoolStorageId = jdbc.sql("""
                        SELECT s.school_uid::text
                        FROM student.students st
                        JOIN tenant_school.schools s ON s.id = st.school_id
                        WHERE st.id = :id
                        """)
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        String key = photoStorage.upload(schoolStorageId, id, data, contentType);
        jdbc.sql("UPDATE student.students SET photo_url = :photoUrl, updated_at = :updatedAt WHERE id = :id")
                .param("id", id)
                .param("photoUrl", key)
                .param("updatedAt", OffsetDateTime.now())
                .update();
        reviewInvalidation.invalidatePhoto(id);
        return studentDetail(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> previewImport(Map<String, Object> request) {
        return imports.previewImport(request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> confirmImport(Map<String, Object> request) {
        return imports.confirmImport(request);
    }

    public Map<String, Object> importStatus(String jobId, Long schoolId) {
        return imports.importStatus(jobId, schoolId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> deleteStudent(Long id, String confirmationAdmissionNumber) {
        return eraseStudent(id, confirmationAdmissionNumber, null);
    }

    /**
     * Controlled restore/reconciliation entry point. The external RECONCILING epoch and pinned
     * intent are checked before exact incarnation binding; this method is not exposed by REST.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> reconcileErasure(StudentErasureJournal.IntentReference reference) {
        if (erasureJournal == null || !erasureJournal.enabled()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Erasure reconciliation is not configured");
        }
        erasureJournal.requireSourceDatabase(jdbc.sql("SELECT current_database()").query(String.class).single());
        var receipt = erasureJournal.verifyForReplay(reference);
        var restored = jdbc.sql("""
                SELECT school_id, erasure_incarnation, admission_no FROM student.students
                WHERE id=:id FOR UPDATE
                """).param("id", receipt.studentId())
                .query((rs, n) -> row("schoolId", rs.getLong("school_id"),
                        "incarnation", rs.getObject("erasure_incarnation", UUID.class),
                        "admission", rs.getString("admission_no"))).optional().orElse(null);
        if (restored == null) {
            // An absent unproven target may be a reused numericID hidden by tenant RLS. Never
            // manufacture a global platform tombstone from bare identifier equality.
            if (!matchingErasureReceipt(receipt)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Absent source requires independent incarnation coverage");
            }
            return row("id", receipt.studentId(), "deleted", true, "permanent", true, "alreadyAbsent", true);
        }
        if (!Long.valueOf(receipt.schoolId()).equals(restored.get("schoolId"))
                || !receipt.incarnation().equals(restored.get("incarnation"))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Restored student does not match the erasure incarnation");
        }
        return eraseStudent(receipt.studentId(), str(restored.get("admission"), "DELETE"), receipt);
    }

    private Map<String, Object> eraseStudent(Long id, String confirmationAdmissionNumber,
                                           StudentErasureJournal.Receipt verifiedReplay) {
        Map<String, Object> current = jdbc.sql("""
                SELECT id, school_id, admission_no, photo_url
                FROM student.students
                WHERE id = :id AND deleted_at IS NULL
                FOR UPDATE
                """)
                .param("id", id)
                .query((rs, n) -> row(
                        "id", rs.getLong("id"),
                        "schoolId", rs.getLong("school_id"),
                        "admissionNumber", rs.getString("admission_no"),
                        "photoUrl", rs.getString("photo_url")))
                .optional()
                .orElseThrow(() -> new StudentNotFoundException("Student not found"));
        String expectedAdmissionNumber = str(current.get("admissionNumber"), "DELETE").trim();
        confirmationAdmissionNumber = str(confirmationAdmissionNumber, "").trim();
        if (confirmationAdmissionNumber.isBlank()) {
            throw new IllegalArgumentException("Admission number confirmation is required");
        }
        if (!expectedAdmissionNumber.equals(confirmationAdmissionNumber)) {
            throw new IllegalArgumentException("Admission number confirmation does not match");
        }
        StudentErasureJournal.Receipt erasureReceipt = verifiedReplay;
        if (erasureReceipt == null && erasureJournal != null && erasureJournal.enabled()) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Erasure journal requires a source transaction");
            }
            erasureJournal.requireSourceDatabase(jdbc.sql("SELECT current_database()").query(String.class).single());
            UUID incarnation = jdbc.sql("SELECT erasure_incarnation FROM student.students WHERE id = :id")
                    .param("id", id).query(UUID.class).single();
            erasureReceipt = erasureJournal.claim(id, longValue(current.get("schoolId"), null), incarnation);
        }
        if (erasureReceipt != null) recordErasureReceipt(erasureReceipt);
        UUID erasureOperationId = erasureReceipt == null ? UUID.randomUUID() : erasureReceipt.operationId();
        Set<String> photoKeys = new LinkedHashSet<>();
        String currentPhoto = str(current.get("photoUrl"), "").trim();
        if (!currentPhoto.isBlank()) photoKeys.add(currentPhoto);
        photoKeys.addAll(jdbc.sql("""
                        SELECT photo_key
                        FROM (
                            SELECT prior_photo_key AS photo_key
                            FROM student.photo_import_rows WHERE student_id = :id
                            UNION
                            SELECT final_photo_key AS photo_key
                            FROM student.photo_import_rows WHERE student_id = :id
                        ) photos
                        WHERE photo_key IS NOT NULL AND photo_key <> ''
                        """)
                .param("id", id)
                .query(String.class)
                .list());
        List<String> guardianIds = jdbc.sql("""
                        SELECT guardian_id FROM student.student_guardians WHERE student_id = :id
                        """)
                .param("id", id)
                .query(String.class)
                .list();
        List<String> attendanceDailyIds = jdbc.sql("""
                        SELECT DISTINCT attendance_daily_id
                        FROM attendance.attendance_student_records
                        WHERE student_id = :id
                        """)
                .param("id", id)
                .query(String.class)
                .list();

        deleteByStudentId("attendance.absentee_notifications", id);
        deleteByStudentId("attendance.attendance_student_records", id);
        for (String attendanceDailyId : attendanceDailyIds) {
            refreshAttendanceDailyCounts(attendanceDailyId);
        }
        deleteByStudentId("fee.payment_records", id);
        deleteByStudentId("fee.fee_assignments", id);
        deleteByStudentId("student.student_review_items", id);
        deleteByStudentId("student.photo_import_rows", id);
        jdbc.sql("DELETE FROM student.import_rows WHERE applied_student_id = :id")
                .param("id", id).update();
        deleteByStudentId("student.student_promotion_batch_items", id);
        deleteByStudentId("student.student_enrollments", id);
        deleteByStudentId("student.student_consent_events", id);
        deleteByStudentId("student.student_guardians", id);
        int deleted = jdbc.sql("DELETE FROM student.students WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).update();
        if (deleted != 1) {
            throw new StudentNotFoundException("Student not found");
        }
        for (String guardianId : guardianIds) {
            jdbc.sql("""
                            DELETE FROM student.guardians g
                            WHERE g.id = :guardianId
                              AND NOT EXISTS (
                                  SELECT 1 FROM student.student_guardians sg WHERE sg.guardian_id = g.id
                              )
                            """)
                    .param("guardianId", guardianId)
                    .update();
        }
        Long schoolId = longValue(current.get("schoolId"), null);
        Map<String, Object> deletionEvidence = row("id", id, "schoolId", schoolId);
        if (erasureReceipt != null) deletionEvidence.putAll(erasureReceipt.eventEvidence());
        outbox.append("student.deleted.v1", "StudentDeleted:" + id, "Student", String.valueOf(id),
                schoolId, deletionEvidence);
        enqueuePhotoCleanup(erasureOperationId, schoolId, id, photoKeys);
        return row("id", id, "deleted", true, "permanent", true);
    }

    private void deleteByStudentId(String qualifiedTable, Long studentId) {
        // All callers pass compile-time constants; never accept a request value as a table name.
        jdbc.sql("DELETE FROM " + qualifiedTable + " WHERE student_id = :id")
                .param("id", studentId)
                .update();
    }

    private void recordErasureReceipt(StudentErasureJournal.Receipt receipt) {
        jdbc.sql("""
                INSERT INTO student.erasure_journal_receipts
                    (intent_id, operation_id, student_id, school_id, student_incarnation,
                     source_lineage_id, restore_epoch, journal_object, journal_generation, journal_sha256)
                VALUES (:intent, :operation, :student, :school, :incarnation,
                        :lineage, :epoch, :object, :generation, :hash)
                ON CONFLICT (intent_id) DO NOTHING
                """)
                .param("intent", receipt.intentId()).param("operation", receipt.operationId())
                .param("student", receipt.studentId()).param("school", receipt.schoolId())
                .param("incarnation", receipt.incarnation()).param("lineage", receipt.sourceLineageId())
                .param("epoch", receipt.restoreEpoch()).param("object", receipt.object())
                .param("generation", receipt.generation()).param("hash", receipt.sha256()).update();
        if (!matchingErasureReceipt(receipt)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Erasure receipt binding does not match");
        }
    }

    private boolean matchingErasureReceipt(StudentErasureJournal.Receipt receipt) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM student.erasure_journal_receipts
                  WHERE intent_id=:intent AND operation_id=:operation AND student_id=:student AND school_id=:school
                    AND student_incarnation=:incarnation AND source_lineage_id=:lineage AND restore_epoch=:epoch
                    AND journal_object=:object AND journal_generation=:generation AND journal_sha256=:hash)
                """).param("intent", receipt.intentId()).param("operation", receipt.operationId())
                .param("student", receipt.studentId()).param("school", receipt.schoolId())
                .param("incarnation", receipt.incarnation()).param("lineage", receipt.sourceLineageId())
                .param("epoch", receipt.restoreEpoch()).param("object", receipt.object())
                .param("generation", receipt.generation()).param("hash", receipt.sha256()).query(Boolean.class).single();
    }

    private void refreshAttendanceDailyCounts(String attendanceDailyId) {
        jdbc.sql("""
                        UPDATE attendance.attendance_daily d
                        SET total_enrolled = counts.total_enrolled,
                            present_count = counts.present_count,
                            absent_count = counts.absent_count,
                            late_count = counts.late_count,
                            leave_count = counts.leave_count,
                            updated_at = now()
                        FROM (
                            SELECT count(*)::integer AS total_enrolled,
                                   count(*) FILTER (WHERE status = 'PRESENT')::integer AS present_count,
                                   count(*) FILTER (WHERE status = 'ABSENT')::integer AS absent_count,
                                   count(*) FILTER (WHERE status = 'LATE')::integer AS late_count,
                                   count(*) FILTER (WHERE status = 'LEAVE')::integer AS leave_count
                            FROM attendance.attendance_student_records
                            WHERE attendance_daily_id = :attendanceDailyId
                        ) counts
                        WHERE d.id = :attendanceDailyId
                        """)
                .param("attendanceDailyId", attendanceDailyId)
                .update();
    }

    private void enqueuePhotoCleanup(UUID operation, Long schoolId, Long studentId, Set<String> photoKeys) {
        if (photoKeys.isEmpty()) return;
        String folder = structure.schoolStorageId(schoolId);
        var queue = new com.custoking.ims.schoolcoreservice.outbox.PhotoCleanupQueue(jdbc);
        for (String stored : photoKeys) photoStorage.cleanupTarget(stored, folder, studentId)
                .ifPresent(target -> queue.enqueue(operation, schoolId, studentId, target));
    }

    public Map<String, Object> studentHistory(Long id) {
        return profiles.studentHistory(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> createPromotionBatch(Map<String, Object> request) {
        return promotions.createPromotionBatch(request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> updatePromotionBatchItem(String batchId, String itemId, Map<String, Object> request) {
        return promotions.updatePromotionBatchItem(batchId, itemId, request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> applyPromotionBatch(String batchId) {
        return promotions.applyPromotionBatch(batchId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiateIdCardReview(Map<String, Object> request) {
        return reviews.initiateIdCardReview(request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiateFullNameVerification(Map<String, Object> request) {
        return reviews.initiateFullNameVerification(request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiateProfileVerification(Map<String, Object> request) {
        return reviews.initiateProfileVerification(request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> initiatePhotoVerification(Map<String, Object> request) {
        return reviews.initiatePhotoVerification(request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> verifyStudentProfile(Long studentId, Long schoolId, Long actorId) {
        return reviews.verifyStudentProfile(studentId, schoolId, actorId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> verifyStudentPhoto(Long studentId, Long schoolId, Long actorId) {
        return reviews.verifyStudentPhoto(studentId, schoolId, actorId);
    }

    public Map<String, Object> studentVerificationSummary(Long studentId, Long schoolId) {
        return reviews.studentVerificationSummary(studentId, schoolId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> updateReviewItem(String itemId, Map<String, Object> request) {
        return reviews.updateReviewItem(itemId, request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> verifyFullName(String itemId, Map<String, Object> request) {
        return reviews.verifyFullName(itemId, request);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> completeCampaign(String campaignId, Long actorId) {
        return reviews.completeCampaign(campaignId, actorId);
    }

    public List<ImportBatchRow> importBatches(Long schoolId, int limit) {
        return imports.importBatches(schoolId, limit);
    }

    /**
     * Returns bounded, PII-free operational usage facts derived from the durable import ledger.
     * These counts support noisy-tenant analysis and cost allocation; they are not an exact GCP bill.
     */
    public List<ImportUsageDailyRow> importUsageDaily(Long schoolId, int days, int limit) {
        return imports.importUsageDaily(schoolId, days, limit);
    }

    public List<ImportRow> importRows(Long schoolId, String batchId, String status, int limit) {
        return imports.importRows(schoolId, batchId, status, limit);
    }

    public List<ReviewCampaignRow> reviewCampaigns(Long schoolId, String reviewType, String status, int limit) {
        return reviews.reviewCampaigns(schoolId, reviewType, status, limit);
    }

    public Map<String, Object> idCardReviewStatus(Long schoolId) {
        return reviews.idCardReviewStatus(schoolId);
    }

    public Map<String, Object> fullNameVerificationStatus(Long schoolId) {
        return reviews.fullNameVerificationStatus(schoolId);
    }

    public Map<String, Object> profileVerificationStatus(Long schoolId) {
        return reviews.profileVerificationStatus(schoolId);
    }

    public Map<String, Object> photoVerificationStatus(Long schoolId) {
        return reviews.photoVerificationStatus(schoolId);
    }

    public List<ReviewItemRow> reviewItems(String campaignId, Long schoolId, String status, int limit) {
        return reviews.reviewItems(campaignId, schoolId, status, limit);
    }

    public Map<String, Object> campaignReviewItems(
            Long schoolId, String campaignId, String status, String classId, String sectionId, int page, int size) {
        return reviews.campaignReviewItems(schoolId, campaignId, status, classId, sectionId, page, size);
    }

    public record StudentRow(
            Long id,
            String admissionNo,
            String rollNo,
            String boardRegNo,
            String fullName,
            LocalDate dob,
            String gender,
            String fatherName,
            String fatherContact,
            String motherName,
            String phone,
            String address,
            String houseNumber,
            String street,
            String locality,
            String city,
            String state,
            String pinCode,
            String photoUrl,
            String feeStatus,
            Double attendancePercent,
            OffsetDateTime importedAt,
            String importBatchId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Long schoolId,
            String classId,
            String sectionId,
            String academicYearId,
            OffsetDateTime deletedAt) {

        public StudentRow withPhotoUrl(String newPhotoUrl) {
            return new StudentRow(id, admissionNo, rollNo, boardRegNo, fullName, dob, gender,
                    fatherName, fatherContact, motherName, phone, address, houseNumber, street,
                    locality, city, state, pinCode, newPhotoUrl, feeStatus, attendancePercent,
                    importedAt, importBatchId, createdAt, updatedAt, schoolId, classId, sectionId,
                    academicYearId, deletedAt);
        }
    }

    public record StudentPhotoContent(byte[] data, String contentType) {}

    public record ImportBatchRow(
            String id,
            String fileToken,
            String jobId,
            Integer totalRows,
            Integer validCount,
            Integer errorCount,
            Integer warningCount,
            String status,
            Integer pct,
            Integer inserted,
            Integer skipped,
            String skippedJson,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {}

    public record ImportUsageDailyRow(
            Long schoolId,
            LocalDate usageDate,
            Long previewedBatches,
            Long completedBatches,
            Long unfinishedBatches,
            Long attemptedRows,
            Long insertedRows,
            Long skippedRows,
            Long sourceBytes) {}

    public record ImportRow(
            String id,
            Integer rowNo,
            String name,
            String className,
            String sectionName,
            String admissionNo,
            String phone,
            String status,
            String message,
            String rawJson,
            String normalizedJson,
            String batchId) {}

    public record ReviewCampaignRow(
            String id,
            Long schoolId,
            String academicYearId,
            String reviewType,
            String title,
            String status,
            String verifier,
            Long initiatedBy,
            OffsetDateTime initiatedAt,
            LocalDate dueDate,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record ReviewItemRow(
            String id,
            String campaignId,
            Long studentId,
            Long schoolId,
            Long assignedToUserId,
            String status,
            Boolean verifiedPhoto,
            Boolean verifiedFullName,
            Boolean verifiedAdmissionNo,
            Boolean verifiedClassSection,
            Boolean verifiedRollNo,
            Boolean verifiedFatherName,
            Boolean verifiedFatherContact,
            Boolean verifiedAddress,
            Boolean verifiedBloodGroup,
            String currentFullName,
            String suggestedFullName,
            Boolean parentConfirmed,
            Boolean teacherConfirmed,
            Boolean correctionRequested,
            String correctionNotes,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    private Map<String, Object> studentDetail(Long id) {
        return jdbc.sql("""
                SELECT s.id, s.admission_no, s.roll_no, s.board_reg_no, s.full_name, s.dob, s.gender,
                       s.father_name, s.father_contact, s.mother_name, s.phone, s.address,
                       s.house_number, s.street, s.locality, s.city, s.state, s.pin_code, s.photo_url,
                       s.fee_status, s.attendance_percent, s.school_id, s.class_id, s.section_id, s.academic_year_id,
                       c.name AS class_name, c.sort_order AS class_sort_order, sec.name AS section_name, y.label AS academic_year
                FROM student.students s
                JOIN tenant_school.school_classes c ON c.id = s.class_id
                JOIN tenant_school.school_sections sec ON sec.id = s.section_id
                JOIN tenant_school.academic_years y ON y.id = s.academic_year_id
                WHERE s.id = :id AND s.deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, rowNum) -> {
                    String fullName = rs.getString("full_name");
                    String className = rs.getString("class_name");
                    String sectionName = rs.getString("section_name");
                    String initials = Arrays.stream((fullName == null ? "Student" : fullName).split(" "))
                            .filter(value -> !value.isBlank())
                            .limit(2)
                            .map(value -> value.substring(0, 1).toUpperCase(Locale.ROOT))
                            .collect(Collectors.joining());
                    return row(
                            "id", rs.getLong("id"),
                            "name", fullName,
                            "fullName", fullName,
                            "avatarInitials", initials,
                            "photoUrl", photoReference(rs.getLong("id"), rs.getString("photo_url")),
                            "className", className,
                            "classId", rs.getString("class_id"),
                            "classSortOrder", rs.getInt("class_sort_order"),
                            "sectionName", sectionName,
                            "sectionId", rs.getString("section_id"),
                            "classSection", className.replace("Class ", "") + (sectionName == null || sectionName.isBlank() ? "" : "-" + sectionName),
                            "academicYear", rs.getString("academic_year"),
                            "academicYearId", rs.getString("academic_year_id"),
                            "schoolId", rs.getLong("school_id"),
                            "admissionNumber", rs.getString("admission_no"),
                            "rollNo", rs.getString("roll_no"),
                            "fatherName", rs.getString("father_name"),
                            "fatherContact", rs.getString("father_contact"),
                            "feeStatus", rs.getString("fee_status"),
                            "attendancePercent", rs.getDouble("attendance_percent"),
                            "dateOfBirth", rs.getObject("dob", LocalDate.class) == null ? null : rs.getObject("dob", LocalDate.class).toString(),
                            "gender", rs.getString("gender"),
                            "boardRegistrationNumber", rs.getString("board_reg_no"),
                            "motherName", rs.getString("mother_name"),
                            "address", row(
                                    "houseNumber", rs.getString("house_number"),
                                    "street", rs.getString("street"),
                                    "locality", rs.getString("locality"),
                                    "city", rs.getString("city"),
                                    "state", rs.getString("state"),
                                    "pinCode", rs.getString("pin_code"),
                                    "full", rs.getString("address")));
                })
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
    }

    public Map<String, Object> promotionBatchDetail(String batchId) {
        return promotions.promotionBatchDetail(batchId);
    }

    public Long schoolIdForReviewItem(String itemId) {
        return reviews.schoolIdForReviewItem(itemId);
    }

    public Long schoolIdForCampaign(String campaignId) {
        return reviews.schoolIdForCampaign(campaignId);
    }

}
