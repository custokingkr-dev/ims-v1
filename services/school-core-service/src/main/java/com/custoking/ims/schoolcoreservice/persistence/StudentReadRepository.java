package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class StudentReadRepository {

    private static final String SELECT = """
            SELECT id, admission_no, roll_no, board_reg_no, full_name, dob, gender,
                   father_name, father_contact, mother_name, phone, address,
                   house_number, street, locality, city, state, pin_code, photo_url,
                   fee_status, attendance_percent, imported_at, import_batch_id,
                   created_at, updated_at, school_id, class_id, section_id, academic_year_id,
                   deleted_at
            FROM student.students
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StudentReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

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
        return spec.query(StudentRow.class).list();
    }

    public Optional<StudentRow> find(Long id) {
        return jdbc.sql(SELECT + " WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(StudentRow.class)
                .optional();
    }

    public Map<String, Object> workspaceStudents(Long schoolId, String className, String sectionName,
                                                 String feeStatus, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 500));
        List<Map<String, Object>> all = workspaceStudentRows(schoolId, className, sectionName, feeStatus);
        List<Map<String, Object>> items = all.stream()
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .toList();
        long filteredSections = all.stream()
                .map(row -> str(row.get("className"), "") + "-" + str(row.get("sectionName"), ""))
                .distinct()
                .count();
        return row("items", items,
                "page", safePage,
                "size", safeSize,
                "totalItems", all.size(),
                "totalPages", (int) Math.ceil(all.size() / (double) safeSize),
                "filteredCount", all.size(),
                "filteredSections", filteredSections,
                "filters", row("classes", classesForSchool(schoolId),
                        "sections", sectionNamesForSchool(schoolId),
                        "feeStatuses", List.of("Paid", "Overdue", "Pending", "Partial")));
    }

    public List<Map<String, Object>> workspaceStudentRows(Long schoolId, String className, String sectionName,
                                                          String feeStatus) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.full_name, s.admission_no, s.roll_no, s.board_reg_no, s.dob, s.gender,
                       s.father_name, s.father_contact, s.mother_name, s.phone, s.address,
                       s.house_number, s.street, s.locality, s.city, s.state, s.pin_code,
                       s.photo_url, s.fee_status, s.attendance_percent, s.school_id,
                       sc.id AS class_id, sc.name AS class_name, sc.sort_order,
                       ss.id AS section_id, ss.name AS section_name,
                       ay.label AS academic_year_label
                FROM student.students s
                JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                JOIN tenant_school.school_sections ss ON ss.id = s.section_id
                JOIN tenant_school.academic_years ay ON ay.id = s.academic_year_id
                WHERE s.deleted_at IS NULL
                  AND s.school_id = :schoolId
                """);
        if (!blankOrAll(className)) sql.append(" AND lower(sc.name) = lower(:className)");
        if (!blankOrAll(sectionName)) sql.append(" AND lower(ss.name) = lower(:sectionName)");
        if (!blankOrAll(feeStatus)) sql.append(" AND lower(s.fee_status) = lower(:feeStatus)");
        sql.append(" ORDER BY lower(s.full_name), s.id");

        var spec = jdbc.sql(sql.toString()).param("schoolId", schoolId);
        if (!blankOrAll(className)) spec = spec.param("className", className);
        if (!blankOrAll(sectionName)) spec = spec.param("sectionName", sectionName);
        if (!blankOrAll(feeStatus)) spec = spec.param("feeStatus", feeStatus);
        return spec.query((rs, rowNum) -> {
            String fullName = str(rs.getString("full_name"), "Student");
            String classLabel = str(rs.getString("class_name"), "");
            String sectionLabel = str(rs.getString("section_name"), "");
            Double attendance = rs.getObject("attendance_percent", Double.class);
            return row("id", rs.getLong("id"),
                    "name", fullName,
                    "fullName", fullName,
                    "avatarInitials", initials(fullName),
                    "photoUrl", rs.getString("photo_url"),
                    "className", classLabel,
                    "sectionName", sectionLabel,
                    "classSection", classLabel.replace("Class ", "") + (sectionLabel.isBlank() ? "" : "-" + sectionLabel),
                    "academicYear", rs.getString("academic_year_label"),
                    "admissionNumber", rs.getString("admission_no"),
                    "rollNo", rs.getString("roll_no"),
                    "fatherName", rs.getString("father_name"),
                    "fatherContact", rs.getString("father_contact"),
                    "feeStatus", rs.getString("fee_status"),
                    "attendancePercent", attendance == null ? 0 : round(attendance));
        }).list();
    }

    public Map<String, Object> workspaceStudentDetail(Long id) {
        return jdbc.sql("""
                SELECT s.id, s.full_name, s.admission_no, s.roll_no, s.board_reg_no, s.dob, s.gender,
                       s.father_name, s.father_contact, s.mother_name, s.phone, s.address,
                       s.house_number, s.street, s.locality, s.city, s.state, s.pin_code,
                       s.photo_url, s.fee_status, s.attendance_percent, s.school_id,
                       sc.name AS class_name, ss.name AS section_name, ay.label AS academic_year_label
                FROM student.students s
                JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                JOIN tenant_school.school_sections ss ON ss.id = s.section_id
                JOIN tenant_school.academic_years ay ON ay.id = s.academic_year_id
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
                            "photoUrl", rs.getString("photo_url"),
                            "className", classLabel,
                            "sectionName", sectionLabel,
                            "classSection", classLabel.replace("Class ", "") + (sectionLabel.isBlank() ? "" : "-" + sectionLabel),
                            "academicYear", rs.getString("academic_year_label"),
                            "admissionNumber", rs.getString("admission_no"),
                            "rollNo", rs.getString("roll_no"),
                            "fatherName", rs.getString("father_name"),
                            "fatherContact", rs.getString("father_contact"),
                            "feeStatus", rs.getString("fee_status"),
                            "attendancePercent", attendance == null ? 0 : round(attendance));
                    LinkedHashMap<String, Object> detail = new LinkedHashMap<>(base);
                    detail.put("schoolId", rs.getLong("school_id"));
                    detail.put("dateOfBirth", rs.getObject("dob", LocalDate.class) == null ? null : rs.getObject("dob", LocalDate.class).toString());
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
                    return detail;
                })
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
    }

    @Transactional
    public Map<String, Object> createStudent(Map<String, Object> request) {
        String admissionNo = requireText(firstPresent(request, "admissionNumber", "admissionNo"), "Admission Number is mandatory");
        Long duplicate = jdbc.sql("SELECT COUNT(*) FROM student.students WHERE lower(admission_no) = lower(:admissionNo)")
                .param("admissionNo", admissionNo)
                .query(Long.class)
                .single();
        if (duplicate != null && duplicate > 0) {
            throw new IllegalArgumentException("Admission Number already exists");
        }
        String fullName = requireText(request.get("fullName"), "Full name is mandatory");
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId == null) {
            throw new IllegalArgumentException("School not found");
        }
        requireSchool(schoolId);
        String className = str(firstPresent(request, "gradeLevel", "className"), "Class 9");
        Map<String, Object> schoolClass = classByName(className)
                .or(() -> classById(String.valueOf(classSortOrder(str(request.get("gradeLevel"), "9")))))
                .orElseThrow(() -> new IllegalArgumentException("Class not found"));
        Map<String, Object> section = getOrCreateSection(
                schoolId, String.valueOf(schoolClass.get("id")), str(request.get("sectionName"), "A"));
        String academicYearId = currentAcademicYearId();
        OffsetDateTime now = OffsetDateTime.now();
        Long id = jdbc.sql("""
                INSERT INTO student.students(admission_no, roll_no, board_reg_no, full_name, dob, gender,
                                     father_name, father_contact, mother_name, phone, address,
                                     house_number, street, locality, city, state, pin_code, photo_url,
                                     fee_status, attendance_percent, created_at, updated_at,
                                     school_id, class_id, section_id, academic_year_id, version)
                VALUES (:admissionNo, :rollNo, :boardRegNo, :fullName, :dob, :gender,
                        :fatherName, :fatherContact, :motherName, :phone, :address,
                        :houseNumber, :street, :locality, :city, :state, :pinCode, :photoUrl,
                        'Pending', 0, :createdAt, :updatedAt,
                        :schoolId, :classId, :sectionId, :academicYearId, 0)
                RETURNING id
                """)
                .param("admissionNo", admissionNo)
                .param("rollNo", str(request.get("rollNo"), String.valueOf(countBySection(String.valueOf(section.get("id"))) + 1)))
                .param("boardRegNo", str(request.get("boardRegistrationNumber"), ""))
                .param("fullName", fullName)
                .param("dob", parseDate(str(request.get("dateOfBirth"), "")))
                .param("gender", str(request.get("gender"), "Unspecified"))
                .param("fatherName", str(request.get("fatherName"), ""))
                .param("fatherContact", str(firstPresent(request, "fatherContactNumber", "fatherContact"), ""))
                .param("motherName", str(request.get("motherName"), ""))
                .param("phone", str(request.get("phone"), str(firstPresent(request, "fatherContactNumber", "fatherContact"), "")))
                .param("address", joinAddress(
                        str(request.get("houseNumber"), ""),
                        str(request.get("street"), ""),
                        str(request.get("locality"), ""),
                        str(request.get("city"), "Hyderabad"),
                        str(request.get("state"), "Telangana"),
                        str(request.get("pinCode"), "")))
                .param("houseNumber", str(request.get("houseNumber"), ""))
                .param("street", str(request.get("street"), ""))
                .param("locality", str(request.get("locality"), ""))
                .param("city", str(request.get("city"), "Hyderabad"))
                .param("state", str(request.get("state"), "Telangana"))
                .param("pinCode", str(request.get("pinCode"), ""))
                .param("photoUrl", str(request.get("photoUrl"), null))
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("schoolId", schoolId)
                .param("classId", schoolClass.get("id"))
                .param("sectionId", section.get("id"))
                .param("academicYearId", academicYearId)
                .query(Long.class)
                .single();
        return studentDetail(id);
    }

    @Transactional
    public Map<String, Object> attachPhoto(Long id, Map<String, Object> request) {
        find(id).orElseThrow(() -> new IllegalArgumentException("Student not found"));
        jdbc.sql("UPDATE student.students SET photo_url = :photoUrl, updated_at = :updatedAt WHERE id = :id")
                .param("id", id)
                .param("photoUrl", requireText(request.get("photoUrl"), "Photo URL is required"))
                .param("updatedAt", OffsetDateTime.now())
                .update();
        return studentDetail(id);
    }

    @Transactional
    public Map<String, Object> previewImport(Map<String, Object> request) {
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId == null) throw new IllegalArgumentException("School not found");
        requireSchool(schoolId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRows = (List<Map<String, Object>>) request.getOrDefault("rows", List.of());
        if (rawRows.size() > 500) throw new IllegalArgumentException("Maximum 500 rows per import");

        String batchId = UUID.randomUUID().toString();
        String fileToken = UUID.randomUUID().toString();
        int valid = 0;
        int errors = 0;
        int warnings = 0;
        List<Map<String, Object>> previewRows = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> normalized = normalizeImportRow(rawRows.get(i));
            ImportValidation validation = validatePreviewRow(normalized, schoolId);
            if (validation.valid()) valid++;
            if (validation.error()) errors++;
            if (validation.warning()) warnings++;
            previewRows.add(row(
                    "rowNumber", i + 1,
                    "name", normalized.get("name"),
                    "className", normalized.get("className"),
                    "sectionName", normalized.get("sectionName"),
                    "admissionNo", normalized.get("admissionNo"),
                    "phone", normalized.get("phone"),
                    "status", validation.status(),
                    "message", validation.message()));
        }
        jdbc.sql("""
                        INSERT INTO student.import_batches
                            (id, file_token, total_rows, valid_count, error_count, warning_count,
                             status, pct, inserted, skipped, created_at)
                        VALUES
                            (:id, :fileToken, :totalRows, :validCount, :errorCount, :warningCount,
                             'PREVIEWED', 0, 0, 0, :createdAt)
                        """)
                .param("id", batchId)
                .param("fileToken", fileToken)
                .param("totalRows", rawRows.size())
                .param("validCount", valid)
                .param("errorCount", errors)
                .param("warningCount", warnings)
                .param("createdAt", OffsetDateTime.now())
                .update();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> normalized = normalizeImportRow(rawRows.get(i));
            jdbc.sql("""
                            INSERT INTO student.import_rows
                                (id, row_no, name, class_name, section_name, admission_no, phone,
                                 status, message, raw_json, normalized_json, batch_id)
                            VALUES
                                (:id, :rowNo, :name, :className, :sectionName, :admissionNo, :phone,
                                 :status, :message, :rawJson, :normalizedJson, :batchId)
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("rowNo", i + 1)
                    .param("name", str(normalized.get("name"), ""))
                    .param("className", str(normalized.get("className"), ""))
                    .param("sectionName", str(normalized.get("sectionName"), ""))
                    .param("admissionNo", str(normalized.get("admissionNo"), ""))
                    .param("phone", str(normalized.get("phone"), ""))
                    .param("status", previewRows.get(i).get("status"))
                    .param("message", previewRows.get(i).get("message"))
                    .param("rawJson", toJson(rawRows.get(i)))
                    .param("normalizedJson", toJson(normalized))
                    .param("batchId", batchId)
                    .update();
        }
        return row("rows", previewRows, "fileToken", fileToken,
                "validCount", valid, "errorCount", errors, "warningCount", warnings);
    }

    @Transactional
    public Map<String, Object> confirmImport(Map<String, Object> request) {
        String fileToken = requireText(request.get("fileToken"), "Preview token not found");
        Long schoolId = longValue(request.get("schoolId"), null);
        if (schoolId == null) throw new IllegalArgumentException("School not found");
        String batchId = jdbc.sql("SELECT id FROM student.import_batches WHERE file_token = :fileToken")
                .param("fileToken", fileToken)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Preview token not found"));
        String jobId = UUID.randomUUID().toString();
        jdbc.sql("UPDATE student.import_batches SET job_id = :jobId, status = 'RUNNING', pct = 20 WHERE id = :batchId")
                .param("jobId", jobId)
                .param("batchId", batchId)
                .update();
        List<ImportRow> rows = importRows(batchId, null, 1000);
        int inserted = 0;
        int skipped = 0;
        List<Map<String, Object>> skippedRows = new ArrayList<>();
        for (ImportRow row : rows) {
            if (!"Valid".equalsIgnoreCase(row.status()) && !"Warning".equalsIgnoreCase(row.status())) {
                skipped++;
                skippedRows.add(row("rowNumber", row.rowNo(), "reason", row.message()));
                continue;
            }
            try {
                Map<String, Object> normalized = objectMapper.readValue(row.normalizedJson(), new TypeReference<>() {});
                Long studentId = insertImportedStudent(normalized, schoolId, batchId);
                jdbc.sql("UPDATE student.students SET imported_at = :importedAt, import_batch_id = :batchId WHERE id = :studentId")
                        .param("importedAt", OffsetDateTime.now())
                        .param("batchId", batchId)
                        .param("studentId", studentId)
                        .update();
                inserted++;
            } catch (Exception ex) {
                skipped++;
                skippedRows.add(row("rowNumber", row.rowNo(), "reason", ex.getMessage()));
            }
        }
        jdbc.sql("""
                        UPDATE student.import_batches
                        SET pct = 100, status = 'DONE', inserted = :inserted, skipped = :skipped,
                            completed_at = :completedAt, skipped_json = :skippedJson
                        WHERE id = :batchId
                        """)
                .param("inserted", inserted)
                .param("skipped", skipped)
                .param("completedAt", OffsetDateTime.now())
                .param("skippedJson", toJson(skippedRows))
                .param("batchId", batchId)
                .update();
        return row("batchId", batchId,
                "jobId", jobId,
                "schoolId", schoolId,
                "totalRows", rows.size(),
                "inserted", inserted,
                "skipped", skipped,
                "done", true,
                "skippedRows", skippedRows);
    }

    public Map<String, Object> importStatus(String jobId) {
        ImportBatchRow batch = jdbc.sql("""
                        SELECT id, file_token, job_id, total_rows, valid_count, error_count,
                               warning_count, status, pct, inserted, skipped, skipped_json,
                               created_at, completed_at
                        FROM student.import_batches
                        WHERE job_id = :jobId
                        """)
                .param("jobId", jobId)
                .query(ImportBatchRow.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Import job not found"));
        List<Map<String, Object>> skippedRows = List.of();
        if (batch.skippedJson() != null && !batch.skippedJson().isBlank()) {
            try {
                skippedRows = objectMapper.readValue(batch.skippedJson(), new TypeReference<>() {});
            } catch (Exception ignored) {
                skippedRows = List.of();
            }
        }
        return row("pct", batch.pct(), "done", "DONE".equalsIgnoreCase(batch.status()),
                "inserted", batch.inserted(), "skipped", batch.skipped(), "skippedRows", skippedRows);
    }

    @Transactional
    public Map<String, Object> initiateIdCardReview(Map<String, Object> request) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        Long actorId = longValue(request.get("actorId"), null);
        Long active = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM student.student_review_campaigns
                        WHERE school_id = :schoolId AND review_type = 'ID_CARD_DETAILS' AND status = 'ACTIVE'
                        """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (active != null && active > 0) {
            throw new IllegalArgumentException("An active ID Card Details review campaign already exists for this school");
        }
        String academicYearId = currentAcademicYearId();
        String academicYearLabel = jdbc.sql("SELECT label FROM tenant_school.academic_years WHERE id = :id")
                .param("id", academicYearId)
                .query(String.class)
                .optional()
                .orElse(academicYearId);
        String campaignId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO student.student_review_campaigns
                            (id, school_id, academic_year_id, review_type, title, status, initiated_by,
                             initiated_at, due_date, created_at, updated_at)
                        VALUES
                            (:id, :schoolId, :academicYearId, 'ID_CARD_DETAILS', :title, 'ACTIVE', :actorId,
                             now(), :dueDate, now(), now())
                        """)
                .param("id", campaignId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("title", "ID Card Details Review - " + academicYearLabel)
                .param("actorId", actorId)
                .param("dueDate", parseDate(str(request.get("dueDate"), "")))
                .update();
        insertReviewItems(campaignId, schoolId, stringList(request.get("classIds")),
                stringList(request.get("sectionIds")), longValue(request.get("assignedToUserId"), null));
        return idCardStatus(campaignId);
    }

    @Transactional
    public Map<String, Object> initiateFullNameVerification(Map<String, Object> request) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        Long actorId = longValue(request.get("actorId"), null);
        Long active = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM student.student_review_campaigns
                        WHERE school_id = :schoolId AND review_type = 'FULL_NAME_VERIFICATION' AND status = 'ACTIVE'
                        """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (active != null && active > 0) {
            throw new IllegalArgumentException("An active Full Name Verification campaign already exists for this school");
        }
        String academicYearId = currentAcademicYearId();
        String academicYearLabel = jdbc.sql("SELECT label FROM tenant_school.academic_years WHERE id = :id")
                .param("id", academicYearId)
                .query(String.class)
                .optional()
                .orElse(academicYearId);
        String campaignId = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO student.student_review_campaigns
                            (id, school_id, academic_year_id, review_type, title, status, verifier,
                             initiated_by, initiated_at, due_date, created_at, updated_at)
                        VALUES
                            (:id, :schoolId, :academicYearId, 'FULL_NAME_VERIFICATION', :title, 'ACTIVE', :verifier,
                             :actorId, now(), :dueDate, now(), now())
                        """)
                .param("id", campaignId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("title", "Full Name Verification - " + academicYearLabel)
                .param("verifier", str(request.get("verifier"), "TEACHER"))
                .param("actorId", actorId)
                .param("dueDate", parseDate(str(request.get("dueDate"), "")))
                .update();
        insertReviewItems(campaignId, schoolId, stringList(request.get("classIds")),
                stringList(request.get("sectionIds")), null);
        return fullNameStatus(campaignId);
    }

    @Transactional
    public Map<String, Object> updateReviewItem(String itemId, Map<String, Object> request) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        if (reviewItemDetail(itemId, schoolId).isEmpty()) {
            throw new IllegalArgumentException("Review item not found");
        }
        Map<String, Object> current = reviewItemFlags(itemId);
        boolean verifiedPhoto = boolOr(request.get("verifiedPhoto"), (Boolean) current.get("verifiedPhoto"));
        boolean verifiedFullName = boolOr(request.get("verifiedFullName"), (Boolean) current.get("verifiedFullName"));
        boolean verifiedAdmissionNo = boolOr(request.get("verifiedAdmissionNo"), (Boolean) current.get("verifiedAdmissionNo"));
        boolean verifiedClassSection = boolOr(request.get("verifiedClassSection"), (Boolean) current.get("verifiedClassSection"));
        boolean verifiedRollNo = boolOr(request.get("verifiedRollNo"), (Boolean) current.get("verifiedRollNo"));
        boolean verifiedFatherName = boolOr(request.get("verifiedFatherName"), (Boolean) current.get("verifiedFatherName"));
        boolean verifiedFatherContact = boolOr(request.get("verifiedFatherContact"), (Boolean) current.get("verifiedFatherContact"));
        boolean verifiedAddress = boolOr(request.get("verifiedAddress"), (Boolean) current.get("verifiedAddress"));
        boolean verifiedBloodGroup = boolOr(request.get("verifiedBloodGroup"), (Boolean) current.get("verifiedBloodGroup"));
        String correctionNotes = request.containsKey("correctionNotes")
                ? str(request.get("correctionNotes"), null)
                : (String) current.get("correctionNotes");
        String status = str(request.get("status"), null);
        if (status == null || status.isBlank()) {
            boolean hasCorrection = correctionNotes != null && !correctionNotes.isBlank();
            boolean allRequired = verifiedPhoto && verifiedFullName && verifiedAdmissionNo && verifiedClassSection
                    && verifiedRollNo && verifiedFatherName && verifiedFatherContact && verifiedAddress;
            status = hasCorrection ? "NEEDS_CORRECTION" : allRequired ? "COMPLETED" : (String) current.get("status");
        }
        jdbc.sql("""
                        UPDATE student.student_review_items
                        SET verified_photo = :verifiedPhoto,
                            verified_full_name = :verifiedFullName,
                            verified_admission_no = :verifiedAdmissionNo,
                            verified_class_section = :verifiedClassSection,
                            verified_roll_no = :verifiedRollNo,
                            verified_father_name = :verifiedFatherName,
                            verified_father_contact = :verifiedFatherContact,
                            verified_address = :verifiedAddress,
                            verified_blood_group = :verifiedBloodGroup,
                            correction_notes = :correctionNotes,
                            status = :status,
                            completed_at = CASE WHEN :status = 'COMPLETED' THEN now() ELSE completed_at END,
                            updated_at = now()
                        WHERE id = :itemId AND school_id = :schoolId
                        """)
                .param("itemId", itemId)
                .param("schoolId", schoolId)
                .param("verifiedPhoto", verifiedPhoto)
                .param("verifiedFullName", verifiedFullName)
                .param("verifiedAdmissionNo", verifiedAdmissionNo)
                .param("verifiedClassSection", verifiedClassSection)
                .param("verifiedRollNo", verifiedRollNo)
                .param("verifiedFatherName", verifiedFatherName)
                .param("verifiedFatherContact", verifiedFatherContact)
                .param("verifiedAddress", verifiedAddress)
                .param("verifiedBloodGroup", verifiedBloodGroup)
                .param("correctionNotes", correctionNotes)
                .param("status", status)
                .update();
        return reviewItemDetail(itemId, schoolId).orElseThrow();
    }

    @Transactional
    public Map<String, Object> verifyFullName(String itemId, Map<String, Object> request) {
        Long schoolId = requireLong(request.get("schoolId"), "schoolId is required");
        Map<String, Object> detail = reviewItemDetail(itemId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Review item not found"));
        String verifier = jdbc.sql("""
                        SELECT c.verifier
                        FROM student.student_review_items i
                        JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                        WHERE i.id = :itemId
                        """)
                .param("itemId", itemId)
                .query(String.class)
                .optional()
                .orElse("TEACHER");
        boolean confirmed = Boolean.TRUE.equals(request.get("confirmed"));
        if (confirmed) {
            boolean parentConfirmed = Boolean.TRUE.equals(detail.get("parentConfirmed"));
            boolean teacherConfirmed = Boolean.TRUE.equals(detail.get("teacherConfirmed"));
            if ("PARENT".equals(verifier)) parentConfirmed = true;
            else teacherConfirmed = true;
            boolean done = switch (verifier == null ? "TEACHER" : verifier) {
                case "PARENT" -> parentConfirmed;
                case "BOTH" -> parentConfirmed && teacherConfirmed;
                default -> teacherConfirmed;
            };
            jdbc.sql("""
                            UPDATE student.student_review_items
                            SET parent_confirmed = :parentConfirmed,
                                teacher_confirmed = :teacherConfirmed,
                                status = CASE WHEN :done THEN 'COMPLETED' ELSE status END,
                                completed_at = CASE WHEN :done THEN now() ELSE completed_at END,
                                updated_at = now()
                            WHERE id = :itemId AND school_id = :schoolId
                            """)
                    .param("itemId", itemId)
                    .param("schoolId", schoolId)
                    .param("parentConfirmed", parentConfirmed)
                    .param("teacherConfirmed", teacherConfirmed)
                    .param("done", done)
                    .update();
        } else {
            jdbc.sql("""
                            UPDATE student.student_review_items
                            SET correction_requested = true,
                                suggested_full_name = :suggestedFullName,
                                correction_notes = :correctionNotes,
                                status = 'NEEDS_CORRECTION',
                                updated_at = now()
                            WHERE id = :itemId AND school_id = :schoolId
                            """)
                    .param("itemId", itemId)
                    .param("schoolId", schoolId)
                    .param("suggestedFullName", str(request.get("suggestedFullName"), null))
                    .param("correctionNotes", str(request.get("correctionNotes"), null))
                    .update();
        }
        return reviewItemDetail(itemId, schoolId).orElseThrow();
    }

    public long count(Long schoolId) {
        if (schoolId == null) {
            return jdbc.sql("SELECT count(*) FROM student.students WHERE deleted_at IS NULL")
                    .query(Long.class)
                    .single();
        }
        return jdbc.sql("SELECT count(*) FROM student.students WHERE deleted_at IS NULL AND school_id = :schoolId")
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
    }

    public List<ImportBatchRow> importBatches(int limit) {
        return jdbc.sql("""
                SELECT id, file_token, job_id, total_rows, valid_count, error_count,
                       warning_count, status, pct, inserted, skipped, skipped_json,
                       created_at, completed_at
                FROM student.import_batches
                ORDER BY created_at DESC NULLS LAST
                LIMIT :limit
                """).param("limit", Math.max(1, Math.min(limit, 500)))
                .query(ImportBatchRow.class)
                .list();
    }

    public List<ImportRow> importRows(String batchId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, row_no, name, class_name, section_name, admission_no, phone,
                       status, message, raw_json, normalized_json, batch_id
                FROM student.import_rows
                WHERE 1=1
                """);
        if (batchId != null && !batchId.isBlank()) sql.append(" AND batch_id = :batchId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY row_no ASC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 1000)));
        if (batchId != null && !batchId.isBlank()) spec = spec.param("batchId", batchId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(ImportRow.class).list();
    }

    public List<ReviewCampaignRow> reviewCampaigns(Long schoolId, String reviewType, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, academic_year_id, review_type, title, status,
                       verifier, initiated_by, initiated_at, due_date, created_at, updated_at
                FROM student.student_review_campaigns
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (reviewType != null && !reviewType.isBlank()) sql.append(" AND review_type = :reviewType");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (reviewType != null && !reviewType.isBlank()) spec = spec.param("reviewType", reviewType);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(ReviewCampaignRow.class).list();
    }

    public Map<String, Object> idCardReviewStatus(Long schoolId) {
        return activeReviewCampaign(schoolId, "ID_CARD_DETAILS")
                .map(this::idCardStatus)
                .orElseGet(() -> row(
                        "campaignId", null,
                        "totalStudents", 0,
                        "completed", 0,
                        "pending", 0,
                        "needsCorrection", 0,
                        "completionPercent", 0.0,
                        "classWiseStatus", List.of()));
    }

    public Map<String, Object> fullNameVerificationStatus(Long schoolId) {
        return activeReviewCampaign(schoolId, "FULL_NAME_VERIFICATION")
                .map(this::fullNameStatus)
                .orElseGet(() -> row(
                        "campaignId", null,
                        "totalStudents", 0,
                        "confirmed", 0,
                        "correctionRequested", 0,
                        "pending", 0,
                        "completionPercent", 0.0));
    }

    public List<ReviewItemRow> reviewItems(String campaignId, Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, campaign_id, student_id, school_id, assigned_to_user_id, status,
                       verified_photo, verified_full_name, verified_admission_no,
                       verified_class_section, verified_roll_no, verified_father_name,
                       verified_father_contact, verified_address, verified_blood_group,
                       current_full_name, suggested_full_name, parent_confirmed,
                       teacher_confirmed, correction_requested, correction_notes,
                       completed_at, created_at, updated_at
                FROM student.student_review_items
                WHERE 1=1
                """);
        if (campaignId != null && !campaignId.isBlank()) sql.append(" AND campaign_id = :campaignId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY updated_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 1000)));
        if (campaignId != null && !campaignId.isBlank()) spec = spec.param("campaignId", campaignId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(ReviewItemRow.class).list();
    }

    public Map<String, Object> campaignReviewItems(
            Long schoolId, String campaignId, String status, String classId, String sectionId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));
        StringBuilder where = new StringBuilder("""
                FROM student.student_review_items i
                JOIN student.students s ON s.id = i.student_id
                JOIN tenant_school.school_classes c ON c.id = s.class_id
                JOIN tenant_school.school_sections sec ON sec.id = s.section_id
                WHERE i.campaign_id = :campaignId
                  AND i.school_id = :schoolId
                """);
        if (status != null && !status.isBlank()) where.append(" AND i.status = :status");
        if (classId != null && !classId.isBlank()) where.append(" AND s.class_id = :classId");
        if (sectionId != null && !sectionId.isBlank()) where.append(" AND s.section_id = :sectionId");

        var countSpec = jdbc.sql("SELECT COUNT(*) " + where)
                .param("campaignId", campaignId)
                .param("schoolId", schoolId);
        var listSpec = jdbc.sql("""
                        SELECT i.id, i.student_id, s.full_name, s.admission_no, c.name AS class_name,
                               sec.name AS section_name, i.current_full_name, i.suggested_full_name,
                               i.status, i.verified_photo, i.verified_full_name, i.verified_admission_no,
                               i.verified_class_section, i.verified_roll_no, i.verified_father_name,
                               i.verified_father_contact, i.verified_address, i.verified_blood_group,
                               i.parent_confirmed, i.teacher_confirmed, i.correction_requested,
                               i.correction_notes, i.completed_at
                        """ + where + """
                        ORDER BY s.full_name ASC, i.updated_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("campaignId", campaignId)
                .param("schoolId", schoolId)
                .param("limit", safeSize)
                .param("offset", safePage * safeSize);
        if (status != null && !status.isBlank()) {
            countSpec = countSpec.param("status", status);
            listSpec = listSpec.param("status", status);
        }
        if (classId != null && !classId.isBlank()) {
            countSpec = countSpec.param("classId", classId);
            listSpec = listSpec.param("classId", classId);
        }
        if (sectionId != null && !sectionId.isBlank()) {
            countSpec = countSpec.param("sectionId", sectionId);
            listSpec = listSpec.param("sectionId", sectionId);
        }

        Long total = countSpec.query(Long.class).single();
        List<Map<String, Object>> content = listSpec.query((rs, rowNum) -> row(
                        "itemId", rs.getString("id"),
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("full_name"),
                        "admissionNo", rs.getString("admission_no"),
                        "className", rs.getString("class_name"),
                        "sectionName", rs.getString("section_name"),
                        "currentFullName", rs.getString("current_full_name"),
                        "suggestedFullName", rs.getString("suggested_full_name"),
                        "status", rs.getString("status"),
                        "verifiedPhoto", rs.getBoolean("verified_photo"),
                        "verifiedFullName", rs.getBoolean("verified_full_name"),
                        "verifiedAdmissionNo", rs.getBoolean("verified_admission_no"),
                        "verifiedClassSection", rs.getBoolean("verified_class_section"),
                        "verifiedRollNo", rs.getBoolean("verified_roll_no"),
                        "verifiedFatherName", rs.getBoolean("verified_father_name"),
                        "verifiedFatherContact", rs.getBoolean("verified_father_contact"),
                        "verifiedAddress", rs.getBoolean("verified_address"),
                        "verifiedBloodGroup", rs.getBoolean("verified_blood_group"),
                        "parentConfirmed", rs.getBoolean("parent_confirmed"),
                        "teacherConfirmed", rs.getBoolean("teacher_confirmed"),
                        "correctionRequested", rs.getBoolean("correction_requested"),
                        "correctionNotes", rs.getString("correction_notes"),
                        "completedAt", rs.getObject("completed_at", OffsetDateTime.class)))
                .list();
        return row("content", content, "totalElements", total == null ? 0 : total,
                "page", safePage, "size", safeSize);
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
    }

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
                            "photoUrl", rs.getString("photo_url"),
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

    private Optional<Map<String, Object>> classByName(String className) {
        return jdbc.sql("SELECT id, name, sort_order FROM tenant_school.school_classes WHERE lower(name) = lower(:name)")
                .param("name", className)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name"), "sortOrder", rs.getInt("sort_order")))
                .optional();
    }

    private void insertReviewItems(String campaignId, Long schoolId, List<String> classIds, List<String> sectionIds,
                                   Long assignedToUserId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, full_name
                FROM student.students
                WHERE school_id = :schoolId AND deleted_at IS NULL
                """);
        if (!classIds.isEmpty()) sql.append(" AND class_id IN (:classIds)");
        if (!sectionIds.isEmpty()) sql.append(" AND section_id IN (:sectionIds)");
        sql.append(" ORDER BY full_name");
        var spec = jdbc.sql(sql.toString()).param("schoolId", schoolId);
        if (!classIds.isEmpty()) spec = spec.param("classIds", classIds);
        if (!sectionIds.isEmpty()) spec = spec.param("sectionIds", sectionIds);
        List<Map<String, Object>> students = spec.query((rs, rowNum) ->
                row("id", rs.getLong("id"), "fullName", rs.getString("full_name"))).list();
        for (Map<String, Object> student : students) {
            jdbc.sql("""
                            INSERT INTO student.student_review_items
                                (id, campaign_id, student_id, school_id, assigned_to_user_id, status,
                                 current_full_name, created_at, updated_at)
                            VALUES
                                (:id, :campaignId, :studentId, :schoolId, :assignedToUserId, 'PENDING',
                                 :currentFullName, now(), now())
                            ON CONFLICT (campaign_id, student_id) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("campaignId", campaignId)
                    .param("studentId", student.get("id"))
                    .param("schoolId", schoolId)
                    .param("assignedToUserId", assignedToUserId)
                    .param("currentFullName", student.get("fullName"))
                    .update();
        }
    }

    private Map<String, Object> idCardStatus(String campaignId) {
        ReviewCounts counts = reviewCounts(campaignId);
        return row("campaignId", campaignId,
                "totalStudents", counts.total(),
                "completed", counts.completed(),
                "pending", counts.pending(),
                "needsCorrection", counts.needsCorrection(),
                "completionPercent", counts.percent(),
                "classWiseStatus", List.of());
    }

    private Map<String, Object> fullNameStatus(String campaignId) {
        ReviewCounts counts = reviewCounts(campaignId);
        return row("campaignId", campaignId,
                "totalStudents", counts.total(),
                "confirmed", counts.completed(),
                "correctionRequested", counts.needsCorrection(),
                "pending", counts.pending(),
                "completionPercent", counts.percent());
    }

    private Optional<String> activeReviewCampaign(Long schoolId, String reviewType) {
        return jdbc.sql("""
                        SELECT id
                        FROM student.student_review_campaigns
                        WHERE school_id = :schoolId
                          AND review_type = :reviewType
                          AND status = 'ACTIVE'
                        ORDER BY created_at DESC NULLS LAST, initiated_at DESC NULLS LAST, id DESC
                        LIMIT 1
                        """)
                .param("schoolId", schoolId)
                .param("reviewType", reviewType)
                .query(String.class)
                .optional();
    }

    private ReviewCounts reviewCounts(String campaignId) {
        long total = countReviewItems(campaignId, null);
        long completed = countReviewItems(campaignId, "COMPLETED");
        long needsCorrection = countReviewItems(campaignId, "NEEDS_CORRECTION");
        long pending = Math.max(0, total - completed - needsCorrection);
        double percent = total == 0 ? 0.0 : Math.round((completed * 10000.0 / total)) / 100.0;
        return new ReviewCounts(total, completed, pending, needsCorrection, percent);
    }

    private long countReviewItems(String campaignId, String status) {
        if (status == null) {
            return jdbc.sql("SELECT COUNT(*) FROM student.student_review_items WHERE campaign_id = :campaignId")
                    .param("campaignId", campaignId)
                    .query(Long.class)
                    .single();
        }
        return jdbc.sql("SELECT COUNT(*) FROM student.student_review_items WHERE campaign_id = :campaignId AND status = :status")
                .param("campaignId", campaignId)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private Optional<Map<String, Object>> reviewItemDetail(String itemId, Long schoolId) {
        return jdbc.sql("""
                        SELECT i.id, i.student_id, s.full_name, s.admission_no, c.name AS class_name,
                               sec.name AS section_name, i.current_full_name, i.suggested_full_name,
                               i.status, i.verified_photo, i.verified_full_name, i.verified_admission_no,
                               i.verified_class_section, i.verified_roll_no, i.verified_father_name,
                               i.verified_father_contact, i.verified_address, i.verified_blood_group,
                               i.parent_confirmed, i.teacher_confirmed, i.correction_requested,
                               i.correction_notes, i.completed_at
                        FROM student.student_review_items i
                        JOIN student.students s ON s.id = i.student_id
                        JOIN tenant_school.school_classes c ON c.id = s.class_id
                        JOIN tenant_school.school_sections sec ON sec.id = s.section_id
                        WHERE i.id = :itemId AND i.school_id = :schoolId
                        """)
                .param("itemId", itemId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "itemId", rs.getString("id"),
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("full_name"),
                        "admissionNo", rs.getString("admission_no"),
                        "className", rs.getString("class_name"),
                        "sectionName", rs.getString("section_name"),
                        "currentFullName", rs.getString("current_full_name"),
                        "suggestedFullName", rs.getString("suggested_full_name"),
                        "status", rs.getString("status"),
                        "verifiedPhoto", rs.getBoolean("verified_photo"),
                        "verifiedFullName", rs.getBoolean("verified_full_name"),
                        "verifiedAdmissionNo", rs.getBoolean("verified_admission_no"),
                        "verifiedClassSection", rs.getBoolean("verified_class_section"),
                        "verifiedRollNo", rs.getBoolean("verified_roll_no"),
                        "verifiedFatherName", rs.getBoolean("verified_father_name"),
                        "verifiedFatherContact", rs.getBoolean("verified_father_contact"),
                        "verifiedAddress", rs.getBoolean("verified_address"),
                        "verifiedBloodGroup", rs.getBoolean("verified_blood_group"),
                        "parentConfirmed", rs.getBoolean("parent_confirmed"),
                        "teacherConfirmed", rs.getBoolean("teacher_confirmed"),
                        "correctionRequested", rs.getBoolean("correction_requested"),
                        "correctionNotes", rs.getString("correction_notes"),
                        "completedAt", rs.getObject("completed_at", OffsetDateTime.class)))
                .optional();
    }

    private Map<String, Object> reviewItemFlags(String itemId) {
        return jdbc.sql("""
                        SELECT status, verified_photo, verified_full_name, verified_admission_no,
                               verified_class_section, verified_roll_no, verified_father_name,
                               verified_father_contact, verified_address, verified_blood_group,
                               correction_notes
                        FROM student.student_review_items
                        WHERE id = :itemId
                        """)
                .param("itemId", itemId)
                .query((rs, rowNum) -> row(
                        "status", rs.getString("status"),
                        "verifiedPhoto", rs.getBoolean("verified_photo"),
                        "verifiedFullName", rs.getBoolean("verified_full_name"),
                        "verifiedAdmissionNo", rs.getBoolean("verified_admission_no"),
                        "verifiedClassSection", rs.getBoolean("verified_class_section"),
                        "verifiedRollNo", rs.getBoolean("verified_roll_no"),
                        "verifiedFatherName", rs.getBoolean("verified_father_name"),
                        "verifiedFatherContact", rs.getBoolean("verified_father_contact"),
                        "verifiedAddress", rs.getBoolean("verified_address"),
                        "verifiedBloodGroup", rs.getBoolean("verified_blood_group"),
                        "correctionNotes", rs.getString("correction_notes")))
                .single();
    }

    private ImportValidation validatePreviewRow(Map<String, Object> normalized, Long schoolId) {
        if (str(normalized.get("name"), "").isBlank()
                || str(normalized.get("className"), "").isBlank()
                || str(normalized.get("sectionName"), "").isBlank()
                || str(normalized.get("phone"), "").isBlank()) {
            return new ImportValidation("Missing field", "Required field is blank", false, true, false);
        }
        String className = str(normalized.get("className"), "");
        Optional<Map<String, Object>> schoolClass = classByName(className);
        if (schoolClass.isEmpty()) {
            return new ImportValidation("Class not found", "Class value not found in the system", false, true, false);
        }
        String admissionNo = str(normalized.get("admissionNo"), "");
        Long duplicate = jdbc.sql("SELECT COUNT(*) FROM student.students WHERE lower(admission_no) = lower(:admissionNo) AND school_id = :schoolId")
                .param("admissionNo", admissionNo)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (duplicate != null && duplicate > 0) {
            return new ImportValidation("Duplicate", "Admission number already exists", false, true, false);
        }
        Long sectionCount = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM tenant_school.school_sections
                        WHERE school_id = :schoolId
                          AND school_class_id = :classId
                          AND lower(name) = lower(:sectionName)
                        """)
                .param("schoolId", schoolId)
                .param("classId", schoolClass.get().get("id"))
                .param("sectionName", str(normalized.get("sectionName"), ""))
                .query(Long.class)
                .single();
        if (sectionCount == null || sectionCount == 0) {
            return new ImportValidation("Section not found", "Section value not found for the school", false, true, false);
        }
        if (!str(normalized.get("phone"), "").replaceAll("\\D+", "").matches("\\d{10}")) {
            return new ImportValidation("Warning", "Phone is unusual format", true, false, true);
        }
        return new ImportValidation("Valid", "", true, false, false);
    }

    private Long insertImportedStudent(Map<String, Object> normalized, Long schoolId, String batchId) {
        Map<String, Object> schoolClass = classByName(str(normalized.get("className"), ""))
                .orElseThrow(() -> new IllegalArgumentException("Class not found"));
        Map<String, Object> section = getOrCreateSection(schoolId, String.valueOf(schoolClass.get("id")),
                str(normalized.get("sectionName"), "A"));
        String academicYearId = currentAcademicYearId();
        String admissionNo = str(normalized.get("admissionNo"), "");
        Long duplicate = jdbc.sql("SELECT COUNT(*) FROM student.students WHERE lower(admission_no) = lower(:admissionNo) AND school_id = :schoolId")
                .param("admissionNo", admissionNo)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (duplicate != null && duplicate > 0) {
            throw new IllegalArgumentException("Duplicate admission number");
        }
        OffsetDateTime now = OffsetDateTime.now();
        return jdbc.sql("""
                        INSERT INTO student.students(admission_no, roll_no, board_reg_no, full_name, dob, gender,
                                             father_name, father_contact, phone, address,
                                             fee_status, attendance_percent, imported_at, import_batch_id,
                                             created_at, updated_at, school_id, class_id, section_id, academic_year_id, version)
                        VALUES (:admissionNo, :rollNo, :boardRegNo, :fullName, :dob, :gender,
                                :fatherName, :fatherContact, :phone, :address,
                                'Pending', 0, :importedAt, :batchId,
                                :createdAt, :updatedAt, :schoolId, :classId, :sectionId, :academicYearId, 0)
                        RETURNING id
                        """)
                .param("admissionNo", admissionNo)
                .param("rollNo", String.valueOf(countBySection(String.valueOf(section.get("id"))) + 1))
                .param("boardRegNo", str(normalized.get("boardRegistrationNo"), ""))
                .param("fullName", str(normalized.get("name"), ""))
                .param("dob", parseDate(str(normalized.get("dateOfBirth"), "")))
                .param("gender", str(normalized.get("gender"), "Unspecified"))
                .param("fatherName", str(normalized.get("fatherName"), ""))
                .param("fatherContact", str(normalized.get("phone"), ""))
                .param("phone", str(normalized.get("phone"), ""))
                .param("address", str(normalized.get("address"), ""))
                .param("importedAt", now)
                .param("batchId", batchId)
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("schoolId", schoolId)
                .param("classId", schoolClass.get("id"))
                .param("sectionId", section.get("id"))
                .param("academicYearId", academicYearId)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> normalizeImportRow(Map<String, Object> rawRow) {
        return row("name", firstPresentIgnoreCase(rawRow, "Name", "name"),
                "className", firstPresentIgnoreCase(rawRow, "Class", "class", "className"),
                "sectionName", firstPresentIgnoreCase(rawRow, "Section", "section", "sectionName"),
                "admissionNo", firstPresentIgnoreCase(rawRow, "AdmissionNo", "admissionNo", "Admission No"),
                "dateOfBirth", firstPresentIgnoreCase(rawRow, "DateOfBirth", "dateOfBirth"),
                "gender", firstPresentIgnoreCase(rawRow, "Gender", "gender"),
                "fatherName", firstPresentIgnoreCase(rawRow, "FatherName", "fatherName"),
                "phone", firstPresentIgnoreCase(rawRow, "Phone", "phone"),
                "address", firstPresentIgnoreCase(rawRow, "Address", "address"),
                "boardRegistrationNo", firstPresentIgnoreCase(rawRow, "BoardRegistrationNo", "boardRegistrationNo"));
    }

    private String firstPresentIgnoreCase(Map<String, Object> rawRow, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : rawRow.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                    return String.valueOf(entry.getValue()).trim();
                }
            }
        }
        return "";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Optional<Map<String, Object>> classById(String id) {
        return jdbc.sql("SELECT id, name, sort_order FROM tenant_school.school_classes WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name"), "sortOrder", rs.getInt("sort_order")))
                .optional();
    }

    private Map<String, Object> getOrCreateSection(Long schoolId, String classId, String requestedSectionName) {
        String sectionName = str(requestedSectionName, "A").trim();
        if (sectionName.isBlank()) sectionName = "A";
        String normalized = sectionName.toUpperCase(Locale.ROOT);
        Optional<Map<String, Object>> existing = jdbc.sql("""
                SELECT id, name FROM tenant_school.school_sections
                WHERE school_id = :schoolId AND school_class_id = :classId AND lower(name) = lower(:name)
                """)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("name", normalized)
                .query((rs, rowNum) -> row("id", rs.getString("id"), "name", rs.getString("name")))
                .optional();
        if (existing.isPresent()) return existing.get();
        String id = schoolId + "-" + classId + "-" + normalized;
        jdbc.sql("""
                INSERT INTO tenant_school.school_sections(id, name, teacher_name, active, school_class_id, school_id)
                VALUES (:id, :name, '', true, :classId, :schoolId)
                """)
                .param("id", id)
                .param("name", normalized)
                .param("classId", classId)
                .param("schoolId", schoolId)
                .update();
        return row("id", id, "name", normalized);
    }

    private String currentAcademicYearId() {
        return jdbc.sql("SELECT id FROM tenant_school.academic_years WHERE active = true ORDER BY id LIMIT 1")
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private void requireSchool(Long schoolId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        if (count == null || count == 0) {
            throw new IllegalArgumentException("School not found");
        }
    }

    private long countBySection(String sectionId) {
        return jdbc.sql("SELECT COUNT(*) FROM student.students WHERE section_id = :sectionId AND deleted_at IS NULL")
                .param("sectionId", sectionId)
                .query(Long.class)
                .single();
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String key : keys) {
            if (request.containsKey(key) && request.get(key) != null) return request.get(key);
        }
        return null;
    }

    private String requireText(Object value, String message) {
        String text = str(value, "").trim();
        if (text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }

    private List<String> classesForSchool(Long schoolId) {
        return jdbc.sql("""
                SELECT DISTINCT sc.name
                FROM tenant_school.school_sections ss
                JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id
                WHERE ss.school_id = :schoolId
                ORDER BY sc.name
                """)
                .param("schoolId", schoolId)
                .query(String.class)
                .list();
    }

    private List<String> sectionNamesForSchool(Long schoolId) {
        return jdbc.sql("""
                SELECT DISTINCT name
                FROM tenant_school.school_sections
                WHERE school_id = :schoolId
                ORDER BY name
                """)
                .param("schoolId", schoolId)
                .query(String.class)
                .list();
    }

    private boolean blankOrAll(String value) {
        return value == null || value.isBlank() || "All".equalsIgnoreCase(value);
    }

    private String initials(String fullName) {
        return Arrays.stream(str(fullName, "").split(" "))
                .filter(value -> !value.isBlank())
                .limit(2)
                .map(value -> value.substring(0, 1).toUpperCase(Locale.ROOT))
                .collect(Collectors.joining());
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Long longValue(Object value, Long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Long requireLong(Object value, String message) {
        Long parsed = longValue(value, null);
        if (parsed == null) throw new IllegalArgumentException(message);
        return parsed;
    }

    private boolean boolOr(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(value));
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private int classSortOrder(String classId) {
        String digits = String.valueOf(classId).replaceAll("\\D+", "");
        return digits.isBlank() ? 0 : Integer.parseInt(digits);
    }

    private String joinAddress(String... parts) {
        return Arrays.stream(parts).filter(value -> value != null && !value.isBlank()).collect(Collectors.joining(", "));
    }

    private Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    public Long schoolIdForReviewItem(String itemId) {
        return jdbc.sql("SELECT school_id FROM student.student_review_items WHERE id = :itemId")
                .param("itemId", itemId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private record ImportValidation(String status, String message, boolean valid, boolean error, boolean warning) {}

    private record ReviewCounts(long total, long completed, long pending, long needsCorrection, double percent) {}
}
