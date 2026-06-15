package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);

    private final StudentRepository studentRepository;
    private final SchoolRepository schoolRepository;
    private final SchoolClassRepository classRepository;
    private final SchoolSectionRepository sectionRepository;
    private final AcademicYearRepository academicYearRepository;
    private final FeeBandRepository feeBandRepository;
    private final FeeItemRepository feeItemRepository;
    private final FeeAssignmentRepository feeAssignmentRepository;
    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StudentService(StudentRepository studentRepository,
                          SchoolRepository schoolRepository,
                          SchoolClassRepository classRepository,
                          SchoolSectionRepository sectionRepository,
                          AcademicYearRepository academicYearRepository,
                          FeeBandRepository feeBandRepository,
                          FeeItemRepository feeItemRepository,
                          FeeAssignmentRepository feeAssignmentRepository,
                          ImportBatchRepository importBatchRepository,
                          ImportRowRepository importRowRepository,
                          AuditLogService auditLogService) {
        this.studentRepository = studentRepository;
        this.schoolRepository = schoolRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.academicYearRepository = academicYearRepository;
        this.feeBandRepository = feeBandRepository;
        this.feeItemRepository = feeItemRepository;
        this.feeAssignmentRepository = feeAssignmentRepository;
        this.importBatchRepository = importBatchRepository;
        this.importRowRepository = importRowRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> studentsData(Long schoolId) {
        return scopedStudents(schoolId).stream().filter(this::isStudentRenderable).map(this::studentListRow).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> studentsPage(String className, String sectionName, String feeStatus,
                                             int page, int size, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        List<StudentEntity> filtered = scopedStudents(schoolId).stream()
                .filter(s -> blankOr(className, s.getSchoolClass().getName()))
                .filter(s -> blankOr(sectionName, s.getSection().getName()))
                .filter(s -> blankOr(feeStatus, s.getFeeStatus()))
                .sorted(Comparator.comparing(StudentEntity::getFullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        List<Map<String, Object>> items = filtered.stream().skip((long) page * size).limit(size)
                .map(this::studentListRow).toList();
        long sections = filtered.stream()
                .map(s -> s.getSchoolClass().getName() + "-" + s.getSection().getName()).distinct().count();
        List<SchoolSectionEntity> schoolSections = sectionRepository.findBySchool_Id(schoolId);
        List<String> classes = schoolSections.stream()
                .filter(sec -> sec != null && sec.getSchoolClass() != null)
                .map(sec -> sec.getSchoolClass().getName()).distinct().sorted().toList();
        List<String> secs = schoolSections.stream()
                .filter(sec -> sec != null)
                .map(SchoolSectionEntity::getName).distinct().sorted().toList();
        return row("items", items, "page", page, "size", size, "totalItems", filtered.size(),
                "totalPages", size == 0 ? 0 : (int) Math.ceil(filtered.size() / (double) size),
                "filteredCount", filtered.size(), "filteredSections", sections,
                "filters", row("classes", classes, "sections", secs,
                        "feeStatuses", List.of("Paid", "Overdue", "Pending", "Partial")));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> studentDetail(long id, AuthUser actor, Long requestedSchoolId) {
        StudentEntity s = studentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        Long actorSchoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        Long entitySchoolId = s.getSection() != null && s.getSection().getSchool() != null
                ? s.getSection().getSchool().getId()
                : (s.getSchool() != null ? s.getSchool().getId() : null);
        assertSchoolOwnership("student", entitySchoolId, actorSchoolId);
        return studentDetailRow(s);
    }

    public Map<String, Object> attachStudentPhoto(long studentId, String photoUrl) {
        StudentEntity s = studentRepository.findById(studentId)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        s.setPhotoUrl(photoUrl);
        s.setUpdatedAt(OffsetDateTime.now());
        studentRepository.save(s);
        return studentDetailRow(s);
    }

    public Map<String, Object> addStudent(Map<String, Object> request, AuthUser actor) {
        AcademicYearEntity year = currentAcademicYearEntity();
        String admissionNo = str(request.get("admissionNumber"), "").trim();
        if (admissionNo.isBlank()) throw new IllegalArgumentException("Admission Number is mandatory");
        if (studentRepository.findByAdmissionNoIgnoreCase(admissionNo).isPresent())
            throw new IllegalArgumentException("Admission Number already exists");
        String fullName = str(request.get("fullName"), "").trim();
        if (fullName.isBlank()) throw new IllegalArgumentException("Full name is mandatory");
        Long schoolId = TenantContext.get();
        SchoolEntity school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new IllegalArgumentException("School not found"));
        SchoolClassEntity schoolClass = classRepository.findByName(str(request.get("gradeLevel"), str(request.get("className"), "Class 9")))
                .orElseGet(() -> classRepository.findById(String.valueOf(classSortOrder(str(request.get("gradeLevel"), "9")))).orElseThrow());
        SchoolSectionEntity section = getOrCreateSectionForSchool(school, schoolClass, str(request.get("sectionName"), "A"));
        StudentEntity s = new StudentEntity();
        s.setAdmissionNo(admissionNo);
        s.setRollNo(str(request.get("rollNo"), String.valueOf(studentRepository.countBySection_Id(section.getId()) + 1)));
        s.setBoardRegNo(str(request.get("boardRegistrationNumber"), ""));
        s.setFullName(fullName);
        String dob = str(request.get("dateOfBirth"), "");
        if (!dob.isBlank()) s.setDob(LocalDate.parse(dob));
        s.setGender(str(request.get("gender"), "Unspecified"));
        s.setFatherName(str(request.get("fatherName"), ""));
        s.setFatherContact(str(request.get("fatherContactNumber"), str(request.get("fatherContact"), "")));
        s.setMotherName(str(request.get("motherName"), ""));
        s.setPhone(str(request.get("phone"), s.getFatherContact()));
        s.setHouseNumber(str(request.get("houseNumber"), ""));
        s.setStreet(str(request.get("street"), ""));
        s.setLocality(str(request.get("locality"), ""));
        s.setCity(str(request.get("city"), "Hyderabad"));
        s.setState(str(request.get("state"), "Telangana"));
        s.setPinCode(str(request.get("pinCode"), ""));
        s.setAddress(joinAddress(s.getHouseNumber(), s.getStreet(), s.getLocality(), s.getCity(), s.getState(), s.getPinCode()));
        s.setSchool(school);
        s.setSchoolClass(schoolClass);
        s.setSection(section);
        s.setAcademicYear(year);
        s.setFeeStatus("Pending");
        s.setAttendancePercent(0d);
        s.setCreatedAt(OffsetDateTime.now());
        s.setUpdatedAt(OffsetDateTime.now());
        studentRepository.save(s);
        ensureFeeAssignmentForStudent(s, request, actor);
        auditLogService.recordEvent("STUDENT_CREATED",
                actor == null ? null : actor.userId(),
                s.getSchool() != null ? s.getSchool().getId() : TenantContext.get(),
                "student", String.valueOf(s.getId()),
                null, s.getAdmissionNo() + " | " + s.getFullName());
        log.info("student.added studentId={} admissionNo={} actorId={}", s.getId(), s.getAdmissionNo(), actor.userId());
        return studentDetailRow(s);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> classesList(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        return sectionRepository.findBySchool_Id(schoolId).stream()
                .filter(sec -> sec != null && sec.getSchoolClass() != null)
                .map(SchoolSectionEntity::getSchoolClass)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SchoolClassEntity::getId, c -> c, (a, b) -> a, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(SchoolClassEntity::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> row("id", c.getId(), "name", c.getName())).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sectionsForClass(String classId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        return sectionRepository.findBySchoolClass_IdOrderByNameAsc(classId).stream()
                .filter(s -> s.getSchool() != null && schoolId.equals(s.getSchool().getId()))
                .map(s -> row("id", s.getId(), "name", s.getName())).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> studentsForClassSection(String classId, String sectionId,
                                                              AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        return scopedStudents(schoolId).stream()
                .filter(s -> s.getSchoolClass() != null && classId.equals(s.getSchoolClass().getId())
                        && s.getSection() != null && sectionId.equals(s.getSection().getId()))
                .map(s -> {
                    Optional<FeeAssignmentEntity> assignment = feeAssignmentRepository
                            .findByStudent_IdAndAcademicYear_Id(s.getId(), currentAcademicYearId())
                            .or(() -> feeAssignmentRepository.findByStudent_Id(s.getId()));
                    long total = assignment.map(FeeAssignmentEntity::getNetPayable).orElse(0L);
                    long paid = assignment.map(FeeAssignmentEntity::getPaidAmount).orElse(0L);
                    long due = Math.max(total - paid, 0);
                    return row("id", s.getId(), "name", s.getFullName(), "admissionNo", s.getAdmissionNo(),
                            "dueAmount", due,
                            "feePlan", assignment.map(a -> a.getBand().getName()).orElse(""),
                            "schedule", assignment.map(FeeAssignmentEntity::getSchedule).orElse(""),
                            "totalFee", total,
                            "discount", assignment.map(FeeAssignmentEntity::getBandDiscount).orElse(0d),
                            "surcharge", assignment.map(FeeAssignmentEntity::getSurcharge).orElse(0d),
                            "paid", paid);
                }).toList();
    }

    // ── Import ──────────────────────────────────────────────────────

    public Map<String, Object> previewStudentImport(List<Map<String, Object>> rawRows, AuthUser actor) {
        if (rawRows.size() > 500) throw new IllegalArgumentException("Maximum 500 rows per import");
        Long schoolId = TenantContext.get();
        ImportBatchEntity batch = new ImportBatchEntity();
        batch.setId(UUID.randomUUID().toString());
        batch.setFileToken(UUID.randomUUID().toString());
        batch.setStatus("PREVIEWED");
        batch.setTotalRows(rawRows.size());
        int valid = 0, errors = 0, warnings = 0;
        List<Map<String, Object>> previewRows = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> raw = rawRows.get(i);
            Map<String, Object> normalized = normalizeImportRow(raw);
            String status = "Valid";
            String message = "";
            if (str(normalized.get("name"), "").isBlank() || str(normalized.get("className"), "").isBlank()
                    || str(normalized.get("sectionName"), "").isBlank() || str(normalized.get("phone"), "").isBlank()) {
                status = "Missing field"; message = "Required field is blank"; errors++;
            } else if (!classRepository.findByName(str(normalized.get("className"), "")).isPresent()) {
                status = "Class not found"; message = "Class value not found in the system"; errors++;
            } else if (studentRepository.findByAdmissionNoIgnoreCase(str(normalized.get("admissionNo"), "")).isPresent()) {
                status = "Duplicate"; message = "Admission number already exists"; errors++;
            } else {
                SchoolClassEntity importClass = classRepository.findByName(str(normalized.get("className"), "")).orElse(null);
                if (importClass == null || sectionRepository.findBySchool_IdAndSchoolClass_IdAndNameIgnoreCase(
                        schoolId, importClass.getId(), str(normalized.get("sectionName"), "")).isEmpty()) {
                    status = "Section not found"; message = "Section value not found for the school"; errors++;
                } else if (!str(normalized.get("phone"), "").replaceAll("\\D+", "").matches("\\d{10}")) {
                    status = "Warning"; message = "Phone is unusual format"; warnings++;
                    valid++;
                } else {
                    valid++;
                }
            }
            previewRows.add(row("rowNumber", i + 1, "name", normalized.get("name"), "className", normalized.get("className"),
                    "sectionName", normalized.get("sectionName"), "admissionNo", normalized.get("admissionNo"),
                    "phone", normalized.get("phone"), "status", status, "message", message));
        }
        batch.setValidCount(valid);
        batch.setErrorCount(errors);
        batch.setWarningCount(warnings);
        importBatchRepository.save(batch);
        for (int i = 0; i < rawRows.size(); i++) {
            Map<String, Object> raw = rawRows.get(i);
            Map<String, Object> normalized = normalizeImportRow(raw);
            String status = (String) previewRows.get(i).get("status");
            String message = (String) previewRows.get(i).get("message");
            ImportRowEntity rowEntity = new ImportRowEntity();
            rowEntity.setId(UUID.randomUUID().toString());
            rowEntity.setBatch(batch);
            rowEntity.setRowNumber(i + 1);
            rowEntity.setName(str(normalized.get("name"), ""));
            rowEntity.setClassName(str(normalized.get("className"), ""));
            rowEntity.setSectionName(str(normalized.get("sectionName"), ""));
            rowEntity.setAdmissionNo(str(normalized.get("admissionNo"), ""));
            rowEntity.setPhone(str(normalized.get("phone"), ""));
            rowEntity.setStatus(status);
            rowEntity.setMessage(message);
            rowEntity.setRawJson(toJson(raw));
            rowEntity.setNormalizedJson(toJson(normalized));
            importRowRepository.save(rowEntity);
        }
        return row("rows", previewRows, "fileToken", batch.getFileToken(),
                "validCount", valid, "errorCount", errors, "warningCount", warnings);
    }

    public Map<String, Object> confirmStudentImport(String fileToken, AuthUser actor) {
        ImportBatchEntity batch = importBatchRepository.findByFileToken(fileToken)
                .orElseThrow(() -> new IllegalArgumentException("Preview token not found"));
        List<ImportRowEntity> rows = importRowRepository.findByBatch_IdOrderByRowNumberAsc(batch.getId());
        batch.setJobId(UUID.randomUUID().toString());
        batch.setStatus("RUNNING");
        batch.setPct(20);
        importBatchRepository.save(batch);
        AcademicYearEntity year = currentAcademicYearEntity();
        Long schoolId = TenantContext.get();
        int inserted = 0;
        int skipped = 0;
        List<Map<String, Object>> skippedRows = new ArrayList<>();
        for (ImportRowEntity rowEnt : rows) {
            if (!"Valid".equalsIgnoreCase(rowEnt.getStatus()) && !"Warning".equalsIgnoreCase(rowEnt.getStatus())) {
                skipped++;
                skippedRows.add(row("rowNumber", rowEnt.getRowNumber(), "reason", rowEnt.getMessage()));
                continue;
            }
            try {
                Map<String, Object> normalized = objectMapper.readValue(rowEnt.getNormalizedJson(), new TypeReference<>() {});
                String admission = str(normalized.get("admissionNo"), "");
                if (studentRepository.findByAdmissionNoIgnoreCase(admission).isPresent()) {
                    skipped++;
                    skippedRows.add(row("rowNumber", rowEnt.getRowNumber(), "reason", "Duplicate admission number"));
                    continue;
                }
                SchoolClassEntity schoolClass = classRepository.findByName(str(normalized.get("className"), "")).orElseThrow();
                SchoolEntity school = schoolRepository.findById(schoolId)
                        .orElseThrow(() -> new IllegalArgumentException("School not found"));
                SchoolSectionEntity section = getOrCreateSectionForSchool(school, schoolClass, str(normalized.get("sectionName"), "A"));
                StudentEntity s = new StudentEntity();
                s.setAdmissionNo(admission);
                s.setRollNo(String.valueOf(studentRepository.countBySection_Id(section.getId()) + 1));
                s.setBoardRegNo(str(normalized.get("boardRegistrationNo"), ""));
                s.setFullName(str(normalized.get("name"), ""));
                String dob = str(normalized.get("dateOfBirth"), "");
                if (!dob.isBlank()) s.setDob(LocalDate.parse(dob));
                s.setGender(str(normalized.get("gender"), "Unspecified"));
                s.setFatherName(str(normalized.get("fatherName"), ""));
                s.setFatherContact(str(normalized.get("phone"), ""));
                s.setPhone(str(normalized.get("phone"), ""));
                s.setAddress(str(normalized.get("address"), ""));
                s.setSchool(section.getSchool());
                s.setSchoolClass(schoolClass);
                s.setSection(section);
                s.setAcademicYear(year);
                s.setFeeStatus("Pending");
                s.setAttendancePercent(0d);
                s.setImportedAt(OffsetDateTime.now());
                s.setImportBatchId(batch.getId());
                studentRepository.save(s);
                inserted++;
            } catch (Exception ex) {
                skipped++;
                skippedRows.add(row("rowNumber", rowEnt.getRowNumber(), "reason", ex.getMessage()));
            }
        }
        batch.setPct(100);
        batch.setStatus("DONE");
        batch.setInserted(inserted);
        batch.setSkipped(skipped);
        batch.setCompletedAt(OffsetDateTime.now());
        batch.setSkippedJson(toJson(skippedRows));
        importBatchRepository.save(batch);
        auditLogService.recordEvent("STUDENT_IMPORT_COMPLETED",
                actor == null ? null : actor.userId(),
                TenantContext.get(),
                "import_batch", batch.getId(),
                null, "inserted=" + inserted + " skipped=" + skipped);
        log.info("student.importConfirmed fileToken={} inserted={} skipped={} actorId={}", fileToken, inserted, skipped, actor.userId());
        return row("jobId", batch.getJobId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> importJobStatus(String jobId) {
        ImportBatchEntity batch = importBatchRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Import job not found"));
        List<Map<String, Object>> skipped = new ArrayList<>();
        if (batch.getSkippedJson() != null && !batch.getSkippedJson().isBlank()) {
            try {
                skipped = objectMapper.readValue(batch.getSkippedJson(), new TypeReference<>() {});
            } catch (Exception ignored) {}
        }
        return row("pct", batch.getPct(), "done", "DONE".equalsIgnoreCase(batch.getStatus()),
                "inserted", batch.getInserted(), "skipped", batch.getSkipped(), "skippedRows", skipped);
    }

    public byte[] studentImportTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Students");
            Row header = sheet.createRow(0);
            List<String> columns = List.of("Name", "Class", "Section", "AdmissionNo", "DateOfBirth",
                    "Gender", "FatherName", "Phone", "Address", "BoardRegistrationNo");
            for (int i = 0; i < columns.size(); i++) header.createCell(i).setCellValue(columns.get(i));
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("Aryan Mehta");
            sample.createCell(1).setCellValue("Class 9");
            sample.createCell(2).setCellValue("B");
            sample.createCell(3).setCellValue("ADM-1001");
            sample.createCell(4).setCellValue("2010-05-12");
            sample.createCell(5).setCellValue("Male");
            sample.createCell(6).setCellValue("R. Mehta");
            sample.createCell(7).setCellValue("9876543210");
            sample.createCell(8).setCellValue("Flat 4B, Main Road, Hyderabad");
            sample.createCell(9).setCellValue("BRN1001");
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate template");
        }
    }

    // ── Row mappers (used by WorkspaceService) ──────────────────────

    public Map<String, Object> studentListRow(StudentEntity s) {
        String fullName = s.getFullName() == null ? "Student" : s.getFullName();
        String initials = Arrays.stream(fullName.split(" ")).filter(v -> !v.isBlank()).limit(2)
                .map(v -> v.substring(0, 1).toUpperCase()).collect(Collectors.joining());
        String className = s.getSchoolClass() == null ? "" : s.getSchoolClass().getName();
        String sectionName = s.getSection() == null ? "" : s.getSection().getName();
        String academicYear = s.getAcademicYear() == null ? "" : s.getAcademicYear().getLabel();
        return row("id", s.getId(), "name", fullName, "fullName", fullName, "avatarInitials", initials,
                "photoUrl", s.getPhotoUrl(), "className", className, "sectionName", sectionName,
                "classSection", className.replace("Class ", "") + (sectionName.isBlank() ? "" : "-" + sectionName),
                "academicYear", academicYear, "admissionNumber", s.getAdmissionNo(), "rollNo", s.getRollNo(),
                "fatherName", s.getFatherName(), "fatherContact", s.getFatherContact(),
                "feeStatus", s.getFeeStatus(),
                "attendancePercent", s.getAttendancePercent() == null ? 0 : round(s.getAttendancePercent()));
    }

    // ── Private helpers ─────────────────────────────────────────────

    private boolean isStudentRenderable(StudentEntity s) {
        return s != null && s.getSection() != null && s.getSchoolClass() != null;
    }

    @Transactional(readOnly = true)
    public List<StudentEntity> scopedStudents(Long schoolId) {
        return studentRepository.findBySchool_IdOrderByFullNameAsc(schoolId).stream()
                .filter(this::isStudentRenderable)
                .toList();
    }

    private Map<String, Object> studentDetailRow(StudentEntity s) {
        Map<String, Object> map = new LinkedHashMap<>(studentListRow(s));
        map.put("dateOfBirth", s.getDob() == null ? null : s.getDob().toString());
        map.put("gender", s.getGender());
        map.put("boardRegistrationNumber", s.getBoardRegNo());
        map.put("motherName", s.getMotherName());
        map.put("address", row("houseNumber", s.getHouseNumber(), "street", s.getStreet(),
                "locality", s.getLocality(), "city", s.getCity(), "state", s.getState(),
                "pinCode", s.getPinCode(), "full", s.getAddress()));
        return map;
    }

    private FeeAssignmentEntity ensureFeeAssignmentForStudent(StudentEntity student, Map<String, Object> request, AuthUser actor) {
        FeeAssignmentEntity existing = feeAssignmentRepository
                .findByStudent_IdAndAcademicYear_Id(student.getId(), currentAcademicYearId())
                .orElseGet(() -> feeAssignmentRepository.findByStudent_Id(student.getId()).orElse(null));
        if (existing != null) {
            if (existing.getAcademicYear() == null || !currentAcademicYearId().equals(existing.getAcademicYear().getId())) {
                existing.setAcademicYear(currentAcademicYearEntity());
                existing.setUpdatedBy(actor == null ? 1L : actor.userId());
                existing.setUpdatedAt(OffsetDateTime.now());
                feeAssignmentRepository.save(existing);
            }
            return existing;
        }
        int sort = student.getSchoolClass() == null
                ? classSortOrder(str(request.get("gradeLevel"), "Class 9"))
                : (student.getSchoolClass().getSortOrder() > 0
                ? student.getSchoolClass().getSortOrder()
                : classSortOrder(student.getSchoolClass().getName()));
        FeeBandEntity band = feeBandRepository
                .findFirstByAcademicYear_IdAndClassFromLessThanEqualAndClassToGreaterThanEqual(
                        currentAcademicYearId(), sort, sort).orElse(null);
        if (band == null) return null;
        String schedule = str(firstPresent(request, "paymentSchedule", "schedule"), "Monthly");
        List<String> activeSchedules = bandActiveSchedules(band);
        if (schedule.isBlank()) schedule = activeSchedules.isEmpty() ? "Monthly" : activeSchedules.get(0);
        if (!activeSchedules.isEmpty() && !activeSchedules.contains(schedule)) schedule = activeSchedules.get(0);
        FeeAssignmentEntity assignment = new FeeAssignmentEntity();
        assignment.setId(UUID.randomUUID().toString());
        assignment.setStudent(student);
        assignment.setBand(band);
        assignment.setAcademicYear(currentAcademicYearEntity());
        assignment.setSchedule(schedule);
        assignment.setBandDiscount(num(firstPresent(request, "bandDiscount"), band.getDiscount()));
        assignment.setManualDiscount(num(firstPresent(request, "manualDiscountOverride", "manualDiscount"), 0));
        assignment.setSurcharge("Annual".equalsIgnoreCase(schedule) ? 0 : num(firstPresent(request, "surcharge"), 0));
        long total = bandTotal(band.getId());
        assignment.setNetPayable(calculateNetPayable(total, assignment.getBandDiscount(),
                assignment.getManualDiscount(), assignment.getSurcharge(), schedule));
        assignment.setPaidAmount(0);
        assignment.setAssignedBy(actor == null ? 1L : actor.userId());
        assignment.setAssignedAt(OffsetDateTime.now());
        assignment.setUpdatedBy(actor == null ? 1L : actor.userId());
        assignment.setUpdatedAt(OffsetDateTime.now());
        feeAssignmentRepository.save(assignment);
        student.setFeeStatus(assignment.getNetPayable() <= 0 ? "Paid" : "Pending");
        student.setUpdatedAt(OffsetDateTime.now());
        studentRepository.save(student);
        return assignment;
    }

    private long bandTotal(String bandId) {
        return feeItemRepository.findByBand_IdOrderByCreatedAtAsc(bandId).stream()
                .mapToLong(FeeItemEntity::getAmount).sum();
    }

    private List<String> bandActiveSchedules(FeeBandEntity band) {
        return splitCsv(band == null ? null : band.getActiveSchedulesCsv());
    }

    private long calculateNetPayable(long total, double bandDiscount, double manualDiscount,
                                      double surcharge, String schedule) {
        long bandAmt = Math.round(total * bandDiscount / 100.0);
        long manualAmt = Math.round(total * manualDiscount / 100.0);
        long surchargeAmt = "Annual".equalsIgnoreCase(schedule) ? 0 : Math.round(total * surcharge / 100.0);
        return total - bandAmt - manualAmt + surchargeAmt;
    }

    private SchoolSectionEntity getOrCreateSectionForSchool(SchoolEntity school, SchoolClassEntity schoolClass,
                                                             String requestedSectionName) {
        if (school == null) throw new IllegalArgumentException("School not found");
        if (schoolClass == null) throw new IllegalArgumentException("Class not found");
        String sectionName = str(requestedSectionName, "A").trim();
        if (sectionName.isBlank()) sectionName = "A";
        final String normalizedSectionName = sectionName.toUpperCase(Locale.ROOT);
        return sectionRepository.findBySchool_IdAndSchoolClass_IdAndNameIgnoreCase(
                        school.getId(), schoolClass.getId(), normalizedSectionName)
                .orElseGet(() -> {
                    SchoolSectionEntity section = new SchoolSectionEntity();
                    section.setId(school.getId() + "-" + schoolClass.getId() + "-" + normalizedSectionName);
                    section.setSchool(school);
                    section.setSchoolClass(schoolClass);
                    section.setName(normalizedSectionName);
                    section.setTeacherName("");
                    section.setActive(true);
                    return sectionRepository.save(section);
                });
    }

    private AcademicYearEntity currentAcademicYearEntity() {
        return academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private String currentAcademicYearId() {
        return currentAcademicYearEntity().getId();
    }

    private void assertSchoolOwnership(String entityLabel, Long entitySchoolId, Long actorSchoolId) {
        if (actorSchoolId != null && entitySchoolId != null && !actorSchoolId.equals(entitySchoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this " + entityLabel);
        }
    }

    private boolean blankOr(String filter, String value) {
        return filter == null || filter.isBlank() || filter.equalsIgnoreCase("All") || filter.equalsIgnoreCase(value);
    }

    private int classSortOrder(String classId) {
        String digits = String.valueOf(classId).replaceAll("\\D+", "");
        if (digits.isBlank()) return 0;
        return Integer.parseInt(digits);
    }

    private String joinAddress(String... parts) {
        return Arrays.stream(parts).filter(v -> v != null && !v.isBlank()).collect(Collectors.joining(", "));
    }

    private Map<String, Object> normalizeImportRow(Map<String, Object> rawRow) {
        return row("name", firstPresentValue(rawRow, "Name", "name"),
                "className", firstPresentValue(rawRow, "Class", "class"),
                "sectionName", firstPresentValue(rawRow, "Section", "section"),
                "admissionNo", firstPresentValue(rawRow, "AdmissionNo", "admissionNo", "Admission No"),
                "dateOfBirth", firstPresentValue(rawRow, "DateOfBirth", "dateOfBirth"),
                "gender", firstPresentValue(rawRow, "Gender", "gender"),
                "fatherName", firstPresentValue(rawRow, "FatherName", "fatherName"),
                "phone", firstPresentValue(rawRow, "Phone", "phone"),
                "address", firstPresentValue(rawRow, "Address", "address"),
                "boardRegistrationNo", firstPresentValue(rawRow, "BoardRegistrationNo", "boardRegistrationNo"));
    }

    private String firstPresentValue(Map<String, Object> rawRow, String... keys) {
        for (String k : keys) {
            for (Map.Entry<String, Object> e : rawRow.entrySet()) {
                if (e.getKey().equalsIgnoreCase(k) && e.getValue() != null)
                    return String.valueOf(e.getValue()).trim();
            }
        }
        return "";
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String k : keys)
            if (request.containsKey(k) && request.get(k) != null) return request.get(k);
        return null;
    }

    private List<String> splitCsv(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private double num(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
