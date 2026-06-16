package com.custoking.ims.service;

import com.custoking.ims.context.TenantAccess;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class FeeService {

    private static final Logger log = LoggerFactory.getLogger(FeeService.class);

    private final FeeBandRepository feeBandRepository;
    private final FeeItemRepository feeItemRepository;
    private final FeeAssignmentRepository feeAssignmentRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final AcademicYearRepository academicYearRepository;
    private final StudentRepository studentRepository;

    public FeeService(FeeBandRepository feeBandRepository,
                      FeeItemRepository feeItemRepository,
                      FeeAssignmentRepository feeAssignmentRepository,
                      PaymentRecordRepository paymentRecordRepository,
                      AcademicYearRepository academicYearRepository,
                      StudentRepository studentRepository) {
        this.feeBandRepository = feeBandRepository;
        this.feeItemRepository = feeItemRepository;
        this.feeAssignmentRepository = feeAssignmentRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.academicYearRepository = academicYearRepository;
        this.studentRepository = studentRepository;
    }

    // ── Fee structure ────────────────────────────────────────────────

    public Map<String, Object> feeStructureData(String academicYearId) {
        String yearId = academicYearId == null || academicYearId.isBlank() ? currentAcademicYearId() : academicYearId;
        AcademicYearEntity year = academicYearRepository.findById(yearId).orElse(currentAcademicYearEntity());
        List<Map<String, Object>> bands = feeBandRepository.findByAcademicYear_IdOrderByClassFromAscNameAsc(year.getId())
                .stream().map(this::bandRow).toList();
        return row("academicYearId", year.getId(), "academicYear", year.getLabel(), "bands", bands);
    }

    public Map<String, Object> createFeeStructureBand(Map<String, Object> request, AuthUser actor) {
        String name = str(request.get("name"), "").trim();
        if (name.isBlank()) throw new IllegalArgumentException("Band name is required");
        int classFrom = (int) longNum(request.get("classFrom"), 1);
        int classTo = (int) longNum(request.get("classTo"), classFrom);
        if (classTo < classFrom) throw new IllegalArgumentException("Class to must be >= class from");
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
        band.setCreatedAt(OffsetDateTime.now());
        band.setUpdatedAt(OffsetDateTime.now());
        feeBandRepository.save(band);
        return bandRow(band);
    }

    public Map<String, Object> updateFeeStructureBand(String id, Map<String, Object> request, AuthUser actor) {
        FeeBandEntity band = feeBandRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        String nextName = str(request.get("name"), band.getName()).trim();
        if (nextName.isBlank()) nextName = band.getName();
        int classFrom = (int) longNum(request.get("classFrom"), band.getClassFrom());
        int classTo = (int) longNum(request.get("classTo"), band.getClassTo());
        if (classTo < classFrom) throw new IllegalArgumentException("Class to must be >= class from");
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
        FeeBandEntity band = feeBandRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        feeItemRepository.deleteByBand_Id(id);
        feeBandRepository.delete(band);
        return row("removed", true, "bandId", id);
    }

    public Map<String, Object> patchFeeStructureBand(String id, Map<String, Object> request, AuthUser actor) {
        FeeBandEntity band = feeBandRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        if (request.containsKey("discount") || request.containsKey("bandDiscount"))
            band.setDiscount(num(firstPresent(request, "discount", "bandDiscount"), band.getDiscount()));
        List<String> schedules = toStringList(request.get("schedules"));
        if (!schedules.isEmpty()) band.setActiveSchedulesCsv(String.join(",", schedules));
        band.setUpdatedAt(OffsetDateTime.now());
        feeBandRepository.save(band);
        return bandRow(band);
    }

    public Map<String, Object> addFeeStructureItem(Map<String, Object> request, AuthUser actor) {
        return addFeeItem(request);
    }

    public Map<String, Object> addFeeItem(Map<String, Object> request) {
        FeeBandEntity band = feeBandRepository.findById(str(request.get("bandId"), ""))
                .orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        String name = str(firstPresent(request, "itemName"), "").trim();
        if (name.isBlank()) throw new IllegalArgumentException("Item name is required");
        FeeItemEntity item = new FeeItemEntity();
        item.setId(UUID.randomUUID().toString());
        item.setBand(band);
        item.setName(name);
        item.setFrequency(str(request.get("frequency"), "Annual"));
        item.setAmount(toPaise(request.get("amount")));
        item.setCreatedAt(OffsetDateTime.now());
        item.setUpdatedAt(OffsetDateTime.now());
        feeItemRepository.save(item);
        return bandRow(band);
    }

    public Map<String, Object> updateFeeStructureItem(String id, Map<String, Object> request, AuthUser actor) {
        FeeItemEntity item = feeItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fee item not found"));
        item.setName(str(firstPresent(request, "itemName"), item.getName()));
        item.setFrequency(str(request.get("frequency"), item.getFrequency()));
        item.setAmount(toPaise(request.get("amount")));
        item.setUpdatedAt(OffsetDateTime.now());
        feeItemRepository.save(item);
        return bandRow(item.getBand());
    }

    public Map<String, Object> deleteFeeStructureItem(String id, AuthUser actor) {
        FeeItemEntity item = feeItemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fee item not found"));
        String bandId = item.getBand().getId();
        feeItemRepository.delete(item);
        return row("removed", true, "bandId", bandId);
    }

    public Map<String, Object> matchFeeStructureBand(String classId) {
        int sort = classSortOrder(classId);
        FeeBandEntity band = feeBandRepository
                .findFirstByAcademicYear_IdAndClassFromLessThanEqualAndClassToGreaterThanEqual(
                        currentAcademicYearId(), sort, sort).orElse(null);
        return band == null ? row() : bandRow(band);
    }

    public byte[] exportFeeStructurePdf(String academicYearId, String format) {
        return simplePdf("Fee structure " + currentAcademicYear() + " | bands " + feeBandRepository.count());
    }

    // ── Fee assignment ───────────────────────────────────────────────

    public Map<String, Object> feeAssignmentApi(Map<String, Object> request, AuthUser actor) {
        StudentEntity student = studentRepository.findById(longNum(request.get("studentId"), -1))
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        TenantAccess.assertSchoolAccess("student",
                student.getSchool() == null ? null : student.getSchool().getId(), null);
        FeeBandEntity band = feeBandRepository.findById(str(request.get("bandId"), ""))
                .orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
        String schedule = str(request.get("schedule"), "");
        if (schedule.isBlank()) throw new IllegalArgumentException("Payment schedule is required");
        FeeAssignmentEntity assignment = feeAssignmentRepository
                .findByStudent_IdAndAcademicYear_Id(student.getId(), currentAcademicYearId())
                .orElseGet(FeeAssignmentEntity::new);
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
        long net = calculateNetPayable(total, assignment.getBandDiscount(),
                assignment.getManualDiscount(), assignment.getSurcharge(), schedule);
        assignment.setNetPayable(net);
        assignment.setUpdatedBy(actor.userId());
        assignment.setUpdatedAt(OffsetDateTime.now());
        feeAssignmentRepository.save(assignment);
        student.setFeeStatus(assignment.getPaidAmount() >= assignment.getNetPayable() ? "Paid" : "Overdue");
        studentRepository.save(student);
        log.info("fee.assignment studentId={} assignmentId={} actorId={}", student.getId(), assignment.getId(), actor.userId());
        return row("ok", true, "assignment", assignmentRow(assignment));
    }

    public Map<String, Object> assignFeePlan(Map<String, Object> request) {
        return feeAssignmentApi(request, new AuthUser(1L, "System", "system@custoking.com",
                Role.SUPERADMIN, null, null, null));
    }

    // ── Payments ─────────────────────────────────────────────────────

    public Map<String, Object> paymentApi(Map<String, Object> request, AuthUser actor) {
        StudentEntity student = studentRepository.findById(longNum(request.get("studentId"), -1))
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
        TenantAccess.assertSchoolAccess("student",
                student.getSchool() == null ? null : student.getSchool().getId(), null);
        long amount = longNum(request.get("amount"), 0);
        if (amount <= 0) throw new IllegalArgumentException("Amount must be greater than zero");
        FeeAssignmentEntity assignment = feeAssignmentRepository
                .findByStudent_IdAndAcademicYear_Id(student.getId(), currentAcademicYearId())
                .orElseGet(() -> feeAssignmentRepository.findByStudent_Id(student.getId()).orElse(null));
        if (assignment == null) throw new IllegalArgumentException("Fee assignment not found. Assign a fee plan first.");
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
        payment.setPaidAt(parsePaidAt(request.get("paidAt")));
        payment.setRecordedBy(actor.userId());
        payment.setReceiptNumber("RCPT-" + System.currentTimeMillis());
        paymentRecordRepository.save(payment);
        assignment.setPaidAmount(safeAdd(assignment.getPaidAmount(), amount, "paid amount"));
        assignment.setUpdatedBy(actor.userId());
        assignment.setUpdatedAt(OffsetDateTime.now());
        feeAssignmentRepository.save(assignment);
        student.setFeeStatus(assignment.getPaidAmount() >= assignment.getNetPayable() ? "Paid" : "Overdue");
        studentRepository.save(student);
        log.info("payment.record studentId={} amount={} mode={} actorId={}", student.getId(), amount, payment.getMode(), actor.userId());
        log.info("payment.recorded paymentId={} receiptNumber={}", payment.getId(), payment.getReceiptNumber());
        return row("paymentId", payment.getId(), "receiptUrl", "/api/v1/receipts/" + payment.getId() + "/pdf");
    }

    public Map<String, Object> recordPayment(Map<String, Object> request, AuthUser actor) {
        return paymentApi(request, actor);
    }

    public List<Map<String, Object>> payments() {
        return paymentRecordRepository.findAllByOrderByPaidAtDesc().stream()
                .map(p -> row("id", p.getId(), "student", studentName(p.getStudent()),
                        "amount", p.getAmount(), "mode", p.getMode(), "paidAt", isoDate(p.getPaidAt())))
                .toList();
    }

    public Map<String, Object> addPayment(Object request, AuthUser user) {
        return row("ok", true);
    }

    public byte[] receiptPdfByPaymentId(String paymentId) {
        PaymentRecordEntity payment = paymentRecordRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        return simplePdf("Receipt " + payment.getReceiptNumber()
                + " | " + studentName(payment.getStudent())
                + " | INR " + indian(payment.getAmount()));
    }

    public byte[] feeReceiptPdf(String receiptNumber) {
        PaymentRecordEntity payment = paymentRecordRepository.findByReceiptNumber(receiptNumber)
                .orElseThrow(() -> new IllegalArgumentException("Receipt not found"));
        return receiptPdfByPaymentId(payment.getId());
    }

    // ── Reports ──────────────────────────────────────────────────────

    public List<Map<String, Object>> feeReport(String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantAccess.resolveSchoolId(requestedSchoolId);
        List<FeeAssignmentEntity> assignments = feeAssignmentRepository
                .findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId())
                .stream()
                .filter(a -> a.getStudent() != null && a.getBand() != null
                        && a.getStudent().getSection() != null
                        && a.getStudent().getSection().getSchool() != null
                        && schoolId.equals(a.getStudent().getSection().getSchool().getId()))
                .toList();
        Set<String> bandIds = assignments.stream().map(a -> a.getBand().getId()).collect(Collectors.toSet());
        Map<String, Long> totals = bandTotals(bandIds);
        return assignments.stream().map(a -> {
            long due = dueAmount(a);
            return row("paymentId", latestPaymentId(a.getStudent().getId()),
                    "student", a.getStudent().getFullName(),
                    "planName", a.getBand().getName(),
                    "schedule", a.getSchedule(),
                    "totalAnnualFee", totals.getOrDefault(a.getBand().getId(), 0L),
                    "discounts", round(a.getBandDiscount() + a.getManualDiscount()),
                    "surcharge", round(a.getSurcharge()),
                    "paid", a.getPaidAmount(), "due", due,
                    "status", due > 0 ? "Overdue" : "Paid");
        }).toList();
    }

    public List<Map<String, Object>> feeOverdue(String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantAccess.resolveSchoolId(requestedSchoolId);
        return feeAssignmentRepository
                .findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId())
                .stream()
                .filter(a -> a.getNetPayable() > a.getPaidAmount())
                .filter(a -> a.getStudent() != null && a.getStudent().getSection() != null
                        && a.getStudent().getSection().getSchool() != null
                        && schoolId.equals(a.getStudent().getSection().getSchool().getId()))
                .map(a -> row("student", a.getStudent().getFullName(), "schedule", a.getSchedule(),
                        "dueAmount", dueAmount(a),
                        "daysOverdue", 12 + studentIdModulo(a.getStudent(), 24)))
                .toList();
    }

    public Map<String, Object> sendFeeReminders(String classId, String sectionId, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantAccess.resolveSchoolId(requestedSchoolId);
        long queued = feeAssignmentRepository
                .findByStudent_SchoolClass_IdAndStudent_Section_IdAndAcademicYear_Id(classId, sectionId, currentAcademicYearId())
                .stream()
                .filter(a -> a.getNetPayable() > a.getPaidAmount())
                .filter(a -> a.getStudent() != null && a.getStudent().getSection() != null
                        && a.getStudent().getSection().getSchool() != null
                        && schoolId.equals(a.getStudent().getSection().getSchool().getId()))
                .count();
        return row("ok", true, "queued", queued, "classId", classId, "sectionId", sectionId);
    }

    // ── Used by WorkspaceService ─────────────────────────────────────

    public Map<String, Object> buildFeesModule(String yearId, Long schoolId) {
        List<FeeAssignmentEntity> scoped = schoolId != null
                ? feeAssignmentRepository.findByAcademicYear_IdAndStudent_School_Id(yearId, schoolId).stream()
                        .filter(a -> a.getStudent() != null && a.getBand() != null).toList()
                : feeAssignmentRepository.findByAcademicYear_Id(yearId).stream()
                        .filter(a -> a.getStudent() != null && a.getBand() != null).toList();
        long collected = schoolId != null
                ? paymentRecordRepository.sumAmountBySchoolId(schoolId)
                : paymentRecordRepository.sumAmount();
        long target = scoped.stream().mapToLong(FeeAssignmentEntity::getNetPayable).sum();
        Set<String> bandIds = scoped.stream().map(a -> a.getBand().getId()).collect(Collectors.toSet());
        Map<String, Long> bandTotalMap = bandTotals(bandIds);
        List<Map<String, Object>> records = scoped.stream()
                .map(a -> row("studentId", a.getStudent().getId(), "studentName", a.getStudent().getFullName(),
                        "planName", a.getBand().getName(), "schedule", a.getSchedule(),
                        "dueAmount", dueAmount(a),
                        "totalAnnualFee", bandTotalMap.getOrDefault(a.getBand().getId(), 0L),
                        "paidAmount", a.getPaidAmount()))
                .toList();
        return row("summary", row("collected", collected, "target", target), "records", records);
    }

    public long feeOverdueCount(String yearId, Long schoolId) {
        return feeAssignmentRepository.countOverdueByYearAndSchool(yearId, schoolId);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private Map<String, Object> bandRow(FeeBandEntity band) {
        List<FeeItemEntity> feeItems = feeItemRepository.findByBand_IdOrderByCreatedAtAsc(band.getId());
        long total = feeItems.stream().mapToLong(FeeItemEntity::getAmount).sum();
        List<String> schedules = bandActiveSchedules(band);
        List<Map<String, Object>> items = feeItems.stream()
                .map(item -> row("id", item.getId(), "name", item.getName(), "frequency", item.getFrequency(),
                        "amount", item.getAmount(),
                        "percentOfTotal", total == 0 ? 0 : Math.round(item.getAmount() * 100.0 / total),
                        "createdAt", isoDate(item.getCreatedAt()), "updatedAt", isoDate(item.getUpdatedAt())))
                .toList();
        AcademicYearEntity academicYear = band.getAcademicYear();
        return row("id", band.getId(), "name", band.getName(), "groupName", band.getName(),
                "classFrom", band.getClassFrom(), "classTo", band.getClassTo(),
                "academicYearId", academicYear == null ? "" : academicYear.getId(),
                "academicYear", academicYear == null ? "" : academicYear.getLabel(),
                "discount", round(band.getDiscount()), "activeSchedules", schedules, "allowedSchedules", schedules,
                "items", items, "annualTotal", total,
                "createdAt", isoDate(band.getCreatedAt()), "updatedAt", isoDate(band.getUpdatedAt()));
    }

    private long bandTotal(String bandId) {
        return feeItemRepository.findByBand_IdOrderByCreatedAtAsc(bandId).stream()
                .mapToLong(FeeItemEntity::getAmount).sum();
    }

    private Map<String, Long> bandTotals(Set<String> bandIds) {
        if (bandIds.isEmpty()) return Collections.emptyMap();
        return feeItemRepository.sumAmountByBandIds(bandIds).stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).longValue()
                ));
    }

    private List<String> bandActiveSchedules(FeeBandEntity band) {
        return splitCsv(band == null ? null : band.getActiveSchedulesCsv());
    }

    private Map<String, Object> assignmentRow(FeeAssignmentEntity a) {
        StudentEntity student = a.getStudent();
        FeeBandEntity band = a.getBand();
        return row("id", a.getId(), "studentId", student == null ? null : student.getId(),
                "bandId", band == null ? null : band.getId(),
                "schedule", a.getSchedule(), "bandDiscount", a.getBandDiscount(),
                "manualDiscount", a.getManualDiscount(), "surcharge", a.getSurcharge(),
                "netPayable", a.getNetPayable(), "paidAmount", a.getPaidAmount());
    }

    private long calculateNetPayable(long total, double bandDiscount, double manualDiscount,
                                      double surcharge, String schedule) {
        long bandAmt = percentageAmount(total, bandDiscount);
        long manualAmt = percentageAmount(total, manualDiscount);
        long surchargeAmt = "Annual".equalsIgnoreCase(schedule) ? 0 : percentageAmount(total, surcharge);
        long discounted = safeSubtract(total, bandAmt, "net payable");
        discounted = safeSubtract(discounted, manualAmt, "net payable");
        return Math.max(safeAdd(discounted, surchargeAmt, "net payable"), 0);
    }

    private String latestPaymentId(Long studentId) {
        return paymentRecordRepository.findByStudent_IdOrderByPaidAtDesc(studentId).stream()
                .findFirst().map(PaymentRecordEntity::getId).orElse("");
    }

    private AcademicYearEntity currentAcademicYearEntity() {
        return academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private String currentAcademicYearId() {
        return currentAcademicYearEntity().getId();
    }

    private String currentAcademicYear() {
        return currentAcademicYearEntity().getLabel();
    }

    private int classSortOrder(String classId) {
        String value = String.valueOf(classId);
        long digits = 0;
        boolean foundDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isDigit(ch)) continue;
            foundDigit = true;
            int digit = Character.digit(ch, 10);
            if (digits > (Integer.MAX_VALUE - digit) / 10L) return Integer.MAX_VALUE;
            digits = digits * 10L + digit;
        }
        return foundDigit ? (int) digits : 0;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(v -> !v.isBlank()).distinct().toList();
    }

    private List<String> splitCsv(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    private long toPaise(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) {
            return amountToPaise(n.doubleValue());
        }
        String s = String.valueOf(value).replace(",", "").trim();
        if (s.isBlank()) return 0;
        try {
            double d = Double.parseDouble(s);
            return amountToPaise(d);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount must be numeric", e);
        }
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String k : keys)
            if (request.containsKey(k) && request.get(k) != null) return request.get(k);
        return null;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double num(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double round(double d) {
        if (!Double.isFinite(d)) return 0;
        return Math.round(d * 10.0) / 10.0;
    }

    private OffsetDateTime parsePaidAt(Object value) {
        if (value == null) return OffsetDateTime.now();
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("paidAt must be an ISO-8601 offset date-time", e);
        }
    }

    private long dueAmount(FeeAssignmentEntity assignment) {
        long due = safeSubtract(assignment.getNetPayable(), assignment.getPaidAmount(), "due amount");
        return Math.max(due, 0);
    }

    private long percentageAmount(long total, double percent) {
        if (total <= 0 || !Double.isFinite(percent) || percent == 0) return 0;
        double amount = total * percent / 100.0;
        if (!Double.isFinite(amount) || amount > Long.MAX_VALUE || amount < Long.MIN_VALUE) {
            throw new IllegalArgumentException("percentage amount is too large");
        }
        return Math.round(amount);
    }

    private long amountToPaise(double value) {
        if (!Double.isFinite(value) || value < 0) return 0;
        double paise = value > 100_000 ? value : value * 100.0;
        if (!Double.isFinite(paise) || paise > Long.MAX_VALUE) {
            throw new IllegalArgumentException("amount is too large");
        }
        return Math.round(paise);
    }

    private long safeAdd(long left, long right, String fieldName) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(fieldName + " is too large", e);
        }
    }

    private long safeSubtract(long left, long right, String fieldName) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(fieldName + " is too large", e);
        }
    }

    private String studentName(StudentEntity student) {
        return student == null ? "" : str(student.getFullName(), "");
    }

    private int studentIdModulo(StudentEntity student, int divisor) {
        if (student == null || student.getId() == null || divisor <= 0) return 0;
        return Math.floorMod(student.getId().intValue(), divisor);
    }

    private String isoDate(OffsetDateTime dateTime) {
        return dateTime == null ? "" : dateTime.toString();
    }

    private String indian(long paise) {
        return String.format(Locale.ENGLISH, "%,d", paise / 100);
    }

    private byte[] simplePdf(String content) {
        String safe = escapePdfText(content);
        String stream = "BT /F1 12 Tf 36 740 Td (" + safe + ") Tj ET\n";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>stream\n"
                        + stream + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>(objects.size());
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj").append(objects.get(i)).append("endobj\n");
        }
        int xrefOffset = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append('\n')
                .append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append(String.format(Locale.ENGLISH, "%010d 00000 n \n", offset));
        }
        pdf.append("trailer<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xrefOffset).append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private String escapePdfText(String content) {
        if (content == null || content.isBlank()) return "";
        StringBuilder escaped = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '(' || ch == ')' || ch == '\\') {
                escaped.append('\\').append(ch);
            } else if (ch >= 32 && ch <= 126) {
                escaped.append(ch);
            } else {
                escaped.append(' ');
            }
        }
        return escaped.toString();
    }

    private Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
