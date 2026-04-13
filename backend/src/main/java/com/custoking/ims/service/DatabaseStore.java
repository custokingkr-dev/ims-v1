package com.custoking.ims.service;

import com.custoking.ims.dto.*;
import com.custoking.ims.dto.school.SchoolAdminRequest;
import com.custoking.ims.dto.school.SchoolCreateRequest;
import com.custoking.ims.dto.school.SchoolUpdateRequest;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.DashboardStats;
import com.custoking.ims.model.Role;
import com.custoking.ims.repo.*;
import com.custoking.ims.util.PasswordUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class DatabaseStore {
    private final AppUserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final SchoolRepository schoolRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SchoolClassRepository classRepository;
    private final SchoolSectionRepository sectionRepository;
    private final StudentRepository studentRepository;
    private final FeeBandRepository feeBandRepository;
    private final FeeItemRepository feeItemRepository;
    private final FeeAssignmentRepository feeAssignmentRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final AttendanceDailyRepository attendanceDailyRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final SupplyOrderRepository supplyOrderRepository;
    private final AnnualPlanRepository annualPlanRepository;
    private final FirefightingRequestRepository firefightingRequestRepository;
    private final CatalogOrderRepository catalogOrderRepository;
    private final SuperadminInvoiceRepository saInvoiceRepository;
    private final SuperadminOrderSeqRepository saSeqRepository;
    private final AnnualPlanItemRepository annualPlanItemRepository;
    private final FirefightingQuotationRepository firefightingQuotationRepository;
    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DatabaseStore(
            AppUserRepository userRepository,
            AuthSessionRepository authSessionRepository,
            SchoolRepository schoolRepository,
            AcademicYearRepository academicYearRepository,
            SchoolClassRepository classRepository,
            SchoolSectionRepository sectionRepository,
            StudentRepository studentRepository,
            FeeBandRepository feeBandRepository,
            FeeItemRepository feeItemRepository,
            FeeAssignmentRepository feeAssignmentRepository,
            PaymentRecordRepository paymentRecordRepository,
            AttendanceDailyRepository attendanceDailyRepository,
            StaffMemberRepository staffMemberRepository,
            CatalogItemRepository catalogItemRepository,
            SupplyOrderRepository supplyOrderRepository,
            AnnualPlanRepository annualPlanRepository,
            FirefightingRequestRepository firefightingRequestRepository,
            CatalogOrderRepository catalogOrderRepository,
            SuperadminInvoiceRepository saInvoiceRepository,
            SuperadminOrderSeqRepository saSeqRepository,
            AnnualPlanItemRepository annualPlanItemRepository,
            FirefightingQuotationRepository firefightingQuotationRepository,
            ImportBatchRepository importBatchRepository,
            ImportRowRepository importRowRepository
    ) {
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.schoolRepository = schoolRepository;
        this.academicYearRepository = academicYearRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.studentRepository = studentRepository;
        this.feeBandRepository = feeBandRepository;
        this.feeItemRepository = feeItemRepository;
        this.feeAssignmentRepository = feeAssignmentRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.attendanceDailyRepository = attendanceDailyRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.supplyOrderRepository = supplyOrderRepository;
        this.annualPlanRepository = annualPlanRepository;
        this.firefightingRequestRepository = firefightingRequestRepository;
        this.catalogOrderRepository = catalogOrderRepository;
        this.saInvoiceRepository = saInvoiceRepository;
        this.saSeqRepository = saSeqRepository;
        this.annualPlanItemRepository = annualPlanItemRepository;
        this.firefightingQuotationRepository = firefightingQuotationRepository;
        this.importBatchRepository = importBatchRepository;
        this.importRowRepository = importRowRepository;
    }

    public AuthResponse login(LoginRequest request) {
        AppUserEntity user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));
        if (!PasswordUtil.hash(request.password()).equals(user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        authSessionRepository.deleteByUser_Id(user.getId());
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUser(user);
        session.setAccessToken(UUID.randomUUID().toString());
        session.setRefreshToken(UUID.randomUUID().toString());
        session.setCreatedAt(OffsetDateTime.now());
        session.setExpiresAt(OffsetDateTime.now().plusDays(7));
        authSessionRepository.save(session);
        return new AuthResponse(session.getAccessToken(), session.getRefreshToken(), user.getId(), user.getFullName(), user.getEmail(), user.getRole(), user.getBranchId(), user.getBranchName());
    }

    public AuthResponse refresh(RefreshRequest request) {
        AuthSessionEntity session = authSessionRepository.findByRefreshToken(request.refreshToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) throw new IllegalArgumentException("Invalid refresh token");
        session.setAccessToken(UUID.randomUUID().toString());
        session.setRefreshToken(UUID.randomUUID().toString());
        session.setExpiresAt(OffsetDateTime.now().plusDays(7));
        authSessionRepository.save(session);
        AppUserEntity user = session.getUser();
        return new AuthResponse(session.getAccessToken(), session.getRefreshToken(), user.getId(), user.getFullName(), user.getEmail(), user.getRole(), user.getBranchId(), user.getBranchName());
    }

    public AuthUser requireUser(String authorization) {
        if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing bearer token");
        }
        String token = authorization.substring(7).trim();
        AuthSessionEntity session = authSessionRepository.findByAccessToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid access token"));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) throw new IllegalArgumentException("Invalid access token");
        AppUserEntity user = session.getUser();
        return new AuthUser(user.getId(), user.getFullName(), user.getEmail(), Role.valueOf(user.getRole()), user.getBranchId(), user.getBranchName(), null);
    }

    public AuthUser requireSuperAdmin(String authorization) {
        AuthUser user = requireUser(authorization);
        if (user.role() != Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN access required");
        }
        return user;
    }

    public List<Map<String, Object>> listSchools() {
        return schoolRepository.findAll().stream()
                .sorted(Comparator.comparing(SchoolEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .map(school -> {
                    AppUserEntity admin = userRepository.findFirstByRoleIgnoreCaseAndBranchId("ADMIN", school.getId()).orElse(null);
                    return Map.<String, Object>of(
                            "id", school.getId(),
                            "name", school.getName(),
                            "shortCode", school.getShortCode(),
                            "city", school.getCity() == null ? "" : school.getCity(),
                            "active", school.isActive(),
                            "adminEmail", admin == null ? "" : admin.getEmail(),
                            "configuredClassCount", school.getConfiguredClassCount() == null ? 12 : school.getConfiguredClassCount(),
                            "configuredSectionCount", school.getConfiguredSectionCount() == null ? 2 : school.getConfiguredSectionCount()
                    );
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> createSchool(SchoolCreateRequest request) {
        String shortCode = request.shortCode().trim().toUpperCase(Locale.ROOT);
        schoolRepository.findByShortCodeIgnoreCase(shortCode).ifPresent(existing -> {
            throw new IllegalArgumentException("School short code already exists");
        });
        SchoolEntity school = new SchoolEntity();
        school.setName(request.name().trim());
        school.setShortCode(shortCode);
        school.setCity(trimToNull(request.city()));
        school.setState(trimToNull(request.state()));
        school.setContactEmail(trimToNull(request.contactEmail()));
        school.setContactPhone(trimToNull(request.contactPhone()));
        school.setConfiguredClassCount(request.classCount() == null ? 12 : Math.max(1, Math.min(12, request.classCount())));
        school.setConfiguredSectionCount(request.sectionCount() == null ? 2 : Math.max(1, Math.min(26, request.sectionCount())));
        school.setActive(true);
        schoolRepository.save(school);
        ensureSchoolSections(school, school.getConfiguredClassCount(), school.getConfiguredSectionCount());
        return schoolDetails(school);
    }

    public Map<String, Object> updateSchool(Long schoolId, SchoolUpdateRequest request) {
        SchoolEntity school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        if (request.name() != null && !request.name().isBlank()) school.setName(request.name().trim());
        if (request.city() != null) school.setCity(trimToNull(request.city()));
        if (request.active() != null) school.setActive(request.active());
        schoolRepository.save(school);
        return schoolDetails(school);
    }

    public Map<String, Object> createOrResetSchoolAdmin(Long schoolId, SchoolAdminRequest request) {
        SchoolEntity school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        userRepository.findFirstByRoleIgnoreCaseAndBranchId("ADMIN", schoolId).ifPresent(existing -> {
            authSessionRepository.deleteByUser_Id(existing.getId());
            userRepository.delete(existing);
        });
        AppUserEntity user = new AppUserEntity();
        user.setFullName(request.fullName().trim());
        user.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(PasswordUtil.hash(request.temporaryPassword()));
        user.setRole("ADMIN");
        user.setBranchId(school.getId());
        user.setBranchName(school.getName());
        userRepository.save(user);
        return adminDetails(user);
    }

    public Map<String, Object> getSchoolAdmin(Long schoolId) {
        schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        AppUserEntity admin = userRepository.findFirstByRoleIgnoreCaseAndBranchId("ADMIN", schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ADMIN not found for school"));
        return adminDetails(admin);
    }

    private Map<String, Object> schoolDetails(SchoolEntity school) {
        return Map.of(
                "id", school.getId(),
                "name", school.getName(),
                "shortCode", school.getShortCode(),
                "city", school.getCity() == null ? "" : school.getCity(),
                "state", school.getState() == null ? "" : school.getState(),
                "active", school.isActive(),
                "configuredClassCount", school.getConfiguredClassCount() == null ? 12 : school.getConfiguredClassCount(),
                "configuredSectionCount", school.getConfiguredSectionCount() == null ? 2 : school.getConfiguredSectionCount()
        );
    }

    private Map<String, Object> adminDetails(AppUserEntity user) {
        return Map.of(
                "userId", user.getId(),
                "fullName", user.getFullName(),
                "email", user.getEmail(),
                "branchId", user.getBranchId(),
                "branchName", user.getBranchName()
        );
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public Map<String, Object> workspace(AuthUser actor) { return workspace(actor, null); }

    public Map<String, Object> workspace(AuthUser actor, Long requestedSchoolId) {
        AcademicYearEntity year = currentAcademicYearEntity();
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        SchoolEntity school = schoolRepository.findById(schoolId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        List<StudentEntity> students = scopedStudents(schoolId);
        Map<String, Object> attendanceSummary = attendanceDailySummary("today", schoolId);
        List<Map<String, Object>> attendanceSections = attendanceSummary.containsKey("sections") ? castListMap(attendanceSummary.get("sections")) : List.of();
        Map<String, Object> feesModule = buildFeesModule(year.getId(), schoolId);
        Map<String, Object> feeSummary = castMap(feesModule.get("summary"));
        long activeFire = firefightingRequestRepository.countBySchool_IdAndStatusNot(schoolId, "FULFILLED");
        long pendingApprovals = firefightingRequestRepository.countBySchool_IdAndStatus(schoolId, "AWAITING_PRINCIPAL");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("school", row("name", school.getName(), "meta", school.getCity() == null ? ("Academic year " + year.getLabel()) : (school.getCity() + " · Academic year " + year.getLabel()), "students", students.size(), "sections", sectionRepository.findAll().stream().filter(sec -> sec.getSchool() != null && schoolId.equals(sec.getSchool().getId())).count()));
        response.put("dashboard", row(
                "students", students.size(),
                "sections", sectionRepository.findAll().stream().filter(sec -> sec != null && sec.getSchool() != null && schoolId.equals(sec.getSchool().getId())).count(),
                "attendancePercent", num(attendanceSummary.get("overallPercent"), 0),
                "attendancePresent", attendanceSections.stream().mapToInt(m -> (int) longNum(m.get("presentCount"), 0)).sum(),
                "feeCollectedLakh", round(num(feeSummary.get("collected"), 0) / 100000.0),
                "feeTargetLakh", round(num(feeSummary.get("target"), 0) / 100000.0),
                "feeOverdueCount", feeOverdueCount(year.getId(), schoolId),
                "firefightingActive", activeFire,
                "pendingApprovals", pendingApprovals
        ));
        response.put("recentActivity", List.of(
                row("icon", "🎓", "title", "Student profile updated", "meta", students.isEmpty() ? "No students yet" : students.get(0).getFullName() + " · ERP", "tag", "ERP", "tone", "sb2"),
                row("icon", "₹", "title", "Fee plan assignments live", "meta", feeAssignmentRepository.count() + " assignments in PostgreSQL", "tag", "Fees", "tone", "sg"),
                row("icon", "✓", "title", "Attendance snapshot ready", "meta", attendanceSections.size() + " sections with records today", "tag", "Attendance", "tone", "pb")
        ));
        response.put("students", studentsData(schoolId));
        response.put("fees", feesModule);
        response.put("feeStructures", feeStructureData(year.getId()).get("bands"));
        response.put("attendance", row("summary", row("date", LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")), "overallPercent", num(attendanceSummary.get("overallPercent"), 0)), "classes", attendanceSections));
        response.put("timetable", List.of(row("day", "Monday", "period", "P1", "classSection", "9-B", "subject", "Mathematics", "teacher", "Priya Sharma"), row("day", "Tuesday", "period", "P2", "classSection", "6-A", "subject", "Science", "teacher", "Arun Menon")));
        response.put("staff", staffMemberRepository.findAll().stream().filter(s -> s != null && s.getSchool() != null && schoolId.equals(s.getSchool().getId())).map(this::staffRow).toList());
        response.put("catalog", catalogCategories());
        List<CatalogOrderEntity> orders = catalogOrderRepository.findBySchool_Id(schoolId);
        response.put("orders", orders.stream().sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()).map(this::catalogOrderListRow).toList());
        response.put("annualPlan", annualPlanPayload(schoolId));
        List<FirefightingRequestEntity> ff = firefightingRequestRepository.findBySchool_Id(schoolId);
        response.put("firefighting", row("requests", ff.stream().sorted(Comparator.comparing(FirefightingRequestEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()).map(this::fireRequestRow).toList(), "orders", ff.stream().filter(r -> List.of("APPROVED", "FULFILLED").contains(String.valueOf(r.getStatus()).toUpperCase(Locale.ROOT))).map(this::fireOrderRowNew).toList()));
        response.put("users", users());
        return response;
    }

    public DashboardStats dashboard(AuthUser actor) {
        Map<String, Object> dashboard = castMap(workspace(actor).get("dashboard"));
        return new DashboardStats(((Number) dashboard.get("students")).intValue(), ((Number) dashboard.get("feeOverdueCount")).intValue(), ((Number) dashboard.get("firefightingActive")).intValue(), num(dashboard.get("attendancePercent"), 0), num(dashboard.get("feeCollectedLakh"), 0), num(dashboard.get("feeTargetLakh"), 0));
    }

    public List<Map<String, Object>> users() {
        return userRepository.findAll().stream().map(u -> row("id", u.getId(), "fullName", u.getFullName(), "email", u.getEmail(), "role", u.getRole(), "branchName", u.getBranchName())).toList();
    }

    public List<Map<String, Object>> customers() { return List.of(); }
    public Map<String, Object> addCustomer(Map<String, Object> request) { return row("ok", true); }
    public List<Map<String, Object>> invoices() { return List.of(); }
    public Map<String, Object> addInvoice(InvoiceCreateRequest request) { return row("ok", true); }
    public byte[] invoicePdf(long id) { return simplePdf("Invoice " + id); }
    public List<Map<String, Object>> payments() { return paymentRecordRepository.findAll().stream().map(p -> row("id", p.getId(), "student", p.getStudent().getFullName(), "amount", p.getAmount(), "mode", p.getMode(), "paidAt", p.getPaidAt().toString())).toList(); }
    public Map<String, Object> addPayment(PaymentCreateRequest request, AuthUser user) { return row("ok", true); }
    public List<Map<String, Object>> approvals(AuthUser actor) { return firefightingRequestRepository.findBySchool_IdAndStatus(resolveSchoolId(actor, null), "AWAITING_PRINCIPAL").stream().map(this::fireRequestRow).toList(); }
    public Map<String, Object> decideApproval(long id, String action, ApprovalDecisionRequest request) { return row("ok", true); }

    public List<Map<String, Object>> studentsData() {
        return studentRepository.findAll().stream().filter(this::isStudentRenderable).map(this::studentListRow).toList();
    }

    public List<Map<String, Object>> studentsData(Long schoolId) {
        return scopedStudents(schoolId).stream().filter(this::isStudentRenderable).map(this::studentListRow).toList();
    }

    public Map<String, Object> studentsPage(String className, String sectionName, String feeStatus, int page, int size) {
        return studentsPage(className, sectionName, feeStatus, page, size, new AuthUser(1L, "System", "system@custoking.com", Role.SUPERADMIN, defaultSchool().getId(), defaultSchool().getName(), null), defaultSchool().getId());
    }

    public Map<String, Object> studentsPage(String className, String sectionName, String feeStatus, int page, int size, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        List<StudentEntity> filtered = scopedStudents(schoolId).stream().filter(s -> blankOr(className, s.getSchoolClass().getName())).filter(s -> blankOr(sectionName, s.getSection().getName())).filter(s -> blankOr(feeStatus, s.getFeeStatus())).sorted(Comparator.comparing(StudentEntity::getFullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList();
        List<Map<String, Object>> items = filtered.stream().skip((long) page * size).limit(size).map(this::studentListRow).toList();
        long sections = filtered.stream().map(s -> s.getSchoolClass().getName() + "-" + s.getSection().getName()).distinct().count();
        List<String> classes = sectionRepository.findAll().stream().filter(sec -> sec != null && sec.getSchool() != null && schoolId.equals(sec.getSchool().getId()) && sec.getSchoolClass() != null).map(sec -> sec.getSchoolClass().getName()).distinct().sorted().toList();
        List<String> secs = sectionRepository.findAll().stream().filter(sec -> sec != null && sec.getSchool() != null && schoolId.equals(sec.getSchool().getId())).map(SchoolSectionEntity::getName).distinct().sorted().toList();
        return row("items", items, "page", page, "size", size, "totalItems", filtered.size(), "totalPages", size == 0 ? 0 : (int) Math.ceil(filtered.size() / (double) size), "filteredCount", filtered.size(), "filteredSections", sections, "filters", row("classes", classes, "sections", secs, "feeStatuses", List.of("Paid", "Overdue", "Pending", "Partial")));
    }

    public Map<String, Object> studentDetail(long id) {
        StudentEntity s = studentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Student not found"));
        return studentDetailRow(s);
    }

    public Map<String, Object> studentDetail(long id, AuthUser actor, Long requestedSchoolId) {
        StudentEntity s = studentRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Student not found"));
        Long actorSchoolId = resolveSchoolId(actor, requestedSchoolId);
        Long entitySchoolId = s.getSection() != null && s.getSection().getSchool() != null ? s.getSection().getSchool().getId() : (s.getSchool() != null ? s.getSchool().getId() : null);
        assertSchoolOwnership("student", entitySchoolId, actorSchoolId);
        return studentDetailRow(s);
    }

    public Map<String, Object> attachStudentPhoto(long studentId, String photoUrl) {
        StudentEntity s = studentRepository.findById(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found"));
        s.setPhotoUrl(photoUrl);
        s.setUpdatedAt(OffsetDateTime.now());
        studentRepository.save(s);
        return studentDetailRow(s);
    }

    
public Map<String, Object> addStudent(Map<String, Object> request, AuthUser actor) {
    AcademicYearEntity year = currentAcademicYearEntity();
    String admissionNo = str(request.get("admissionNumber"), "").trim();
    if (admissionNo.isBlank()) throw new IllegalArgumentException("Admission Number is mandatory");
    if (studentRepository.findByAdmissionNoIgnoreCase(admissionNo).isPresent()) throw new IllegalArgumentException("Admission Number already exists");
    String fullName = str(request.get("fullName"), "").trim();
    if (fullName.isBlank()) throw new IllegalArgumentException("Full name is mandatory");
    Long schoolId = actor != null && actor.branchId() != null ? actor.branchId() : defaultSchool().getId();
    SchoolEntity school = schoolRepository.findById(schoolId).orElseThrow(() -> new IllegalArgumentException("School not found"));
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
    return studentDetailRow(s);
}

    public Map<String, Object> bulkImport(Map<String, Object> request) { return row("ok", true); }

    public List<Map<String, Object>> classesList() {
        return classRepository.findAllByOrderBySortOrderAsc().stream().map(c -> row("id", c.getId(), "name", c.getName())).toList();
    }

    public List<Map<String, Object>> classesList(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return sectionRepository.findAll().stream().filter(sec -> sec != null && sec.getSchool() != null && schoolId.equals(sec.getSchool().getId()) && sec.getSchoolClass() != null)
                .map(SchoolSectionEntity::getSchoolClass)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SchoolClassEntity::getId, c -> c, (a, b) -> a, LinkedHashMap::new))
                .values().stream().sorted(Comparator.comparing(SchoolClassEntity::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> row("id", c.getId(), "name", c.getName())).toList();
    }

    public List<Map<String, Object>> sectionsForClass(String classId) {
        return sectionRepository.findBySchoolClass_IdOrderByNameAsc(classId).stream().map(s -> row("id", s.getId(), "name", s.getName())).toList();
    }

    public List<Map<String, Object>> sectionsForClass(String classId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return sectionRepository.findBySchoolClass_IdOrderByNameAsc(classId).stream().filter(s -> s.getSchool() != null && schoolId.equals(s.getSchool().getId())).map(s -> row("id", s.getId(), "name", s.getName())).toList();
    }

    public List<Map<String, Object>> studentsForClassSection(String classId, String sectionId) {
        return studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(classId, sectionId).stream().map(s -> {
            Optional<FeeAssignmentEntity> assignment = feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(s.getId(), currentAcademicYearId()).or(() -> feeAssignmentRepository.findByStudent_Id(s.getId()));
            long total = assignment.map(FeeAssignmentEntity::getNetPayable).orElse(0L);
            long paid = assignment.map(FeeAssignmentEntity::getPaidAmount).orElse(0L);
            long due = Math.max(total - paid, 0);
            return row("id", s.getId(), "name", s.getFullName(), "admissionNo", s.getAdmissionNo(), "dueAmount", due, "feePlan", assignment.map(a -> a.getBand().getName()).orElse(""), "schedule", assignment.map(FeeAssignmentEntity::getSchedule).orElse(""), "totalFee", total, "discount", assignment.map(FeeAssignmentEntity::getBandDiscount).orElse(0d), "surcharge", assignment.map(FeeAssignmentEntity::getSurcharge).orElse(0d), "paid", paid);
        }).toList();
    }

    public List<Map<String, Object>> studentsForClassSection(String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return scopedStudents(schoolId).stream().filter(s -> s.getSchoolClass() != null && classId.equals(s.getSchoolClass().getId()) && s.getSection() != null && sectionId.equals(s.getSection().getId())).map(s -> {
            Optional<FeeAssignmentEntity> assignment = feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(s.getId(), currentAcademicYearId()).or(() -> feeAssignmentRepository.findByStudent_Id(s.getId()));
            long total = assignment.map(FeeAssignmentEntity::getNetPayable).orElse(0L);
            long paid = assignment.map(FeeAssignmentEntity::getPaidAmount).orElse(0L);
            long due = Math.max(total - paid, 0);
            return row("id", s.getId(), "name", s.getFullName(), "admissionNo", s.getAdmissionNo(), "dueAmount", due, "feePlan", assignment.map(a -> a.getBand().getName()).orElse(""), "schedule", assignment.map(FeeAssignmentEntity::getSchedule).orElse(""), "totalFee", total, "discount", assignment.map(FeeAssignmentEntity::getBandDiscount).orElse(0d), "surcharge", assignment.map(FeeAssignmentEntity::getSurcharge).orElse(0d), "paid", paid);
        }).toList();
    }

    private FeeAssignmentEntity ensureFeeAssignmentForStudent(StudentEntity student, Map<String, Object> request, AuthUser actor) {
        FeeAssignmentEntity existing = feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(student.getId(), currentAcademicYearId())
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
        FeeBandEntity band = feeBandRepository.findFirstByAcademicYear_IdAndClassFromLessThanEqualAndClassToGreaterThanEqual(currentAcademicYearId(), sort, sort).orElse(null);
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
        assignment.setNetPayable(calculateNetPayable(bandTotal(band.getId()), assignment.getBandDiscount(), assignment.getManualDiscount(), assignment.getSurcharge(), schedule));
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

    public Map<String, Object> feeAssignmentApi(Map<String, Object> request, AuthUser actor) {
        StudentEntity student = studentRepository.findById(longNum(request.get("studentId"), -1)).orElseThrow(() -> new IllegalArgumentException("Student not found"));
        FeeBandEntity band = feeBandRepository.findById(str(request.get("bandId"), "")).orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        String schedule = str(request.get("schedule"), "");
        if (schedule.isBlank()) throw new IllegalArgumentException("Payment schedule is required");
        FeeAssignmentEntity assignment = feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(student.getId(), currentAcademicYearId()).orElseGet(FeeAssignmentEntity::new);
        if (assignment.getId() == null) {
            assignment.setId(UUID.randomUUID().toString());
            assignment.setStudent(student);
            assignment.setAcademicYear(currentAcademicYearEntity());
            assignment.setAssignedBy(actor.userId());
            assignment.setAssignedAt(OffsetDateTime.now());
        }
        assignment.setBand(band);
        assignment.setSchedule(schedule);
        assignment.setBandDiscount(num(firstPresent(request, "bandDiscount"), band.getDiscount()));
        assignment.setManualDiscount(num(firstPresent(request, "manualDiscount"), 0));
        assignment.setSurcharge("Annual".equalsIgnoreCase(schedule) ? 0 : num(request.get("surcharge"), 0));
        long total = bandTotal(band.getId());
        long net = calculateNetPayable(total, assignment.getBandDiscount(), assignment.getManualDiscount(), assignment.getSurcharge(), schedule);
        assignment.setNetPayable(net);
        assignment.setUpdatedBy(actor.userId());
        assignment.setUpdatedAt(OffsetDateTime.now());
        feeAssignmentRepository.save(assignment);
        student.setFeeStatus(assignment.getPaidAmount() >= assignment.getNetPayable() ? "Paid" : "Overdue");
        studentRepository.save(student);
        return row("ok", true, "assignment", assignmentRow(assignment));
    }

    public Map<String, Object> assignFeePlan(Map<String, Object> request) { return feeAssignmentApi(request, new AuthUser(1L, "System", "system@custoking.com", Role.SUPERADMIN, null, null, null)); }

    public List<Map<String, Object>> feeReport(String classId, String sectionId) {
        return feeAssignmentRepository.findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId()).stream().map(a -> {
            long due = Math.max(a.getNetPayable() - a.getPaidAmount(), 0);
            return row("paymentId", latestPaymentId(a.getStudent().getId()), "student", a.getStudent().getFullName(), "planName", a.getBand().getName(), "schedule", a.getSchedule(), "totalAnnualFee", bandTotal(a.getBand().getId()), "discounts", round(a.getBandDiscount() + a.getManualDiscount()), "surcharge", round(a.getSurcharge()), "paid", a.getPaidAmount(), "due", due, "status", due > 0 ? "Overdue" : "Paid");
        }).toList();
    }

    public List<Map<String, Object>> feeReport(String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return feeAssignmentRepository.findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId()).stream().filter(a -> a.getStudent() != null && a.getStudent().getSection() != null && a.getStudent().getSection().getSchool() != null && schoolId.equals(a.getStudent().getSection().getSchool().getId())).map(a -> {
            long due = Math.max(a.getNetPayable() - a.getPaidAmount(), 0);
            return row("paymentId", latestPaymentId(a.getStudent().getId()), "student", a.getStudent().getFullName(), "planName", a.getBand().getName(), "schedule", a.getSchedule(), "totalAnnualFee", bandTotal(a.getBand().getId()), "discounts", round(a.getBandDiscount() + a.getManualDiscount()), "surcharge", round(a.getSurcharge()), "paid", a.getPaidAmount(), "due", due, "status", due > 0 ? "Overdue" : "Paid");
        }).toList();
    }

    public List<Map<String, Object>> feeOverdue(String classId, String sectionId) {
        return feeAssignmentRepository.findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId()).stream().filter(a -> a.getNetPayable() > a.getPaidAmount()).map(a -> row("student", a.getStudent().getFullName(), "schedule", a.getSchedule(), "dueAmount", a.getNetPayable() - a.getPaidAmount(), "daysOverdue", 12 + (a.getStudent().getId().intValue() % 24))).toList();
    }

    public List<Map<String, Object>> feeOverdue(String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return feeAssignmentRepository.findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId()).stream().filter(a -> a.getNetPayable() > a.getPaidAmount()).filter(a -> a.getStudent() != null && a.getStudent().getSection() != null && a.getStudent().getSection().getSchool() != null && schoolId.equals(a.getStudent().getSection().getSchool().getId())).map(a -> row("student", a.getStudent().getFullName(), "schedule", a.getSchedule(), "dueAmount", a.getNetPayable() - a.getPaidAmount(), "daysOverdue", 12 + (a.getStudent().getId().intValue() % 24))).toList();
    }

    public Map<String, Object> sendFeeReminders(String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        long queued = feeAssignmentRepository.findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId()).stream()
                .filter(a -> a.getNetPayable() > a.getPaidAmount())
                .filter(a -> a.getStudent() != null && a.getStudent().getSection() != null && a.getStudent().getSection().getSchool() != null && schoolId.equals(a.getStudent().getSection().getSchool().getId()))
                .count();
        return row("ok", true, "queued", queued, "classId", classId, "sectionId", sectionId);
    }

    public Map<String, Object> paymentApi(Map<String, Object> request, AuthUser actor) {
        StudentEntity student = studentRepository.findById(longNum(request.get("studentId"), -1)).orElseThrow(() -> new IllegalArgumentException("Student not found"));
        long amount = longNum(request.get("amount"), 0);
        if (amount <= 0) throw new IllegalArgumentException("Amount must be greater than zero");
        FeeAssignmentEntity assignment = feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(student.getId(), currentAcademicYearId())
                .orElseGet(() -> feeAssignmentRepository.findByStudent_Id(student.getId()).orElse(null));
        if (assignment == null) {
            assignment = ensureFeeAssignmentForStudent(student, request, actor);
        }
        if (assignment == null) throw new IllegalArgumentException("Fee assignment not found for this student. Assign a fee plan first.");
        if (assignment.getAcademicYear() == null || !currentAcademicYearId().equals(assignment.getAcademicYear().getId())) {
            assignment.setAcademicYear(currentAcademicYearEntity());
        }
        PaymentRecordEntity payment = new PaymentRecordEntity();
        payment.setId(UUID.randomUUID().toString());
        payment.setStudent(student);
        payment.setAssignment(assignment);
        payment.setAmount(amount);
        payment.setMode(str(request.get("mode"), "UPI"));
        payment.setNotes(str(request.get("notes"), ""));
        payment.setPaidAt(request.get("paidAt") == null ? OffsetDateTime.now() : OffsetDateTime.parse(String.valueOf(request.get("paidAt"))));
        payment.setRecordedBy(actor.userId());
        payment.setReceiptNumber("RCPT-" + System.currentTimeMillis());
        paymentRecordRepository.save(payment);
        assignment.setPaidAmount(assignment.getPaidAmount() + amount);
        assignment.setUpdatedBy(actor.userId());
        assignment.setUpdatedAt(OffsetDateTime.now());
        feeAssignmentRepository.save(assignment);
        student.setFeeStatus(assignment.getPaidAmount() >= assignment.getNetPayable() ? "Paid" : "Overdue");
        studentRepository.save(student);
        return row("paymentId", payment.getId(), "receiptUrl", "/api/receipts/" + payment.getId() + "/pdf");
    }

    public Map<String, Object> recordPayment(Map<String, Object> request, AuthUser actor) { return paymentApi(request, actor); }

    public byte[] receiptPdfByPaymentId(String paymentId) {
        PaymentRecordEntity payment = paymentRecordRepository.findById(paymentId).orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        return simplePdf("Receipt " + payment.getReceiptNumber() + " | " + payment.getStudent().getFullName() + " | INR " + indian(payment.getAmount()));
    }

    public byte[] feeReceiptPdf(String receiptNumber) {
        PaymentRecordEntity payment = paymentRecordRepository.findByReceiptNumber(receiptNumber).orElseThrow(() -> new IllegalArgumentException("Receipt not found"));
        return receiptPdfByPaymentId(payment.getId());
    }

    public Map<String, Object> attendanceDailySummary(String dateInput) {
        LocalDate date = parseDate(dateInput);
        List<AttendanceDailyEntity> records = attendanceDailyRepository.findByAttendanceDateAndAcademicYear_Id(date, currentAcademicYearId());
        List<Map<String, Object>> sections = sectionRepository.findAll().stream().map(section -> {
            AttendanceDailyEntity rec = records.stream().filter(r -> r.getSection().getId().equals(section.getId())).findFirst().orElse(null);
            double pct = rec == null || rec.getTotalEnrolled() == 0 ? 0 : round((rec.getPresentCount() * 100.0) / rec.getTotalEnrolled());
            return row("sectionId", section.getId(), "sectionName", section.getSchoolClass().getName() + "-" + section.getName(), "totalStudents", studentRepository.countBySection_Id(section.getId()), "presentPercent", rec == null ? null : pct, "presentCount", rec == null ? 0 : rec.getPresentCount(), "teacherName", section.getTeacherName(), "status", rec == null ? "Pending" : "Submitted");
        }).toList();
        double overall = sections.stream().filter(m -> m.get("presentPercent") != null).mapToDouble(m -> num(m.get("presentPercent"), 0)).average().orElse(0);
        return row("date", date.toString(), "dateLabel", date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")), "overallPercent", round(overall), "sections", sections, "allSubmitted", sections.stream().noneMatch(m -> "Pending".equals(m.get("status"))), "nonWorkingDay", date.getDayOfWeek() == DayOfWeek.SUNDAY);
    }


    private boolean isStudentRenderable(StudentEntity s){ return s != null && s.getSection() != null && s.getSchoolClass() != null; }
    private List<StudentEntity> scopedStudents(Long schoolId){ return studentRepository.findAll().stream().filter(this::isStudentRenderable).filter(s -> s.getSchool()!=null && schoolId.equals(s.getSchool().getId())).sorted(Comparator.comparing(StudentEntity::getFullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).toList(); }
    public Map<String, Object> attendanceDailySummary(String dateInput, Long schoolId) {
        LocalDate date = parseDate(dateInput);
        List<AttendanceDailyEntity> records = attendanceDailyRepository.findByAttendanceDateAndAcademicYear_Id(date, currentAcademicYearId()).stream().filter(r -> r.getSection()!=null && r.getSection().getSchool()!=null && schoolId.equals(r.getSection().getSchool().getId())).toList();
        List<Map<String, Object>> sections = sectionRepository.findAll().stream().filter(section -> section != null && section.getSchool() != null && schoolId.equals(section.getSchool().getId()) && section.getSchoolClass() != null).map(section -> {
            AttendanceDailyEntity rec = records.stream().filter(r -> r.getSection()!=null && r.getSection().getId().equals(section.getId())).findFirst().orElse(null);
            double pct = rec == null || rec.getTotalEnrolled() == 0 ? 0 : round((rec.getPresentCount() * 100.0) / rec.getTotalEnrolled());
            return row("sectionId", section.getId(), "sectionName", section.getSchoolClass().getName() + "-" + section.getName(), "totalStudents", studentRepository.countBySection_Id(section.getId()), "presentPercent", rec == null ? null : pct, "presentCount", rec == null ? 0 : rec.getPresentCount(), "teacherName", section.getTeacherName(), "status", rec == null ? "Pending" : "Submitted");
        }).toList();
        double overall = sections.stream().filter(m -> m.get("presentPercent") != null).mapToDouble(m -> num(m.get("presentPercent"), 0)).average().orElse(0);
        return row("date", date.toString(), "dateLabel", date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")), "overallPercent", round(overall), "sections", sections, "allSubmitted", sections.stream().noneMatch(m -> "Pending".equals(m.get("status"))), "nonWorkingDay", date.getDayOfWeek() == DayOfWeek.SUNDAY);
    }
    public Map<String, Object> attendanceDailySummary(String dateInput, AuthUser actor, Long requestedSchoolId) {
        return attendanceDailySummary(dateInput, resolveSchoolId(actor, requestedSchoolId));
    }

    public Map<String, Object> attendanceSectionInfo(String dateInput, String classId, String sectionId) {
        LocalDate date = parseDate(dateInput);
        SchoolSectionEntity section = sectionRepository.findById(sectionId).orElseThrow(() -> new IllegalArgumentException("Section not found"));
        int total = (int) studentRepository.countBySection_Id(sectionId);
        AttendanceDailyEntity record = attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(date, sectionId, currentAcademicYearId()).orElse(null);
        return row("totalEnrolled", total, "teacherName", section.getTeacherName(), "existingRecord", record == null ? null : row("presentCount", record.getPresentCount(), "savedAt", record.getUpdatedAt() == null ? record.getRecordedAt().toString() : record.getUpdatedAt().toString()));
    }
    public Map<String, Object> attendanceSectionInfo(String dateInput, String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        SchoolSectionEntity section = sectionRepository.findById(sectionId).orElseThrow(() -> new IllegalArgumentException("Section not found"));
        assertSchoolOwnership("section", section.getSchool() == null ? null : section.getSchool().getId(), schoolId);
        return attendanceSectionInfo(dateInput, classId, sectionId);
    }

    public Map<String, Object> saveDailyAttendance(Map<String, Object> request, AuthUser actor) {
        LocalDate date = LocalDate.parse(str(request.get("date"), LocalDate.now().toString()));
        String classId = str(request.get("classId"), "");
        String sectionId = str(request.get("sectionId"), "");
        SchoolClassEntity schoolClass = classRepository.findById(classId).orElseThrow(() -> new IllegalArgumentException("Class not found"));
        SchoolSectionEntity section = sectionRepository.findById(sectionId).orElseThrow(() -> new IllegalArgumentException("Section not found"));
        AttendanceDailyEntity entity = attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(date, sectionId, currentAcademicYearId()).orElseGet(AttendanceDailyEntity::new);
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setAcademicYear(currentAcademicYearEntity());
            entity.setAttendanceDate(date);
            entity.setSchoolClass(schoolClass);
            entity.setSection(section);
            entity.setRecordedBy(actor.userId());
            entity.setRecordedAt(OffsetDateTime.now());
        }
        int total = (int) longNum(request.get("totalEnrolled"), studentRepository.countBySection_Id(sectionId));
        int present = (int) longNum(request.get("presentCount"), 0);
        if (present < 0 || present > total) throw new IllegalArgumentException("Present count is invalid");
        entity.setTotalEnrolled(total);
        entity.setPresentCount(present);
        entity.setAbsentCount(Math.max(total - present, 0));
        entity.setUpdatedBy(actor.userId());
        entity.setUpdatedAt(OffsetDateTime.now());
        attendanceDailyRepository.save(entity);
        return row("ok", true, "message", "Saved — " + schoolClass.getName() + "-" + section.getName() + " · " + present + "/" + total + " present (" + round(total == 0 ? 0 : present * 100.0 / total) + "%)");
    }

    public Map<String, Object> saveAttendance(Map<String, Object> request) { return saveDailyAttendance(request, new AuthUser(1L, "System", "system@custoking.com", Role.SUPERADMIN, null, null, null)); }

    public Map<String, Object> submitAttendanceDay(String dateText, AuthUser actor) {
        LocalDate date = parseDate(dateText);
        List<AttendanceDailyEntity> records = attendanceDailyRepository.findByAttendanceDateAndAcademicYear_Id(date, currentAcademicYearId());
        records.forEach(r -> r.setLocked(true));
        attendanceDailyRepository.saveAll(records);
        return row("ok", true, "submitted", records.size());
    }

    public Map<String, Object> previewStudentImport(List<Map<String, Object>> rawRows, AuthUser actor) {
        if (rawRows.size() > 500) throw new IllegalArgumentException("Maximum 500 rows per import");
        Long schoolId = actor != null && actor.branchId() != null ? actor.branchId() : defaultSchool().getId();
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
            if (str(normalized.get("name"), "").isBlank() || str(normalized.get("className"), "").isBlank() || str(normalized.get("sectionName"), "").isBlank() || str(normalized.get("phone"), "").isBlank()) {
                status = "Missing field"; message = "Required field is blank"; errors++;
            } else if (!classRepository.findByName(str(normalized.get("className"), "")).isPresent()) {
                status = "Class not found"; message = "Class value not found in the system"; errors++;
            } else if (studentRepository.findByAdmissionNoIgnoreCase(str(normalized.get("admissionNo"), "")).isPresent()) {
                status = "Duplicate"; message = "Admission number already exists"; errors++;
            } else {
                SchoolClassEntity importClass = classRepository.findByName(str(normalized.get("className"), "")).orElse(null);
                if (importClass == null || sectionRepository.findBySchool_IdAndSchoolClass_IdAndNameIgnoreCase(schoolId, importClass.getId(), str(normalized.get("sectionName"), "")).isEmpty()) {
                    status = "Section not found"; message = "Section value not found for the school"; errors++;
                } else if (!str(normalized.get("phone"), "").replaceAll("\\D+", "").matches("\\d{10}")) {
                    status = "Warning"; message = "Phone is unusual format"; warnings++;
                    valid++;
                } else {
                    valid++;
                }
            }
            previewRows.add(row("rowNumber", i + 1, "name", normalized.get("name"), "className", normalized.get("className"), "sectionName", normalized.get("sectionName"), "admissionNo", normalized.get("admissionNo"), "phone", normalized.get("phone"), "status", status, "message", message));
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
            if (batch.getId() == null) throw new IllegalStateException("Batch not ready");
        }
        batch.setValidCount(valid); batch.setErrorCount(errors); batch.setWarningCount(warnings);
        importBatchRepository.save(batch);
        // save rows after batch persistence
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
        return row("rows", previewRows, "fileToken", batch.getFileToken(), "validCount", valid, "errorCount", errors, "warningCount", warnings);
    }

    public Map<String, Object> confirmStudentImport(String fileToken, AuthUser actor) {
        ImportBatchEntity batch = importBatchRepository.findByFileToken(fileToken).orElseThrow(() -> new IllegalArgumentException("Preview token not found"));
        List<ImportRowEntity> rows = importRowRepository.findByBatch_IdOrderByRowNumberAsc(batch.getId());
        batch.setJobId(UUID.randomUUID().toString());
        batch.setStatus("RUNNING");
        batch.setPct(20);
        importBatchRepository.save(batch);
        AcademicYearEntity year = currentAcademicYearEntity();
        Long schoolId = actor != null && actor.branchId() != null ? actor.branchId() : defaultSchool().getId();
        int inserted = 0; int skipped = 0; List<Map<String,Object>> skippedRows = new ArrayList<>();
        for (ImportRowEntity row : rows) {
            if (!"Valid".equalsIgnoreCase(row.getStatus()) && !"Warning".equalsIgnoreCase(row.getStatus())) {
                skipped++; skippedRows.add(row("rowNumber", row.getRowNumber(), "reason", row.getMessage())); continue;
            }
            try {
                Map<String,Object> normalized = objectMapper.readValue(row.getNormalizedJson(), new TypeReference<>(){});
                String admission = str(normalized.get("admissionNo"), "");
                if (studentRepository.findByAdmissionNoIgnoreCase(admission).isPresent()) { skipped++; skippedRows.add(row("rowNumber", row.getRowNumber(), "reason", "Duplicate admission number")); continue; }
                SchoolClassEntity schoolClass = classRepository.findByName(str(normalized.get("className"), "")).orElseThrow();
                SchoolEntity school = schoolRepository.findById(schoolId).orElseThrow(() -> new IllegalArgumentException("School not found"));
                SchoolSectionEntity section = getOrCreateSectionForSchool(school, schoolClass, str(normalized.get("sectionName"), "A"));
                StudentEntity s = new StudentEntity();
                s.setAdmissionNo(admission);
                s.setRollNo(String.valueOf(studentRepository.countBySection_Id(section.getId()) + 1));
                s.setBoardRegNo(str(normalized.get("boardRegistrationNo"), ""));
                s.setFullName(str(normalized.get("name"), ""));
                String dob = str(normalized.get("dateOfBirth"), ""); if (!dob.isBlank()) s.setDob(LocalDate.parse(dob));
                s.setGender(str(normalized.get("gender"), "Unspecified"));
                s.setFatherName(str(normalized.get("fatherName"), ""));
                s.setFatherContact(str(normalized.get("phone"), ""));
                s.setPhone(str(normalized.get("phone"), ""));
                s.setAddress(str(normalized.get("address"), ""));
                s.setSchool(section.getSchool()); s.setSchoolClass(schoolClass); s.setSection(section); s.setAcademicYear(year);
                s.setFeeStatus("Pending"); s.setAttendancePercent(0d);
                s.setImportedAt(OffsetDateTime.now()); s.setImportBatchId(batch.getId());
                studentRepository.save(s);
                inserted++;
            } catch (Exception ex) {
                skipped++; skippedRows.add(row("rowNumber", row.getRowNumber(), "reason", ex.getMessage()));
            }
        }
        batch.setPct(100); batch.setStatus("DONE"); batch.setInserted(inserted); batch.setSkipped(skipped); batch.setCompletedAt(OffsetDateTime.now()); batch.setSkippedJson(toJson(skippedRows)); importBatchRepository.save(batch);
        return row("jobId", batch.getJobId());
    }

    public Map<String, Object> importJobStatus(String jobId) {
        ImportBatchEntity batch = importBatchRepository.findByJobId(jobId).orElseThrow(() -> new IllegalArgumentException("Import job not found"));
        List<Map<String,Object>> skipped = new ArrayList<>();
        if (batch.getSkippedJson() != null && !batch.getSkippedJson().isBlank()) {
            try { skipped = objectMapper.readValue(batch.getSkippedJson(), new TypeReference<>(){}); } catch (Exception ignored) {}
        }
        return row("pct", batch.getPct(), "done", "DONE".equalsIgnoreCase(batch.getStatus()), "inserted", batch.getInserted(), "skipped", batch.getSkipped(), "skippedRows", skipped);
    }

    public byte[] studentImportTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Students");
            Row header = sheet.createRow(0);
            List<String> columns = List.of("Name", "Class", "Section", "AdmissionNo", "DateOfBirth", "Gender", "FatherName", "Phone", "Address", "BoardRegistrationNo");
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
        } catch (Exception e) { throw new IllegalStateException("Unable to generate template"); }
    }

    public Map<String, Object> feeStructureData(String academicYearId) {
        String yearId = academicYearId == null || academicYearId.isBlank() ? currentAcademicYearId() : academicYearId;
        AcademicYearEntity year = academicYearRepository.findById(yearId).orElse(currentAcademicYearEntity());
        List<Map<String,Object>> bands = feeBandRepository.findByAcademicYear_IdOrderByClassFromAscNameAsc(year.getId()).stream().map(this::bandRow).toList();
        return row("academicYearId", year.getId(), "academicYear", year.getLabel(), "bands", bands);
    }

    public Map<String, Object> createFeeStructureBand(Map<String, Object> request, AuthUser actor) {
        String name = str(request.get("name"), "").trim();
        if (name.isBlank()) throw new IllegalArgumentException("Band name is required");
        int classFrom = (int) longNum(request.get("classFrom"), 1);
        int classTo = (int) longNum(request.get("classTo"), classFrom);
        if (classTo < classFrom) throw new IllegalArgumentException("Class to must be greater than or equal to class from");
        List<String> schedules = toStringList(request.get("schedules"));
        if (schedules.isEmpty()) throw new IllegalArgumentException("At least one payment schedule is required");
        FeeBandEntity band = new FeeBandEntity();
        band.setId(UUID.randomUUID().toString());
        band.setName(name);
        band.setClassFrom(classFrom);
        band.setClassTo(classTo);
        band.setDiscount(num(request.get("discount"), 0));
        band.setActiveSchedulesCsv(String.join(",", schedules));
        band.setAcademicYear(currentAcademicYearEntity());
        band.setCreatedAt(OffsetDateTime.now()); band.setUpdatedAt(OffsetDateTime.now());
        feeBandRepository.save(band);
        return bandRow(band);
    }

    public Map<String, Object> updateFeeStructureBand(String id, Map<String, Object> request, AuthUser actor) {
        FeeBandEntity band = feeBandRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        String nextName = str(request.get("name"), band.getName()).trim();
        if (nextName.isBlank()) nextName = band.getName();
        int classFrom = (int) longNum(request.get("classFrom"), band.getClassFrom());
        int classTo = (int) longNum(request.get("classTo"), band.getClassTo());
        if (classTo < classFrom) throw new IllegalArgumentException("Class to must be greater than or equal to class from");
        band.setName(nextName);
        band.setClassFrom(classFrom);
        band.setClassTo(classTo);
        band.setDiscount(num(request.get("discount"), band.getDiscount()));
        List<String> schedules = toStringList(request.get("schedules"));
        if (!schedules.isEmpty()) band.setActiveSchedulesCsv(String.join(",", schedules));
        band.setUpdatedAt(OffsetDateTime.now());
        feeBandRepository.save(band);
        return bandRow(band);
    }

    public Map<String, Object> deleteFeeStructureBand(String id, AuthUser actor) {
        FeeBandEntity band = feeBandRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        feeItemRepository.deleteByBand_Id(id);
        feeBandRepository.delete(band);
        return row("removed", true, "bandId", id);
    }

    public Map<String, Object> addFeeStructureItem(Map<String, Object> request, AuthUser actor) { return addFeeItem(request); }
    public Map<String, Object> addFeeItem(Map<String, Object> request) {
        FeeBandEntity band = feeBandRepository.findById(str(request.get("bandId"), "")).orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        String name = str(firstPresent(request, "itemName"), "").trim();
        if (name.isBlank()) throw new IllegalArgumentException("Item name is required");
        FeeItemEntity item = new FeeItemEntity();
        item.setId(UUID.randomUUID().toString()); item.setBand(band); item.setName(name); item.setFrequency(str(request.get("frequency"), "Annual")); item.setAmount(toPaise(request.get("amount"))); item.setCreatedAt(OffsetDateTime.now()); item.setUpdatedAt(OffsetDateTime.now());
        feeItemRepository.save(item);
        return bandRow(band);
    }

    public Map<String, Object> updateFeeStructureItem(String id, Map<String, Object> request, AuthUser actor) {
        FeeItemEntity item = feeItemRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Fee item not found"));
        item.setName(str(firstPresent(request, "itemName"), item.getName()));
        item.setFrequency(str(request.get("frequency"), item.getFrequency()));
        item.setAmount(toPaise(request.get("amount")));
        item.setUpdatedAt(OffsetDateTime.now());
        feeItemRepository.save(item);
        return bandRow(item.getBand());
    }

    public Map<String, Object> deleteFeeStructureItem(String id, AuthUser actor) {
        FeeItemEntity item = feeItemRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Fee item not found"));
        String bandId = item.getBand().getId();
        feeItemRepository.delete(item);
        return row("removed", true, "bandId", bandId);
    }

    public Map<String, Object> patchFeeStructureBand(String id, Map<String, Object> request, AuthUser actor) {
        FeeBandEntity band = feeBandRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        if (request.containsKey("discount") || request.containsKey("bandDiscount")) band.setDiscount(num(firstPresent(request, "discount", "bandDiscount"), band.getDiscount()));
        List<String> schedules = toStringList(request.get("schedules")); if (!schedules.isEmpty()) band.setActiveSchedulesCsv(String.join(",", schedules));
        band.setUpdatedAt(OffsetDateTime.now());
        feeBandRepository.save(band);
        return bandRow(band);
    }

    public Map<String, Object> matchFeeStructureBand(String classId) {
        int sort = classSortOrder(classId);
        FeeBandEntity band = feeBandRepository.findFirstByAcademicYear_IdAndClassFromLessThanEqualAndClassToGreaterThanEqual(currentAcademicYearId(), sort, sort).orElse(null);
        return band == null ? row() : bandRow(band);
    }

    public byte[] exportFeeStructurePdf(String academicYearId, String format) { return simplePdf("Fee structure " + currentAcademicYear() + " | bands " + feeBandRepository.count()); }

    public Map<String, Object> createOrder(Map<String, Object> request) {
        CatalogOrderEntity e = new CatalogOrderEntity();
        e.setId("CK-" + (1000 + catalogOrderRepository.count() + 1));
        e.setSchool(defaultSchool());
        e.setCategory(str(request.get("category"), "STATIONERY"));
        e.setOrderData(toJson(row("title", str(request.get("title"), "Order"), "items", str(request.get("items"), "1 unit"))));
        e.setSubtotal(longNum(request.get("amount"), 0));
        e.setGst(0);
        e.setTotalAmount(longNum(request.get("amount"), 0));
        e.setStatus(str(request.get("status"), "DRAFT"));
        e.setEstimatedDelivery("3–4 weeks");
        e.setPlacedBy(1L);
        if (!"DRAFT".equalsIgnoreCase(e.getStatus())) e.setPlacedAt(OffsetDateTime.now());
        catalogOrderRepository.save(e);
        return catalogOrderListRow(e);
    }

    public Map<String, Object> savePlan(Map<String, Object> request) {
        AnnualPlanItemEntity e = new AnnualPlanItemEntity();
        e.setId(UUID.randomUUID().toString());
        e.setSchool(defaultSchool());
        e.setAcademicYear(currentAcademicYearEntity());
        e.setTermName(str(request.get("term"), "Term 1"));
        e.setCategory(str(request.get("category"), "Stationery"));
        e.setDescription(str(request.get("description"), e.getCategory()));
        e.setQuantity(str(request.get("quantity"), "100 units"));
        e.setEstimatedAmount(longNum(request.get("amount"), 0));
        e.setStatus(str(request.get("status"), "PLANNED").toUpperCase(Locale.ROOT));
        annualPlanItemRepository.save(e);
        return annualPlanItemRow(e);
    }

    public Map<String, Object> createFirefightingRequest(Map<String, Object> request) {
        FirefightingRequestEntity e = new FirefightingRequestEntity();
        e.setCode(nextFireCode());
        e.setSchool(defaultSchool());
        e.setTitle(str(request.get("title"), "Request"));
        e.setCategory(str(request.get("category"), "Other"));
        e.setDescription(str(firstPresent(request, "description", "summary"), ""));
        e.setEstimatedBudget(longNum(firstPresent(request, "estimatedBudget", "amount"), 0));
        e.setUrgency(str(request.get("urgency"), "MEDIUM"));
        e.setStatus("DRAFT");
        e.setRaisedBy(1L);
        firefightingRequestRepository.save(e);
        return fireRequestRow(e);
    }

    public Map<String, Object> decideFirefighting(String code, String action) {
        FirefightingRequestEntity entity = firefightingRequestRepository.findById(code).orElseThrow(() -> new IllegalArgumentException("Request not found"));
        entity.setStatus("approve".equalsIgnoreCase(action) ? "FULFILLED" : "REJECTED");
        firefightingRequestRepository.save(entity);
        return fireRequestRow(entity);
    }



    public List<Map<String, Object>> catalogCategories() {
        return List.of(
                row("id", "UNIFORMS", "emoji", "👕", "label", "Uniforms", "orderType", "Recurring", "description", "Full uniform sets by size and house"),
                row("id", "NOTEBOOKS", "emoji", "📘", "label", "Notebooks", "orderType", "Recurring", "description", "Ruled, unruled, graph and custom books"),
                row("id", "STATIONERY", "emoji", "✏️", "label", "Stationery", "orderType", "Recurring", "description", "Student stationery kits and classroom essentials"),
                row("id", "IDCARDS", "emoji", "🪪", "label", "ID Cards", "orderType", "One-time", "description", "Student and staff ID cards, lanyards and holders"),
                row("id", "HOUSEKEEPING", "emoji", "🧹", "label", "Housekeeping", "orderType", "Service", "description", "Cleaning consumables and support services"),
                row("id", "EVENTS", "emoji", "🎉", "label", "Events", "orderType", "One-time", "description", "Trophies, certificates, banners and event kits"),
                row("id", "HEALTH", "emoji", "🩺", "label", "Health", "orderType", "Service", "description", "Infirmary essentials and annual health services")
        );
    }

    public Map<String, Object> createCatalogOrder(Map<String, Object> request, AuthUser actor) {
        Long schoolId = resolveSchoolId(actor, request.get("schoolId") == null ? null : longNum(request.get("schoolId"), -1) < 0 ? null : longNum(request.get("schoolId"), -1));
        CatalogOrderEntity entity = new CatalogOrderEntity();
        entity.setId("CK-" + (1000 + catalogOrderRepository.count() + 1));
        entity.setSchool(resolveSchool(schoolId));
        entity.setCategory(str(request.get("category"), "STATIONERY").toUpperCase(Locale.ROOT));
        Map<String, Object> orderData = parseJsonMap(valueAsJson(request.get("orderData"), row("title", str(request.get("category"), "Order"), "items", str(request.get("items"), "1 unit"))));
        entity.setOrderData(valueAsJson(orderData, row("title", str(request.get("category"), "Order"), "items", str(request.get("items"), "1 unit"))));
        entity.setSubtotal(longNum(request.get("subtotal"), longNum(request.get("amount"), 0)));
        entity.setGst(longNum(request.get("gst"), 0));
        entity.setTotalAmount(longNum(request.get("totalAmount"), entity.getSubtotal() + entity.getGst()));
        entity.setRequiredByDate(parseNullableDate(str(request.get("requiredByDate"), "")));
        entity.setNotes(trimToNull(str(request.get("notes"), "")));
        entity.setStatus(str(request.get("status"), "DRAFT").toUpperCase(Locale.ROOT));
        entity.setEstimatedDelivery(defaultEstimatedDelivery(entity.getCategory()));
        entity.setPlacedBy(actor.userId());
        boolean designRequired = List.of("UNIFORMS", "NOTEBOOKS").contains(entity.getCategory().toUpperCase(Locale.ROOT));
        boolean superadminRequired = List.of("UNIFORMS", "NOTEBOOKS", "STATIONERY", "EVENTS").contains(entity.getCategory().toUpperCase(Locale.ROOT));
        entity.setDesignStatus(designRequired ? "PENDING" : "NOT_REQUIRED");
        entity.setSuperadminApprovalStatus(superadminRequired ? "NOT_SUBMITTED" : "NOT_REQUIRED");
        entity.setClassGroup(trimToNull(str(firstPresent(orderData, "classGroup", "class_group"), "")));
        entity.setLogoOnUniform(trimToNull(str(firstPresent(orderData, "logoOnUniform", "logo_on_uniform"), "")));
        entity.setNotebookCoverLogo(trimToNull(str(firstPresent(orderData, "coverLogo", "notebookCoverLogo"), "")));
        entity.setNotebookDeliveryMode(trimToNull(str(firstPresent(orderData, "delivery", "notebookDeliveryMode"), "")));
        entity.setNotebookSpineName(trimToNull(str(firstPresent(orderData, "schoolNameOnSpine", "notebookSpineName"), "")));
        entity.setStationeryPackType(trimToNull(str(firstPresent(orderData, "packType", "stationeryPackType"), "")));
        entity.setEventName(trimToNull(str(firstPresent(orderData, "eventName", "title"), "")));
        entity.setEventDate(parseNullableDate(str(firstPresent(orderData, "eventDate"), "")));
        if (!"DRAFT".equalsIgnoreCase(entity.getStatus())) entity.setPlacedAt(OffsetDateTime.now());
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    public Map<String, Object> placeCatalogOrder(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        assertSchoolOwnership("order", entity.getSchool().getId(), resolveSchoolId(actor, null));
        String category = String.valueOf(entity.getCategory()).toUpperCase(Locale.ROOT);
        if (List.of("UNIFORMS", "NOTEBOOKS").contains(category)) {
            entity.setStatus("DESIGN_APPROVAL");
            entity.setDesignStatus("PENDING");
            entity.setSuperadminApprovalStatus("NOT_SUBMITTED");
        } else if (List.of("STATIONERY", "EVENTS").contains(category)) {
            entity.setStatus("PROCESSING");
            entity.setDesignStatus("NOT_REQUIRED");
            entity.setSuperadminApprovalStatus("PENDING");
        } else {
            entity.setStatus("AWAITING_APPROVAL");
        }
        entity.setPlacedAt(OffsetDateTime.now());
        entity.setPlacedBy(actor.userId());
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    public Map<String, Object> updateCatalogOrderStatus(String orderId, Map<String, Object> request, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        assertSchoolOwnership("order", entity.getSchool().getId(), resolveSchoolId(actor, null));
        String newStatus = str(request.get("status"), entity.getStatus()).toUpperCase(Locale.ROOT);
        entity.setStatus(newStatus);
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }


    public List<Map<String, Object>> listOrdersPendingApproval(AuthUser actor) {
        return catalogOrderRepository.findAll().stream()
                .filter(e -> ("DESIGN_APPROVED_PROCESSING".equalsIgnoreCase(e.getStatus()) || "PROCESSING".equalsIgnoreCase(e.getStatus()))
                        && "PENDING".equalsIgnoreCase(String.valueOf(e.getSuperadminApprovalStatus())))
                .sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>(catalogOrderListRow(e));
                    row.put("schoolName", e.getSchool() != null ? e.getSchool().getName() : "—");
                    row.put("schoolId", e.getSchool() != null ? e.getSchool().getId() : null);
                    return row;
                })
                .toList();
    }

    public Map<String, Object> markCatalogOrderDesignApproved(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        assertSchoolOwnership("order", entity.getSchool().getId(), resolveSchoolId(actor, null));
        if (!List.of("UNIFORMS", "NOTEBOOKS").contains(String.valueOf(entity.getCategory()).toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Design approval is only supported for uniform and notebook orders.");
        }
        if (!"DESIGN_APPROVAL".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only orders in DESIGN_APPROVAL status can be marked design approved. Current status: " + entity.getStatus());
        }
        entity.setDesignStatus("APPROVED");
        entity.setSuperadminApprovalStatus("PENDING");
        entity.setStatus("DESIGN_APPROVED_PROCESSING");
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    public Map<String, Object> superadminApproveOrder(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!("DESIGN_APPROVED_PROCESSING".equalsIgnoreCase(entity.getStatus()) || "PROCESSING".equalsIgnoreCase(entity.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only orders in DESIGN_APPROVED_PROCESSING or PROCESSING status can be approved. Current status: " + entity.getStatus());
        }
        entity.setSuperadminApprovalStatus("APPROVED");
        entity.setStatus("APPROVED");
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    public Map<String, Object> superadminRejectOrder(String orderId, Map<String, Object> request, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!("DESIGN_APPROVED_PROCESSING".equalsIgnoreCase(entity.getStatus()) || "PROCESSING".equalsIgnoreCase(entity.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only orders in DESIGN_APPROVED_PROCESSING or PROCESSING status can be rejected. Current status: " + entity.getStatus());
        }
        entity.setSuperadminApprovalStatus("RETURNED");
        if (List.of("UNIFORMS", "NOTEBOOKS").contains(String.valueOf(entity.getCategory()).toUpperCase(Locale.ROOT))) {
            entity.setStatus("DESIGN_APPROVAL");
            entity.setDesignStatus("PENDING");
        } else {
            entity.setStatus("PROCESSING");
            entity.setDesignStatus("NOT_REQUIRED");
        }
        entity.setNotes(str(request.get("reason"), "Returned by Superadmin"));
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    public List<Map<String, Object>> listCatalogOrders(AuthUser actor) { return listCatalogOrders(actor, null); }

    public List<Map<String, Object>> listCatalogOrders(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return catalogOrderRepository.findBySchool_Id(schoolId).stream().sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()).map(this::catalogOrderListRow).toList();
    }

    public Map<String, Object> catalogOrderDetail(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (actor.role() != Role.SUPERADMIN) {
            assertSchoolOwnership("order", entity.getSchool().getId(), resolveSchoolId(actor, null));
        }
        return catalogOrderDetailRow(entity);
    }

    public Map<String, Object> catalogOrderStats(AuthUser actor) { return catalogOrderStats(actor, null); }

    public Map<String, Object> catalogOrderStats(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        List<CatalogOrderEntity> orders = catalogOrderRepository.findBySchool_Id(schoolId);
        long activeOrders = orders.stream().filter(o -> !List.of("DELIVERED", "DRAFT").contains(String.valueOf(o.getStatus()).toUpperCase(Locale.ROOT))).count();
        long termSpend = orders.stream().mapToLong(CatalogOrderEntity::getTotalAmount).sum();
        long activeServices = orders.stream().filter(o -> "ACTIVE".equalsIgnoreCase(o.getStatus())).count();
        long deliveredCount = orders.stream().filter(o -> "DELIVERED".equalsIgnoreCase(o.getStatus())).count();
        long termBudget = annualPlanItemRepository.findBySchool_Id(schoolId).stream().mapToLong(AnnualPlanItemEntity::getEstimatedAmount).sum();
        return row("activeOrders", activeOrders, "termSpend", termSpend, "termBudget", termBudget, "activeServices", activeServices, "deliveredCount", deliveredCount);
    }

    public Map<String, Object> listAnnualPlan(AuthUser actor) { return listAnnualPlan(actor, null); }
    public Map<String, Object> listAnnualPlan(AuthUser actor, Long requestedSchoolId) { return annualPlanPayload(resolveSchoolId(actor, requestedSchoolId)); }

    public Map<String, Object> saveAnnualPlanItem(Map<String, Object> request, AuthUser actor) {
        Long schoolId = resolveSchoolId(actor, request.get("schoolId") == null ? null : longNum(request.get("schoolId"), -1) < 0 ? null : longNum(request.get("schoolId"), -1));
        String id = trimToNull(str(request.get("id"), ""));
        AnnualPlanItemEntity item = id == null ? new AnnualPlanItemEntity() : annualPlanItemRepository.findById(id).orElseGet(AnnualPlanItemEntity::new);
        if (item.getId() == null) item.setId(UUID.randomUUID().toString());
        item.setSchool(resolveSchool(schoolId));
        item.setAcademicYear(currentAcademicYearEntity());
        item.setTermName(str(request.get("termName"), str(request.get("term"), "Term 1")));
        item.setCategory(str(request.get("category"), "STATIONERY"));
        item.setDescription(str(request.get("description"), item.getCategory()));
        item.setQuantity(str(request.get("quantity"), "100 units"));
        item.setEstimatedAmount(longNum(firstPresent(request, "estimatedAmount", "amount"), 0));
        item.setStatus(str(request.get("status"), "PLANNED").toUpperCase(Locale.ROOT));
        annualPlanItemRepository.save(item);
        return annualPlanItemRow(item);
    }

    public Map<String, Object> confirmAnnualPlan(AuthUser actor) { return row("ok", true, "message", "Annual plan confirmed and Custoking notified"); }

    public List<Map<String, Object>> listFireRequests(AuthUser actor) { return listFireRequests(actor, null); }
    public List<Map<String, Object>> listFireRequests(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return firefightingRequestRepository.findBySchool_Id(schoolId).stream().sorted(Comparator.comparing(FirefightingRequestEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()).map(this::fireRequestRow).toList();
    }

    public Map<String, Object> fireRequestStats(AuthUser actor) { return fireRequestStats(actor, null); }
    public Map<String, Object> fireRequestStats(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        List<FirefightingRequestEntity> rows = firefightingRequestRepository.findBySchool_Id(schoolId);
        long activeRequests = rows.stream().filter(r -> !List.of("FULFILLED", "REJECTED").contains(String.valueOf(r.getStatus()).toUpperCase(Locale.ROOT))).count();
        long totalValue = rows.stream().mapToLong(r -> r.getWinnerAmount() == null ? r.getEstimatedBudget() : r.getWinnerAmount()).sum();
        long custokingWins = rows.stream().filter(r -> "Custoking".equalsIgnoreCase(r.getWinnerVendor())).count();
        long fulfilled = rows.stream().filter(r -> "FULFILLED".equalsIgnoreCase(r.getStatus())).count();
        return row("activeRequests", activeRequests, "totalValue", totalValue, "custokingWins", custokingWins, "fulfilled", fulfilled);
    }

    public Map<String, Object> createFireRequest(Map<String, Object> request, AuthUser actor) {
        Long schoolId = resolveSchoolId(actor, request.get("schoolId") == null ? null : longNum(request.get("schoolId"), -1) < 0 ? null : longNum(request.get("schoolId"), -1));
        FirefightingRequestEntity e = new FirefightingRequestEntity();
        e.setCode(nextFireCode());
        e.setSchool(resolveSchool(schoolId));
        e.setTitle(str(request.get("title"), "Request"));
        e.setCategory(str(request.get("category"), "Other"));
        e.setUrgency(str(request.get("urgency"), "MEDIUM").toUpperCase(Locale.ROOT));
        e.setRequiredByDate(parseNullableDate(str(request.get("requiredByDate"), "")));
        e.setEstimatedBudget(longNum(request.get("estimatedBudget"), 0));
        e.setDescription(str(firstPresent(request, "description", "summary"), ""));
        e.setReferenceFileUrl(trimToNull(str(request.get("referenceFileUrl"), "")));
        e.setRaisedBy(actor.userId());
        e.setStatus("DRAFT");
        e.setCustokingCriteriaJson(toJson(custokingCriteria(e.getCategory())));
        firefightingRequestRepository.save(e);
        return fireRequestDetailRow(e);
    }

    public Map<String, Object> fireRequestDetail(String id, AuthUser actor) { return fireRequestDetail(id, actor, null); }
    public Map<String, Object> fireRequestDetail(String id, AuthUser actor, Long requestedSchoolId) {
        FirefightingRequestEntity e = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", e.getSchool().getId(), resolveSchoolId(actor, requestedSchoolId));
        return fireRequestDetailRow(e);
    }

    public Map<String, Object> addFireQuotation(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, null));
        FirefightingQuotationEntity q = new FirefightingQuotationEntity();
        q.setId(UUID.randomUUID().toString());
        q.setRequest(ff);
        q.setVendorName(str(request.get("vendorName"), "Vendor"));
        q.setAmount(longNum(request.get("amount"), 0));
        q.setDeliveryTimeline(str(request.get("deliveryTimeline"), ""));
        q.setNotes(trimToNull(str(request.get("notes"), "")));
        q.setDocumentUrl(trimToNull(str(request.get("documentUrl"), "")));
        q.setCustoking("Custoking".equalsIgnoreCase(q.getVendorName()));
        firefightingQuotationRepository.save(q);
        return quotationRow(q);
    }

    public Map<String, Object> deleteFireQuotation(String id, String quotationId, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, null));
        if (!"DRAFT".equalsIgnoreCase(ff.getStatus())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quotation can be removed only in DRAFT status");
        firefightingQuotationRepository.deleteById(quotationId);
        return row("ok", true);
    }

    public Map<String, Object> submitFireRequest(String id, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, null));
        if (firefightingQuotationRepository.findByRequest_Code(id).size() < 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum 2 quotations required");
        ff.setStatus("AWAITING_BURSAR");
        firefightingRequestRepository.save(ff);
        return fireRequestDetailRow(ff);
    }

    public Map<String, Object> approveFireBursar(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, null));
        ff.setBursarNote(trimToNull(str(request.get("note"), "")));
        ff.setBursarApprovedAt(OffsetDateTime.now());
        ff.setStatus("AWAITING_PRINCIPAL");
        firefightingRequestRepository.save(ff);
        return fireRequestDetailRow(ff);
    }

    public Map<String, Object> approveFirePrincipal(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, null));
        String qid = str(request.get("selectedQuotationId"), "");
        FirefightingQuotationEntity q = firefightingQuotationRepository.findById(qid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation not found"));
        ff.setPrincipalNote(trimToNull(str(request.get("note"), "")));
        ff.setPrincipalApprovedAt(OffsetDateTime.now());
        ff.setWinnerVendor(q.getVendorName());
        ff.setWinnerAmount(q.getAmount());
        ff.setStatus("APPROVED");
        firefightingRequestRepository.save(ff);
        return fireRequestDetailRow(ff);
    }

    public Map<String, Object> rejectFireRequest(String id, Map<String, Object> request, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, null));
        ff.setRejectedBy(str(request.get("rejectedBy"), actor.fullName()));
        ff.setRejectedReason(str(firstPresent(request, "reason", "rejectedReason"), ""));
        ff.setStatus("REJECTED");
        firefightingRequestRepository.save(ff);
        return fireRequestDetailRow(ff);
    }

    public Map<String, Object> fulfillFireRequest(String id, AuthUser actor) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, null));
        ff.setStatus("FULFILLED");
        firefightingRequestRepository.save(ff);
        return fireRequestDetailRow(ff);
    }

    public List<Map<String, Object>> pendingFireApprovals(AuthUser actor) { return pendingFireApprovals(actor, null); }
    public List<Map<String, Object>> pendingFireApprovals(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = resolveSchoolId(actor, requestedSchoolId);
        return firefightingRequestRepository.findBySchool_IdAndStatus(schoolId, "AWAITING_PRINCIPAL").stream().map(this::fireRequestDetailRow).toList();
    }

    public List<Map<String, Object>> fireRequestTimeline(String id, AuthUser actor) { return fireRequestTimeline(id, actor, null); }
    public List<Map<String, Object>> fireRequestTimeline(String id, AuthUser actor, Long requestedSchoolId) {
        FirefightingRequestEntity ff = firefightingRequestRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found"));
        assertSchoolOwnership("request", ff.getSchool().getId(), resolveSchoolId(actor, requestedSchoolId));
        return List.of(
                timeline("Request raised", ff.getCreatedAt(), null, "done"),
                timeline("Quotations submitted", ff.getStatus().equals("DRAFT") ? null : ff.getCreatedAt().plusHours(4), null, ff.getStatus().equals("DRAFT") ? "pending" : "done"),
                timeline("Bursar approved", ff.getBursarApprovedAt(), ff.getBursarNote(), ff.getBursarApprovedAt() != null ? "done" : ("AWAITING_BURSAR".equals(ff.getStatus()) ? "active" : "pending")),
                timeline("Principal approved", ff.getPrincipalApprovedAt(), ff.getPrincipalNote(), ff.getPrincipalApprovedAt() != null ? "done" : ("AWAITING_PRINCIPAL".equals(ff.getStatus()) ? "active" : "pending")),
                row("title", "Custoking coordinating", "meta", ff.getWinnerVendor() == null ? "Pending vendor finalisation" : ff.getWinnerVendor(), "note", null, "state", List.of("APPROVED", "FULFILLED").contains(ff.getStatus()) ? "done" : "pending"),
                row("title", "Delivery & invoice", "meta", ff.getStatus(), "note", null, "state", "FULFILLED".equals(ff.getStatus()) ? "done" : "pending")
        );
    }

    public Map<String, Object> addTimetableEntry(Map<String, Object> request) { return row("ok", true, "entry", request); }
    public Map<String, Object> addStaff(Map<String, Object> request) {
        StaffMemberEntity e = new StaffMemberEntity(); e.setName(str(request.get("name"), "")); e.setDesignation(str(request.get("designation"), "")); e.setDepartment(str(request.get("department"), "")); e.setMonthlySalary(longNum(request.get("monthlySalary"), 0)); e.setPayrollStatus(str(request.get("payrollStatus"), "Pending")); staffMemberRepository.save(e); return staffRow(e);
    }

    @Transactional
    public String nextOrderId() {
        SuperadminOrderSeqEntity seq = saSeqRepository.findById("SINGLETON")
                .orElseGet(() -> {
                    SuperadminOrderSeqEntity s = new SuperadminOrderSeqEntity();
                    saSeqRepository.save(s);
                    return s;
                });
        seq.setOrderSeq(seq.getOrderSeq() + 1);
        saSeqRepository.save(seq);
        return "CK-2025-0" + seq.getOrderSeq();
    }

    @Transactional
    public String nextInvoiceId() {
        SuperadminOrderSeqEntity seq = saSeqRepository.findById("SINGLETON")
                .orElseGet(() -> {
                    SuperadminOrderSeqEntity s = new SuperadminOrderSeqEntity();
                    saSeqRepository.save(s);
                    return s;
                });
        seq.setInvoiceSeq(seq.getInvoiceSeq() + 1);
        saSeqRepository.save(seq);
        return "INV-2025-0" + seq.getInvoiceSeq();
    }

    public List<Map<String, Object>> listAllOrdersForSuperadmin() {
        return catalogOrderRepository.findAll().stream()
                .sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>(catalogOrderListRow(e));
                    row.put("schoolName", e.getSchool() != null ? e.getSchool().getName() : "—");
                    row.put("schoolId", e.getSchool() != null ? e.getSchool().getId() : null);
                    return row;
                })
                .toList();
    }

    public Map<String, Object> allOrdersStatsForSuperadmin() {
        List<CatalogOrderEntity> all = catalogOrderRepository.findAll();
        long total = all.size();
        long newReq = all.stream().filter(o -> "AWAITING_APPROVAL".equalsIgnoreCase(o.getStatus())).count();
        long inProgress = all.stream().filter(o -> List.of("PROCESSING", "IN_PROGRESS", "APPROVED").contains(String.valueOf(o.getStatus()).toUpperCase(Locale.ROOT))).count();
        long delivered = all.stream().filter(o -> "DELIVERED".equalsIgnoreCase(o.getStatus())).count();
        long gmv = all.stream().mapToLong(CatalogOrderEntity::getTotalAmount).sum();
        return row("total", total, "newRequests", newReq, "inProgress", inProgress, "delivered", delivered, "gmv", gmv);
    }

    public Map<String, Object> superadminUpdateOrderStatus(String orderId, Map<String, Object> request) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        String newStatus = str(request.get("status"), entity.getStatus()).toUpperCase(Locale.ROOT);
        entity.setStatus(newStatus);
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    public Map<String, Object> superadminCreateOrder(Map<String, Object> request) {
        String newId = nextOrderId();
        CatalogOrderEntity entity = new CatalogOrderEntity();
        entity.setId(newId);
        Long schoolId = longNum(request.get("schoolId"), -1L);
        entity.setSchool(schoolId > 0 ? schoolRepository.findById(schoolId).orElse(null) : null);
        entity.setCategory(str(request.get("category"), "CUSTOM").toUpperCase(Locale.ROOT));
        entity.setOrderData(valueAsJson(request.get("orderData"), row("title", str(request.get("category"), "Order"))));
        entity.setSubtotal(longNum(request.get("subtotal"), 0));
        entity.setGst(longNum(request.get("gst"), 0));
        entity.setTotalAmount(longNum(request.get("totalAmount"), entity.getSubtotal() + entity.getGst()));
        entity.setRequiredByDate(parseNullableDate(str(request.get("requiredByDate"), "")));
        entity.setNotes(trimToNull(str(request.get("notes"), "")));
        entity.setStatus("AWAITING_APPROVAL");
        entity.setEstimatedDelivery(defaultEstimatedDelivery(entity.getCategory()));
        entity.setPlacedAt(OffsetDateTime.now());
        catalogOrderRepository.save(entity);
        Map<String, Object> result = new LinkedHashMap<>(catalogOrderDetailRow(entity));
        result.put("schoolName", entity.getSchool() != null ? entity.getSchool().getName() : "—");
        result.put("schoolId", entity.getSchool() != null ? entity.getSchool().getId() : null);
        return result;
    }

    public List<Map<String, Object>> listSuperadminInvoices() {
        return saInvoiceRepository.findAllByOrderByCreatedAtDesc().stream().map(this::saInvoiceRow).toList();
    }

    public Map<String, Object> superadminInvoiceStats() {
        List<SuperadminInvoiceEntity> all = saInvoiceRepository.findAllByOrderByCreatedAtDesc();
        long paid = all.stream().filter(i -> "Paid".equalsIgnoreCase(i.getStatus())).count();
        long pending = all.stream().filter(i -> "Awaiting payment".equalsIgnoreCase(i.getStatus())).count();
        long total = all.stream().mapToLong(SuperadminInvoiceEntity::getTotal).sum();
        return row("sentThisMonth", (long) all.size(), "paid", paid, "pending", pending, "totalInvoiced", total);
    }

    public Map<String, Object> findInvoiceByOrderRef(String orderRef) {
        return saInvoiceRepository.findByOrderRefOrderByCreatedAtDesc(orderRef).stream().findFirst().map(this::saInvoiceRow).orElse(null);
    }

    public Map<String, Object> createSuperadminInvoice(Map<String, Object> request) {
        String id = nextInvoiceId();
        SuperadminInvoiceEntity e = new SuperadminInvoiceEntity();
        e.setId(id);
        e.setOrderRef(str(request.get("orderRef"), ""));
        e.setSchool(str(request.get("school"), ""));
        e.setSchoolId(request.get("schoolId") != null ? longNum(request.get("schoolId"), 0L) : null);
        e.setDescription(str(request.get("description"), ""));
        e.setQty((int) longNum(request.get("qty"), 1));
        e.setRate(longNum(request.get("rate"), 0));
        e.setAmount(longNum(request.get("amount"), (long) e.getQty() * e.getRate()));
        e.setGstAmount(Math.round(e.getAmount() * 0.12));
        e.setTotal(e.getAmount() + e.getGstAmount());
        e.setStatus("Awaiting payment");
        e.setIssuedAt(todayString());
        e.setDueAt(in14DaysString());
        e.setNotes(trimToNull(str(request.get("notes"), "")));
        saInvoiceRepository.save(e);
        return saInvoiceRow(e);
    }

    public Map<String, Object> updateSuperadminInvoice(String id, Map<String, Object> request) {
        SuperadminInvoiceEntity e = saInvoiceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (request.containsKey("description")) e.setDescription(str(request.get("description"), ""));
        if (request.containsKey("qty")) e.setQty((int) longNum(request.get("qty"), e.getQty()));
        if (request.containsKey("rate")) e.setRate(longNum(request.get("rate"), e.getRate()));
        if (request.containsKey("school")) e.setSchool(str(request.get("school"), e.getSchool()));
        if (request.containsKey("status")) e.setStatus(str(request.get("status"), e.getStatus()));
        long amount = (long) e.getQty() * e.getRate();
        e.setAmount(amount);
        e.setGstAmount(Math.round(amount * 0.12));
        e.setTotal(e.getAmount() + e.getGstAmount());
        saInvoiceRepository.save(e);
        return saInvoiceRow(e);
    }

    public List<Map<String, Object>> listSchoolsWithStats() {
        List<CatalogOrderEntity> allOrders = catalogOrderRepository.findAll();
        return schoolRepository.findAll().stream()
                .sorted(Comparator.comparing(SchoolEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .map(school -> {
                    List<CatalogOrderEntity> schoolOrders = allOrders.stream()
                            .filter(o -> o.getSchool() != null && school.getId().equals(o.getSchool().getId()))
                            .toList();
                    long gmv = schoolOrders.stream().mapToLong(CatalogOrderEntity::getTotalAmount).sum();
                    AppUserEntity admin = userRepository.findFirstByRoleIgnoreCaseAndBranchId("ADMIN", school.getId()).orElse(null);
                    return row(
                            "id", school.getId(),
                            "name", school.getName(),
                            "shortCode", school.getShortCode(),
                            "city", school.getCity() == null ? "" : school.getCity(),
                            "active", school.isActive(),
                            "adminEmail", admin == null ? "" : admin.getEmail(),
                            "ordersYTD", (long) schoolOrders.size(),
                            "gmvYTD", gmv,
                            "erpSince", school.getCreatedAt() == null ? "" : school.getCreatedAt().getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + school.getCreatedAt().getYear()
                    );
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> saInvoiceRow(SuperadminInvoiceEntity e) {
        return row("id", e.getId(), "orderRef", e.getOrderRef(), "school", e.getSchool(),
                "schoolId", e.getSchoolId(), "description", e.getDescription(),
                "qty", e.getQty(), "rate", e.getRate(), "amount", e.getAmount(),
                "gstAmount", e.getGstAmount(), "total", e.getTotal(),
                "status", e.getStatus(), "issuedAt", e.getIssuedAt(), "dueAt", e.getDueAt(),
                "notes", e.getNotes());
    }

    private String todayString() { return LocalDate.now().toString(); }
    private String in14DaysString() { return LocalDate.now().plusDays(14).toString(); }

    private SchoolEntity defaultSchool(){ return schoolRepository.findByShortCodeIgnoreCase("DEMO").orElseGet(() -> schoolRepository.findAll().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No school available"))); }
    private SchoolEntity resolveSchool(Long schoolId){ return schoolRepository.findById(schoolId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found")); }
    private void ensureSchoolSections(SchoolEntity school, Integer classCountInput, Integer sectionCountInput) {
        if (school == null) return;
        int classCount = Math.max(1, Math.min(12, classCountInput == null ? 12 : classCountInput));
        int sectionCount = Math.max(1, Math.min(26, sectionCountInput == null ? 2 : sectionCountInput));
        List<SchoolClassEntity> classes = classRepository.findAllByOrderBySortOrderAsc();
        if (classes.isEmpty()) {
            for (int i = 1; i <= 12; i++) {
                SchoolClassEntity schoolClass = new SchoolClassEntity();
                schoolClass.setId(String.valueOf(i));
                schoolClass.setName("Class " + i);
                schoolClass.setSortOrder(i);
                classRepository.save(schoolClass);
            }
            classes = classRepository.findAllByOrderBySortOrderAsc();
        }
        List<SchoolClassEntity> scopedClasses = classes.stream()
                .sorted(Comparator.comparingInt(SchoolClassEntity::getSortOrder))
                .limit(classCount)
                .toList();
        for (SchoolClassEntity schoolClass : scopedClasses) {
            for (int idx = 0; idx < sectionCount; idx++) {
                char sectionLetter = (char) ('A' + idx);
                getOrCreateSectionForSchool(school, schoolClass, String.valueOf(sectionLetter));
            }
        }
    }
    private SchoolSectionEntity getOrCreateSectionForSchool(SchoolEntity school, SchoolClassEntity schoolClass, String requestedSectionName) {
        if (school == null) throw new IllegalArgumentException("School not found");
        if (schoolClass == null) throw new IllegalArgumentException("Class not found");
        String sectionName = str(requestedSectionName, "A").trim();
        if (sectionName.isBlank()) sectionName = "A";
        final String normalizedSectionName = sectionName.toUpperCase(Locale.ROOT);
        return sectionRepository.findBySchool_IdAndSchoolClass_IdAndNameIgnoreCase(school.getId(), schoolClass.getId(), normalizedSectionName)
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
    private Long resolveSchoolId(AuthUser actor, Long schoolId){ if (actor.role() == Role.SUPERADMIN) return schoolId != null ? schoolId : defaultSchool().getId(); if (actor.branchId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No school mapped for user"); return actor.branchId(); }
    private void assertSchoolOwnership(String entityLabel, Long entitySchoolId, Long actorSchoolId) { if (actorSchoolId != null && entitySchoolId != null && !actorSchoolId.equals(entitySchoolId)) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this " + entityLabel); } }
    private LocalDate parseNullableDate(String input){ if (input == null || input.isBlank()) return null; return LocalDate.parse(input); }
    private String valueAsJson(Object raw, Object fallback){ try { return objectMapper.writeValueAsString(raw == null ? fallback : raw); } catch (Exception e) { return toJson(fallback); } }
    private Map<String,Object> parseJsonMap(String json){ if (json == null || json.isBlank()) return new LinkedHashMap<>(); try { return objectMapper.readValue(json, new TypeReference<Map<String,Object>>(){}); } catch (Exception e) { return new LinkedHashMap<>(); } }
    private Object firstPresent(Map<String,Object> request, String... keys){ for (String k: keys) if (request.containsKey(k) && request.get(k) != null) return request.get(k); return null; }
    private String defaultEstimatedDelivery(String category){ return switch (String.valueOf(category).toUpperCase(Locale.ROOT)) { case "UNIFORMS" -> "3–4 weeks"; case "NOTEBOOKS", "STATIONERY" -> "1–2 weeks"; case "IDCARDS" -> "10 days"; default -> "2–3 weeks"; }; }
    private String nextFireCode(){ return String.format("FF-%03d", firefightingRequestRepository.findAll().stream().map(FirefightingRequestEntity::getCode).map(c -> c.replaceAll("\\D+", "")).filter(s -> !s.isBlank()).mapToInt(Integer::parseInt).max().orElse(2) + 1); }
    private Map<String,Object> custokingCriteria(String category){ boolean met = List.of("Furniture & fixtures", "Lab equipment", "Sports & playground", "Services & AMC", "Events & occasions", "Health").contains(category); return row("met", met, "details", List.of("Category supported", "Deadline feasible", "Quantity feasible", "Budget aligned", "GST invoice ready", "Delivery support")); }
    private Map<String,Object> timeline(String title, OffsetDateTime at, String note, String state){ return row("title", title, "meta", at == null ? "Pending" : at.toLocalDate().toString(), "note", note, "state", state); }
    // helpers
    private AcademicYearEntity currentAcademicYearEntity() { return academicYearRepository.findFirstByActiveTrue().orElseThrow(() -> new IllegalArgumentException("No active academic year configured")); }
    private String currentAcademicYearId() { return currentAcademicYearEntity().getId(); }
    private String currentAcademicYear() { return currentAcademicYearEntity().getLabel(); }
    private boolean blankOr(String filter, String value) { return filter == null || filter.isBlank() || filter.equalsIgnoreCase("All") || filter.equalsIgnoreCase(value); }
    private LocalDate parseDate(String input) { if (input == null || input.isBlank() || "today".equalsIgnoreCase(input)) return LocalDate.now(); return LocalDate.parse(input); }
    private long feeOverdueCount(String yearId) { return feeAssignmentRepository.findAll().stream().filter(a -> a.getAcademicYear()!=null && yearId.equals(a.getAcademicYear().getId()) && a.getNetPayable() > a.getPaidAmount()).count(); }
    private long feeOverdueCount(String yearId, Long schoolId) { return feeAssignmentRepository.findAll().stream().filter(a -> a.getAcademicYear()!=null && yearId.equals(a.getAcademicYear().getId()) && a.getStudent()!=null && a.getStudent().getSchool()!=null && schoolId.equals(a.getStudent().getSchool().getId()) && a.getNetPayable() > a.getPaidAmount()).count(); }
    private Map<String, Object> buildFeesModule(String yearId) { return buildFeesModule(yearId, null); }
    private Map<String, Object> buildFeesModule(String yearId, Long schoolId) {
        java.util.stream.Stream<FeeAssignmentEntity> assignments = feeAssignmentRepository.findAll().stream().filter(a -> a.getAcademicYear()!=null && yearId.equals(a.getAcademicYear().getId()) && a.getStudent()!=null && a.getBand()!=null);
        if (schoolId != null) assignments = assignments.filter(a -> a.getStudent().getSchool()!=null && schoolId.equals(a.getStudent().getSchool().getId()));
        List<FeeAssignmentEntity> scoped = assignments.toList();
        long collected = paymentRecordRepository.findAll().stream().filter(p -> p.getStudent()!=null && (schoolId == null || (p.getStudent().getSchool()!=null && schoolId.equals(p.getStudent().getSchool().getId())))).mapToLong(PaymentRecordEntity::getAmount).sum();
        long target = scoped.stream().mapToLong(FeeAssignmentEntity::getNetPayable).sum();
        List<Map<String,Object>> records = scoped.stream().map(a -> row("studentId", a.getStudent().getId(), "studentName", a.getStudent().getFullName(), "planName", a.getBand().getName(), "schedule", a.getSchedule(), "dueAmount", Math.max(a.getNetPayable() - a.getPaidAmount(), 0), "totalAnnualFee", bandTotal(a.getBand().getId()), "paidAmount", a.getPaidAmount())).toList();
        return row("summary", row("collected", collected, "target", target), "records", records);
    }
    private String latestPaymentId(Long studentId) { return paymentRecordRepository.findByStudent_IdOrderByPaidAtDesc(studentId).stream().findFirst().map(PaymentRecordEntity::getId).orElse(""); }
    private long bandTotal(String bandId) { return feeItemRepository.findByBand_IdOrderByCreatedAtAsc(bandId).stream().mapToLong(FeeItemEntity::getAmount).sum(); }
    private long calculateNetPayable(long total, double bandDiscount, double manualDiscount, double surcharge, String schedule) {
        long bandAmt = Math.round(total * bandDiscount / 100.0);
        long manualAmt = Math.round(total * manualDiscount / 100.0);
        long surchargeAmt = "Annual".equalsIgnoreCase(schedule) ? 0 : Math.round(total * surcharge / 100.0);
        return total - bandAmt - manualAmt + surchargeAmt;
    }
    private Map<String, Object> staffRow(StaffMemberEntity e){ return row("id", e.getId(), "name", e.getName(), "designation", e.getDesignation(), "department", e.getDepartment(), "monthlySalary", e.getMonthlySalary(), "payrollStatus", e.getPayrollStatus()); }
    private Map<String, Object> catalogRow(CatalogItemEntity e){ return row("title", e.getTitle(), "subtitle", e.getSubtitle(), "icon", e.getIcon(), "orderType", e.getOrderType(), "sampleAmount", e.getSampleAmount()); }
    private Map<String, Object> orderRow(SupplyOrderEntity e){ return row("code", e.getCode(), "title", e.getTitle(), "category", e.getCategory(), "items", e.getItems(), "amount", e.getAmount(), "status", e.getStatus(), "date", e.getOrderDate().toString(), "action", e.getActionLabel()); }
    private Map<String, Object> annualPlanRow(AnnualPlanEntity e){ return row("term", e.getTermName(), "category", e.getCategory(), "status", e.getStatus(), "quantity", e.getQuantity(), "amount", e.getAmount()); }
    private Map<String, Object> catalogOrderListRow(CatalogOrderEntity e){ Map<String,Object> data = parseJsonMap(e.getOrderData()); return row("id", e.getId(), "code", e.getId(), "category", e.getCategory(), "description", str(firstPresent(data, "title", "description"), e.getCategory()), "title", str(firstPresent(data, "title", "description"), e.getCategory()), "items", str(firstPresent(data, "items", "quantitySummary"), "—"), "totalAmount", e.getTotalAmount(), "amount", e.getTotalAmount(), "status", e.getStatus(), "placedAt", e.getPlacedAt() == null ? null : e.getPlacedAt().toString(), "estimatedDelivery", e.getEstimatedDelivery(), "date", e.getCreatedAt().toLocalDate().toString(), "action", "Track"); }
    private Map<String, Object> catalogOrderDetailRow(CatalogOrderEntity e){ Map<String,Object> base = new LinkedHashMap<>(catalogOrderListRow(e)); base.put("orderData", parseJsonMap(e.getOrderData())); base.put("subtotal", e.getSubtotal()); base.put("gst", e.getGst()); base.put("requiredByDate", e.getRequiredByDate() == null ? null : e.getRequiredByDate().toString()); base.put("notes", e.getNotes()); base.put("classGroup", e.getClassGroup()); base.put("logoOnUniform", e.getLogoOnUniform()); base.put("notebookCoverLogo", e.getNotebookCoverLogo()); base.put("notebookDeliveryMode", e.getNotebookDeliveryMode()); base.put("notebookSpineName", e.getNotebookSpineName()); base.put("stationeryPackType", e.getStationeryPackType()); base.put("eventName", e.getEventName()); base.put("eventDate", e.getEventDate() == null ? null : e.getEventDate().toString()); base.put("designStatus", e.getDesignStatus()); base.put("superadminApprovalStatus", e.getSuperadminApprovalStatus()); return base; }
    private Map<String, Object> annualPlanItemRow(AnnualPlanItemEntity e){ return row("id", e.getId(), "term", e.getTermName(), "termName", e.getTermName(), "category", e.getCategory(), "description", e.getDescription(), "status", e.getStatus(), "quantity", e.getQuantity(), "amount", e.getEstimatedAmount(), "estimatedAmount", e.getEstimatedAmount(), "linkedOrderId", e.getLinkedOrderId()); }
    private Map<String, Object> annualPlanPayload(Long schoolId){ List<Map<String,Object>> terms = annualPlanItemRepository.findBySchool_Id(schoolId).stream().sorted(Comparator.comparing(AnnualPlanItemEntity::getTermName, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(AnnualPlanItemEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))).map(this::annualPlanItemRow).toList(); long total = terms.stream().mapToLong(t -> longNum(t.get("estimatedAmount"), 0)).sum(); long ordered = terms.stream().filter(t -> "ORDERED".equals(String.valueOf(t.get("status")))).count(); int completion = terms.isEmpty() ? 0 : (int) Math.round((ordered * 100.0) / terms.size()); return row("completionPercent", completion, "academicYears", List.of(currentAcademicYear()), "terms", terms, "summary", row("total", total, "pendingCount", terms.stream().filter(t -> !"ORDERED".equals(String.valueOf(t.get("status")))).count(), "perStudentCost", total / Math.max(1, scopedStudents(schoolId).size()), "vsLastYearPercent", 12)); }
    private Map<String, Object> quotationRow(FirefightingQuotationEntity q){ return row("id", q.getId(), "vendorName", q.getVendorName(), "amount", q.getAmount(), "deliveryTimeline", q.getDeliveryTimeline(), "notes", q.getNotes(), "documentUrl", q.getDocumentUrl(), "isCustoking", q.isCustoking(), "isRecommended", q.isRecommended(), "createdAt", q.getCreatedAt() == null ? null : q.getCreatedAt().toString()); }
    private Map<String, Object> fireRequestRow(FirefightingRequestEntity e){ List<Map<String,Object>> quotes = firefightingQuotationRepository.findByRequest_Code(e.getCode()).stream().map(this::quotationRow).toList(); long best = quotes.stream().mapToLong(q -> longNum(q.get("amount"), 0)).min().orElse(e.getEstimatedBudget()); return row("code", e.getCode(), "title", e.getTitle(), "category", e.getCategory(), "summary", e.getDescription(), "description", e.getDescription(), "amount", best, "estimatedBudget", e.getEstimatedBudget(), "quotesCount", quotes.size(), "winner", e.getWinnerVendor(), "winnerVendor", e.getWinnerVendor(), "winnerAmount", e.getWinnerAmount(), "status", e.getStatus(), "date", e.getCreatedAt() == null ? null : e.getCreatedAt().toLocalDate().toString(), "urgency", e.getUrgency(), "requiredByDate", e.getRequiredByDate() == null ? null : e.getRequiredByDate().toString()); }
    private Map<String, Object> fireRequestDetailRow(FirefightingRequestEntity e){ Map<String,Object> m = new LinkedHashMap<>(fireRequestRow(e)); m.put("quotations", firefightingQuotationRepository.findByRequest_Code(e.getCode()).stream().map(this::quotationRow).toList()); m.put("bursarNote", e.getBursarNote()); m.put("principalNote", e.getPrincipalNote()); m.put("rejectedReason", e.getRejectedReason()); m.put("custokingCriteria", parseJsonMap(e.getCustokingCriteriaJson())); return m; }
    private Map<String, Object> fireOrderRowNew(FirefightingRequestEntity e){ return row("code", e.getCode(), "title", e.getTitle(), "category", e.getCategory(), "via", e.getWinnerVendor() == null ? "Custoking" : e.getWinnerVendor(), "amount", e.getWinnerAmount() == null ? e.getEstimatedBudget() : e.getWinnerAmount(), "status", "FULFILLED".equalsIgnoreCase(e.getStatus()) ? "Delivered" : "Approved", "date", e.getCreatedAt() == null ? null : e.getCreatedAt().toLocalDate().toString()); }
    private Map<String, Object> studentListRow(StudentEntity s) {
        String fullName = s.getFullName() == null ? "Student" : s.getFullName();
        String initials = Arrays.stream(fullName.split(" ")).filter(v -> !v.isBlank()).limit(2).map(v -> v.substring(0,1).toUpperCase()).collect(Collectors.joining());
        String className = s.getSchoolClass() == null ? "" : s.getSchoolClass().getName();
        String sectionName = s.getSection() == null ? "" : s.getSection().getName();
        String academicYear = s.getAcademicYear() == null ? "" : s.getAcademicYear().getLabel();
        return row("id", s.getId(), "name", fullName, "fullName", fullName, "avatarInitials", initials, "photoUrl", s.getPhotoUrl(), "className", className, "sectionName", sectionName, "classSection", className.replace("Class ", "") + (sectionName.isBlank() ? "" : "-" + sectionName), "academicYear", academicYear, "admissionNumber", s.getAdmissionNo(), "rollNo", s.getRollNo(), "fatherName", s.getFatherName(), "fatherContact", s.getFatherContact(), "feeStatus", s.getFeeStatus(), "attendancePercent", s.getAttendancePercent() == null ? 0 : round(s.getAttendancePercent()));
    }
    private Map<String, Object> studentDetailRow(StudentEntity s) {
        Map<String, Object> map = new LinkedHashMap<>(studentListRow(s));
        map.put("dateOfBirth", s.getDob() == null ? null : s.getDob().toString());
        map.put("gender", s.getGender());
        map.put("boardRegistrationNumber", s.getBoardRegNo());
        map.put("motherName", s.getMotherName());
        map.put("address", row("houseNumber", s.getHouseNumber(), "street", s.getStreet(), "locality", s.getLocality(), "city", s.getCity(), "state", s.getState(), "pinCode", s.getPinCode(), "full", s.getAddress()));
        return map;
    }
    private List<String> bandActiveSchedules(FeeBandEntity band) { return splitCsv(band == null ? null : band.getActiveSchedulesCsv()); }
    private Map<String, Object> bandRow(FeeBandEntity band) {
        List<String> schedules = bandActiveSchedules(band);
        long total = bandTotal(band.getId());
        List<Map<String,Object>> items = feeItemRepository.findByBand_IdOrderByCreatedAtAsc(band.getId()).stream().map(item -> row("id", item.getId(), "name", item.getName(), "frequency", item.getFrequency(), "amount", item.getAmount(), "percentOfTotal", total == 0 ? 0 : Math.round(item.getAmount() * 100.0 / total), "createdAt", item.getCreatedAt().toString(), "updatedAt", item.getUpdatedAt().toString())).toList();
        return row("id", band.getId(), "name", band.getName(), "groupName", band.getName(), "classFrom", band.getClassFrom(), "classTo", band.getClassTo(), "academicYearId", band.getAcademicYear().getId(), "academicYear", band.getAcademicYear().getLabel(), "discount", round(band.getDiscount()), "activeSchedules", schedules, "allowedSchedules", schedules, "items", items, "annualTotal", total, "createdAt", band.getCreatedAt().toString(), "updatedAt", band.getUpdatedAt().toString());
    }
    private Map<String, Object> assignmentRow(FeeAssignmentEntity a) { return row("id", a.getId(), "studentId", a.getStudent().getId(), "bandId", a.getBand().getId(), "schedule", a.getSchedule(), "bandDiscount", a.getBandDiscount(), "manualDiscount", a.getManualDiscount(), "surcharge", a.getSurcharge(), "netPayable", a.getNetPayable(), "paidAmount", a.getPaidAmount()); }
    private String joinAddress(String... parts) { return Arrays.stream(parts).filter(v -> v != null && !v.isBlank()).collect(Collectors.joining(", ")); }
    private Map<String, Object> normalizeImportRow(Map<String, Object> row) { return row("name", firstPresentValue(row, "Name", "name"), "className", firstPresentValue(row, "Class", "class"), "sectionName", firstPresentValue(row, "Section", "section"), "admissionNo", firstPresentValue(row, "AdmissionNo", "admissionNo", "Admission No"), "dateOfBirth", firstPresentValue(row, "DateOfBirth", "dateOfBirth"), "gender", firstPresentValue(row, "Gender", "gender"), "fatherName", firstPresentValue(row, "FatherName", "fatherName"), "phone", firstPresentValue(row, "Phone", "phone"), "address", firstPresentValue(row, "Address", "address"), "boardRegistrationNo", firstPresentValue(row, "BoardRegistrationNo", "boardRegistrationNo")); }
    private String firstPresentValue(Map<String,Object> row, String... keys) { for (String k : keys) { for (Map.Entry<String,Object> e : row.entrySet()) if (e.getKey().equalsIgnoreCase(k) && e.getValue()!=null) return String.valueOf(e.getValue()).trim(); } return ""; }
    private String toJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; } }
    private List<String> splitCsv(String csv) { return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList(); }
    private List<String> toStringList(Object value) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().map(String::valueOf).filter(v -> !v.isBlank()).distinct().toList(); }
    private long toPaise(Object value) { if (value == null) return 0; if (value instanceof Number n) { long raw = Math.round(n.doubleValue()); return raw > 100000 ? raw : raw * 100; } String s = String.valueOf(value).replace(",", "").trim(); if (s.isBlank()) return 0; double d = Double.parseDouble(s); return d > 100000 ? Math.round(d) : Math.round(d * 100); }
    private Map<String, Object> row(Object... kv) { LinkedHashMap<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]); return map; }
    @SuppressWarnings("unchecked") private Map<String,Object> castMap(Object value){ return value == null ? new LinkedHashMap<>() : (Map<String,Object>) value; }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> castListMap(Object value){ return value == null ? List.of() : (List<Map<String,Object>>) value; }
    private String str(Object value, String fallback){ return value == null ? fallback : String.valueOf(value); }
    private long longNum(Object value, long fallback){ if (value == null) return fallback; if (value instanceof Number n) return n.longValue(); try { return Long.parseLong(String.valueOf(value).replace(",", "").trim()); } catch (Exception e) { return fallback; } }
    private double num(Object value, double fallback){ if (value == null) return fallback; if (value instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(String.valueOf(value).replace(",", "").trim()); } catch (Exception e) { return fallback; } }
    private double round(double d){ return Math.round(d * 10.0) / 10.0; }
    private int classSortOrder(String classId){ String digits = String.valueOf(classId).replaceAll("\\D+", ""); if (digits.isBlank()) return 0; return Integer.parseInt(digits); }
    private String indian(long paise){ return String.format(Locale.ENGLISH, "%,d", paise / 100); }
    private byte[] simplePdf(String content) { String safe = content.replace("(", "[").replace(")", "]"); String pdf = "%PDF-1.4\n1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n2 0 obj<< /Type /Pages /Count 1 /Kids [3 0 R] >>endobj\n3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>endobj\n4 0 obj<< /Length 120 >>stream\nBT /F1 12 Tf 36 740 Td (" + safe + ") Tj ET\nendstream endobj\n5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\nxref\n0 6\n0000000000 65535 f \n0000000010 00000 n \n0000000063 00000 n \n0000000122 00000 n \n0000000248 00000 n \n0000000395 00000 n \ntrailer<< /Size 6 /Root 1 0 R >>\nstartxref\n465\n%%EOF"; return pdf.getBytes(StandardCharsets.US_ASCII); }
}
