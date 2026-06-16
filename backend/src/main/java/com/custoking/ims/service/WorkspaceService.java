package com.custoking.ims.service;

import com.custoking.ims.context.TenantAccess;
import com.custoking.ims.dto.ApprovalDecisionRequest;
import com.custoking.ims.dto.InvoiceCreateRequest;
import com.custoking.ims.dto.PaymentCreateRequest;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.DashboardStats;
import com.custoking.ims.repo.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
@Service
@Transactional
public class WorkspaceService {

    private final SchoolRepository schoolRepository;
    private final SchoolSectionRepository sectionRepository;
    private final AcademicYearRepository academicYearRepository;
    private final AppUserRepository userRepository;
    private final StaffMemberRepository staffMemberRepository;
    private final CatalogOrderRepository catalogOrderRepository;
    private final AnnualPlanItemRepository annualPlanItemRepository;
    private final FirefightingRequestRepository firefightingRequestRepository;
    private final FirefightingQuotationRepository firefightingQuotationRepository;
    private final StudentService studentService;
    private final FeeService feeService;
    private final AttendanceService attendanceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkspaceService(SchoolRepository schoolRepository,
                             SchoolSectionRepository sectionRepository,
                             AcademicYearRepository academicYearRepository,
                             AppUserRepository userRepository,
                             StaffMemberRepository staffMemberRepository,
                             CatalogOrderRepository catalogOrderRepository,
                             AnnualPlanItemRepository annualPlanItemRepository,
                             FirefightingRequestRepository firefightingRequestRepository,
                             FirefightingQuotationRepository firefightingQuotationRepository,
                             StudentService studentService,
                             FeeService feeService,
                             AttendanceService attendanceService) {
        this.schoolRepository = schoolRepository;
        this.sectionRepository = sectionRepository;
        this.academicYearRepository = academicYearRepository;
        this.userRepository = userRepository;
        this.staffMemberRepository = staffMemberRepository;
        this.catalogOrderRepository = catalogOrderRepository;
        this.annualPlanItemRepository = annualPlanItemRepository;
        this.firefightingRequestRepository = firefightingRequestRepository;
        this.firefightingQuotationRepository = firefightingQuotationRepository;
        this.studentService = studentService;
        this.feeService = feeService;
        this.attendanceService = attendanceService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> workspace(AuthUser actor) {
        return workspace(actor, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> workspace(AuthUser actor, Long requestedSchoolId) {
        AcademicYearEntity year = currentAcademicYearEntity();
        Long schoolId = TenantAccess.resolveSchoolId(requestedSchoolId);
        SchoolEntity school = resolveWorkspaceSchool(schoolId);
        schoolId = school.getId();
        List<StudentEntity> students = studentService.scopedStudents(schoolId);
        long sectionCount = sectionRepository.findBySchool_Id(schoolId).size();
        Map<String, Object> attendanceSummary = attendanceService.attendanceDailySummary("today", schoolId);
        List<Map<String, Object>> attendanceSections = castListMap(attendanceSummary.get("sections"));
        Map<String, Object> feesModule = feeService.buildFeesModule(year.getId(), schoolId);
        Map<String, Object> feeSummary = castMap(feesModule.get("summary"));
        long activeFire = firefightingRequestRepository.countBySchool_IdAndStatusNot(schoolId, "FULFILLED");
        long pendingApprovals = firefightingRequestRepository.countBySchool_IdAndStatus(schoolId, "AWAITING_BURSAR")
                + firefightingRequestRepository.countBySchool_IdAndStatus(schoolId, "AWAITING_PRINCIPAL");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("school", row("name", school.getName(),
                "meta", school.getCity() == null
                        ? ("Academic year " + year.getLabel())
                        : (school.getCity() + " · Academic year " + year.getLabel()),
                "students", students.size(),
                "sections", sectionCount));
        response.put("dashboard", row(
                "students", students.size(),
                "sections", sectionCount,
                "attendancePercent", num(attendanceSummary.get("overallPercent"), 0),
                "attendancePresent", attendanceSections.stream().mapToInt(m -> (int) longNum(m.get("presentCount"), 0)).sum(),
                "feeCollectedLakh", round(num(feeSummary.get("collected"), 0) / 100000.0),
                "feeTargetLakh", round(num(feeSummary.get("target"), 0) / 100000.0),
                "feeOverdueCount", feeService.feeOverdueCount(year.getId(), schoolId),
                "firefightingActive", activeFire,
                "pendingApprovals", pendingApprovals));
        response.put("recentActivity", List.of(
                row("icon", "🎓", "title", "Student profile updated",
                        "meta", students.isEmpty() ? "No students yet" : students.get(0).getFullName() + " · ERP",
                        "tag", "ERP", "tone", "sb2"),
                row("icon", "₹", "title", "Fee plan assignments live",
                        "meta", "fee assignments in PostgreSQL", "tag", "Fees", "tone", "sg"),
                row("icon", "✓", "title", "Attendance snapshot ready",
                        "meta", attendanceSections.size() + " sections with records today",
                        "tag", "Attendance", "tone", "pb")));
        response.put("students", studentService.studentsData(schoolId));
        response.put("fees", feesModule);
        response.put("feeStructures", feeService.feeStructureData(year.getId()).get("bands"));
        response.put("attendance", row(
                "summary", row("date", LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")),
                        "overallPercent", num(attendanceSummary.get("overallPercent"), 0)),
                "classes", attendanceSections));
        response.put("timetable", List.of(
                row("day", "Monday", "period", "P1", "classSection", "9-B", "subject", "Mathematics", "teacher", "Priya Sharma"),
                row("day", "Tuesday", "period", "P2", "classSection", "6-A", "subject", "Science", "teacher", "Arun Menon")));
        response.put("staff", staffMemberRepository.findBySchool_IdOrderByNameAsc(schoolId).stream()
                .map(this::staffRow).toList());
        response.put("catalog", catalogCategories());
        List<CatalogOrderEntity> orders = catalogOrderRepository.findBySchool_Id(schoolId);
        response.put("orders", orders.stream()
                .sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::catalogOrderListRow).toList());
        response.put("annualPlan", annualPlanPayload(schoolId, students.size()));
        List<FirefightingRequestEntity> ff = firefightingRequestRepository.findBySchool_Id(schoolId);
        response.put("firefighting", row(
                "requests", ff.stream()
                        .sorted(Comparator.comparing(FirefightingRequestEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                        .map(this::fireRequestRow).toList(),
                "orders", ff.stream()
                        .filter(r -> List.of("APPROVED", "FULFILLED")
                                .contains(String.valueOf(r.getStatus()).toUpperCase(Locale.ROOT)))
                        .map(this::fireOrderRowNew).toList()));
        response.put("users", users());
        return response;
    }

    public DashboardStats dashboard(AuthUser actor) {
        Map<String, Object> dashboard = castMap(workspace(actor).get("dashboard"));
        return new DashboardStats(
                ((Number) dashboard.get("students")).intValue(),
                ((Number) dashboard.get("feeOverdueCount")).intValue(),
                ((Number) dashboard.get("firefightingActive")).intValue(),
                num(dashboard.get("attendancePercent"), 0),
                num(dashboard.get("feeCollectedLakh"), 0),
                num(dashboard.get("feeTargetLakh"), 0));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> users() {
        return userRepository.findAllByOrderByFullNameAsc().stream()
                .map(u -> row("id", u.getId(), "fullName", u.getFullName(), "email", u.getEmail(),
                        "role", u.getRole(), "branchName", u.getBranchName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> approvals(AuthUser actor) {
        return firefightingRequestRepository
                .findBySchool_IdAndStatus(TenantAccess.resolveSchoolId(null), "AWAITING_PRINCIPAL")
                .stream().map(this::fireRequestRow).toList();
    }

    public Map<String, Object> decideApproval(long id, String action, ApprovalDecisionRequest request) {
        return row("ok", true);
    }

    public List<Map<String, Object>> customers() {
        return List.of();
    }

    public Map<String, Object> addCustomer(Map<String, Object> request) {
        return row("ok", true);
    }

    public List<Map<String, Object>> invoices() {
        return List.of();
    }

    public Map<String, Object> addInvoice(InvoiceCreateRequest request) {
        return row("ok", true);
    }

    public byte[] invoicePdf(long id) {
        return simplePdf("Invoice " + id);
    }

    public Map<String, Object> addPayment(PaymentCreateRequest request, AuthUser user) {
        return row("ok", true);
    }

    public Map<String, Object> bulkImport(Map<String, Object> request) {
        return row("ok", true);
    }

    public Map<String, Object> addTimetableEntry(Map<String, Object> request) {
        return row("ok", true, "entry", request);
    }

    public Map<String, Object> addStaff(Map<String, Object> request) {
        StaffMemberEntity e = new StaffMemberEntity();
        e.setName(str(request.get("name"), ""));
        e.setDesignation(str(request.get("designation"), ""));
        e.setDepartment(str(request.get("department"), ""));
        e.setMonthlySalary(longNum(request.get("monthlySalary"), 0));
        e.setPayrollStatus(str(request.get("payrollStatus"), "Pending"));
        staffMemberRepository.save(e);
        return staffRow(e);
    }

    public Map<String, Object> createOrder(Map<String, Object> request) {
        CatalogOrderEntity e = new CatalogOrderEntity();
        e.setId("CK-" + (1000 + catalogOrderRepository.count() + 1));
        e.setSchool(resolveWorkspaceSchool(TenantAccess.resolveSchoolId(null)));
        e.setCategory(str(request.get("category"), "STATIONERY"));
        e.setOrderData(toJson(row("title", str(request.get("title"), "Order"),
                "items", str(request.get("items"), "1 unit"))));
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
        e.setSchool(resolveWorkspaceSchool(TenantAccess.resolveSchoolId(null)));
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> createFirefightingRequest(Map<String, Object> request, Long actorUserId) {
        FirefightingRequestEntity e = new FirefightingRequestEntity();
        e.setCode(nextFireCode());
        e.setSchool(resolveWorkspaceSchool(TenantAccess.resolveSchoolId(null)));
        e.setTitle(str(request.get("title"), "Request"));
        e.setCategory(str(request.get("category"), "Other"));
        e.setDescription(str(firstPresent(request, "description", "summary"), ""));
        e.setEstimatedBudget(longNum(firstPresent(request, "estimatedBudget", "amount"), 0));
        e.setUrgency(str(request.get("urgency"), "MEDIUM"));
        e.setRaisedBy(actorUserId);

        List<Map<String, Object>> rawQuotes = new ArrayList<>();
        Object quotationsRaw = request.get("quotations");
        if (quotationsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) rawQuotes.add((Map<String, Object>) m);
            }
        }
        List<Map<String, Object>> nonEmpty = rawQuotes.stream()
                .filter(q -> !str(q.get("vendorName"), "").isBlank())
                .toList();

        boolean isDraft = "draft".equalsIgnoreCase(str(request.get("status"), ""));
        e.setStatus(isDraft ? "DRAFT" : "AWAITING_BURSAR");
        firefightingRequestRepository.save(e);

        for (Map<String, Object> q : nonEmpty) {
            FirefightingQuotationEntity qe = new FirefightingQuotationEntity();
            qe.setId(UUID.randomUUID().toString());
            qe.setRequest(e);
            qe.setVendorName(str(q.get("vendorName"), "Vendor"));
            qe.setAmount(longNum(q.get("amount"), 0));
            qe.setDeliveryTimeline(str(q.get("deliveryTimeline"), ""));
            qe.setNotes(str(q.get("notes"), ""));
            qe.setCustoking("Custoking".equalsIgnoreCase(qe.getVendorName()));
            firefightingQuotationRepository.save(qe);
        }

        return fireRequestRow(e);
    }

    public Map<String, Object> decideFirefighting(String code, String action) {
        FirefightingRequestEntity entity = firefightingRequestRepository.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        entity.setStatus("approve".equalsIgnoreCase(action) ? "FULFILLED" : "REJECTED");
        firefightingRequestRepository.save(entity);
        return fireRequestRow(entity);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private SchoolEntity resolveWorkspaceSchool(Long schoolId) {
        if (schoolId != null) {
            return schoolRepository.findById(schoolId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        }
        return schoolRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No school available"));
    }

    private List<Map<String, Object>> catalogCategories() {
        return List.of(
                row("id", "UNIFORMS", "emoji", "👕", "label", "Uniforms", "orderType", "Recurring"),
                row("id", "NOTEBOOKS", "emoji", "📘", "label", "Notebooks", "orderType", "Recurring"),
                row("id", "STATIONERY", "emoji", "✏️", "label", "Stationery", "orderType", "Recurring"),
                row("id", "IDCARDS", "emoji", "🪪", "label", "ID Cards", "orderType", "One-time"),
                row("id", "HOUSEKEEPING", "emoji", "🧹", "label", "Housekeeping", "orderType", "Service"),
                row("id", "EVENTS", "emoji", "🎉", "label", "Events", "orderType", "One-time"),
                row("id", "HEALTH", "emoji", "🩺", "label", "Health", "orderType", "Service"));
    }

    private Map<String, Object> annualPlanPayload(Long schoolId, int studentCount) {
        List<Map<String, Object>> terms = annualPlanItemRepository.findBySchool_Id(schoolId).stream()
                .sorted(Comparator.comparing(AnnualPlanItemEntity::getTermName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AnnualPlanItemEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::annualPlanItemRow).toList();
        long total = terms.stream().mapToLong(t -> longNum(t.get("estimatedAmount"), 0)).sum();
        long ordered = terms.stream().filter(t -> "ORDERED".equals(String.valueOf(t.get("status")))).count();
        int completion = terms.isEmpty() ? 0 : (int) Math.round((ordered * 100.0) / terms.size());
        return row("completionPercent", completion,
                "academicYears", List.of(currentAcademicYearEntity().getLabel()),
                "terms", terms,
                "summary", row("total", total,
                        "pendingCount", terms.stream().filter(t -> !"ORDERED".equals(String.valueOf(t.get("status")))).count(),
                        "perStudentCost", total / Math.max(1, studentCount),
                        "vsLastYearPercent", 12));
    }

    private Map<String, Object> annualPlanItemRow(AnnualPlanItemEntity e) {
        return row("id", e.getId(), "term", e.getTermName(), "termName", e.getTermName(),
                "category", e.getCategory(), "description", e.getDescription(), "status", e.getStatus(),
                "quantity", e.getQuantity(), "amount", e.getEstimatedAmount(),
                "estimatedAmount", e.getEstimatedAmount(), "linkedOrderId", e.getLinkedOrderId());
    }

    private Map<String, Object> staffRow(StaffMemberEntity e) {
        return row("id", e.getId(), "name", e.getName(), "designation", e.getDesignation(),
                "department", e.getDepartment(), "monthlySalary", e.getMonthlySalary(),
                "payrollStatus", e.getPayrollStatus());
    }

    private Map<String, Object> catalogOrderListRow(CatalogOrderEntity e) {
        Map<String, Object> data = parseJsonMap(e.getOrderData());
        return row("id", e.getId(), "code", e.getId(), "category", e.getCategory(),
                "description", str(firstPresent(data, "title", "description"), e.getCategory()),
                "title", str(firstPresent(data, "title", "description"), e.getCategory()),
                "items", str(firstPresent(data, "items", "quantitySummary"), "—"),
                "totalAmount", e.getTotalAmount(), "amount", e.getTotalAmount(),
                "status", e.getStatus(),
                "placedAt", e.getPlacedAt() == null ? null : e.getPlacedAt().toString(),
                "estimatedDelivery", e.getEstimatedDelivery(),
                "date", e.getCreatedAt().toLocalDate().toString(), "action", "Track");
    }

    private Map<String, Object> fireRequestRow(FirefightingRequestEntity e) {
        return row("code", e.getCode(), "title", e.getTitle(), "category", e.getCategory(),
                "summary", e.getDescription(), "description", e.getDescription(),
                "amount", e.getEstimatedBudget(), "estimatedBudget", e.getEstimatedBudget(),
                "status", e.getStatus(),
                "date", e.getCreatedAt() == null ? null : e.getCreatedAt().toLocalDate().toString(),
                "urgency", e.getUrgency());
    }

    private Map<String, Object> fireOrderRowNew(FirefightingRequestEntity e) {
        return row("code", e.getCode(), "title", e.getTitle(), "category", e.getCategory(),
                "via", e.getWinnerVendor() == null ? "Custoking" : e.getWinnerVendor(),
                "amount", e.getWinnerAmount() == null ? e.getEstimatedBudget() : e.getWinnerAmount(),
                "status", "FULFILLED".equalsIgnoreCase(e.getStatus()) ? "Delivered" : "Approved",
                "date", e.getCreatedAt() == null ? null : e.getCreatedAt().toLocalDate().toString());
    }

    private String nextFireCode() {
        return String.format("FF-%03d", firefightingRequestRepository.findAllCodes().stream()
                .map(c -> c.replaceAll("\\D+", ""))
                .filter(s -> !s.isBlank())
                .mapToInt(Integer::parseInt).max().orElse(2) + 1);
    }

    private AcademicYearEntity currentAcademicYearEntity() {
        return academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String k : keys)
            if (request.containsKey(k) && request.get(k) != null) return request.get(k);
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value == null ? new LinkedHashMap<>() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castListMap(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    private byte[] simplePdf(String content) {
        String safe = content.replace("(", "[").replace(")", "]");
        String pdf = "%PDF-1.4\n1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n"
                + "2 0 obj<< /Type /Pages /Count 1 /Kids [3 0 R] >>endobj\n"
                + "3 0 obj<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>endobj\n"
                + "4 0 obj<< /Length 120 >>stream\nBT /F1 12 Tf 36 740 Td (" + safe + ") Tj ET\nendstream endobj\n"
                + "5 0 obj<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>endobj\n"
                + "xref\n0 6\n0000000000 65535 f \n0000000010 00000 n \n0000000063 00000 n \n"
                + "0000000122 00000 n \n0000000248 00000 n \n0000000395 00000 n \n"
                + "trailer<< /Size 6 /Root 1 0 R >>\nstartxref\n465\n%%EOF";
        return pdf.getBytes(StandardCharsets.US_ASCII);
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
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

    @Transactional(readOnly = true)
    public List<Map<String, Object>> commandCentreCards(AuthUser actor, Long schoolId) {
        boolean isSuperAdmin = schoolId == null;
        List<Map<String, Object>> cards = new ArrayList<>();

        // ── Firefighting: approvals pending ──────────────────────────────────
        List<FirefightingRequestEntity> ffPending = isSuperAdmin
                ? merge(firefightingRequestRepository.findByStatus("AWAITING_BURSAR"),
                        firefightingRequestRepository.findByStatus("AWAITING_PRINCIPAL"))
                : merge(firefightingRequestRepository.findBySchool_IdAndStatus(schoolId, "AWAITING_BURSAR"),
                        firefightingRequestRepository.findBySchool_IdAndStatus(schoolId, "AWAITING_PRINCIPAL"));
        if (!ffPending.isEmpty()) {
            int count = ffPending.size();
            cards.add(row(
                "id", "cc-ff-approval",
                "module", "firefighting",
                "urgency", "critical",
                "confidence", 97,
                "code", "FF-APPROVE-" + count,
                "title", count + " urgent procurement request" + (count > 1 ? "s" : "") + " pending approval",
                "why", count + " request" + (count > 1 ? "s" : "") + " in review need sign-off before procurement can proceed.",
                "impact", "Approval required · SLA at risk",
                "state", "AWAITING → APPROVED",
                "cta", "Approve requests",
                "count", count
            ));
        }

        // ── Firefighting: drafts missing quotation ────────────────────────────
        List<FirefightingRequestEntity> ffDrafts = isSuperAdmin
                ? firefightingRequestRepository.findByStatus("DRAFT")
                : firefightingRequestRepository.findBySchool_IdAndStatus(schoolId, "DRAFT");
        if (!ffDrafts.isEmpty()) {
            int count = ffDrafts.size();
            cards.add(row(
                "id", "cc-ff-drafts",
                "module", "firefighting",
                "urgency", "high",
                "confidence", 91,
                "code", "FF-DRAFT-" + count,
                "title", count + " urgent procurement request" + (count > 1 ? "s" : "") + " awaiting quotation",
                "why", count + " open request" + (count > 1 ? "s" : "") + " need vendor quotation before approval can proceed.",
                "impact", "Quotation needed · " + count + " request" + (count > 1 ? "s" : ""),
                "state", "DRAFT → IN REVIEW",
                "cta", "Add quotations",
                "count", count
            ));
        }

        // ── Supply orders: submitted, awaiting approval ───────────────────────
        List<CatalogOrderEntity> submittedOrders = isSuperAdmin
                ? catalogOrderRepository.findByStatusOrderByCreatedAtDesc("SUBMITTED")
                : catalogOrderRepository.findBySchool_IdAndStatus(schoolId, "SUBMITTED");
        if (!submittedOrders.isEmpty()) {
            int count = submittedOrders.size();
            long totalPaise = submittedOrders.stream()
                    .mapToLong(CatalogOrderEntity::getTotalAmount)
                    .sum();
            String impact = totalPaise > 0
                    ? "₹" + String.format("%.1f", totalPaise / 100_000.0) + "L pending approval"
                    : count + " orders pending";
            cards.add(row(
                "id", "cc-orders-pending",
                "module", "supply",
                "urgency", "high",
                "confidence", 93,
                "code", "ORD-PENDING-" + count,
                "title", count + " supply order" + (count > 1 ? "s" : "") + " awaiting approval",
                "why", count + " submitted order" + (count > 1 ? "s" : "") + " need approval. Delays extend vendor lead time.",
                "impact", impact,
                "state", "SUBMITTED → APPROVED",
                "cta", "Review orders",
                "count", count
            ));
        }

        // ── Fee collections: overdue (school scope only) ──────────────────────
        if (!isSuperAdmin) {
            AcademicYearEntity year = currentAcademicYearEntity();
            long overdueCount = feeService.feeOverdueCount(year.getId(), schoolId);
            if (overdueCount > 0) {
                cards.add(row(
                    "id", "cc-fee-overdue",
                    "module", "fees",
                    "urgency", overdueCount > 50 ? "high" : "medium",
                    "confidence", 95,
                    "code", "FEES-OVERDUE-" + overdueCount,
                    "title", overdueCount + " student" + (overdueCount > 1 ? "s" : "") + " have overdue fees",
                    "why", overdueCount + " families have missed the payment deadline. A reminder before 6 PM recovers dues faster.",
                    "impact", overdueCount + " students unpaid",
                    "state", "Overdue → Reminded",
                    "cta", "Send reminders",
                    "count", (int) overdueCount
                ));
            }
        }

        // Sort: critical → high → medium → low
        List<String> order = List.of("critical", "high", "medium", "low");
        cards.sort(Comparator.comparingInt(c -> order.indexOf(String.valueOf(c.get("urgency")))));
        return cards;
    }

    @SafeVarargs
    private static <T> List<T> merge(List<T>... lists) {
        List<T> result = new ArrayList<>();
        for (List<T> l : lists) result.addAll(l);
        return result;
    }

    private double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
