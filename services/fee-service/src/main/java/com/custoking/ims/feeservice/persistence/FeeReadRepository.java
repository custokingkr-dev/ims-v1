package com.custoking.ims.feeservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Repository
public class FeeReadRepository {

    private final JdbcClient jdbc;

    public FeeReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FeeBandRow> bands(String academicYearId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, class_from, class_to, discount, active_schedules_csv,
                       created_at, updated_at, academic_year_id
                FROM fee.fee_bands
                WHERE 1=1
                """);
        if (academicYearId != null && !academicYearId.isBlank()) sql.append(" AND academic_year_id = :academicYearId");
        sql.append(" ORDER BY class_from, class_to, name");

        var spec = jdbc.sql(sql.toString());
        if (academicYearId != null && !academicYearId.isBlank()) spec = spec.param("academicYearId", academicYearId);
        return spec.query(FeeBandRow.class).list();
    }

    public List<FeeItemRow> items(String bandId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, frequency, amount, created_at, updated_at, band_id
                FROM fee.fee_items
                WHERE 1=1
                """);
        if (bandId != null && !bandId.isBlank()) sql.append(" AND band_id = :bandId");
        sql.append(" ORDER BY name");

        var spec = jdbc.sql(sql.toString());
        if (bandId != null && !bandId.isBlank()) spec = spec.param("bandId", bandId);
        return spec.query(FeeItemRow.class).list();
    }

    public Map<String, Object> feeStructure(String academicYearId) {
        Map<String, Object> year = academicYear(academicYearId);
        List<Map<String, Object>> bands = jdbc.sql("""
                        SELECT id
                        FROM fee.fee_bands
                        WHERE academic_year_id = :academicYearId
                        ORDER BY class_from ASC, name ASC
                        """)
                .param("academicYearId", year.get("id"))
                .query(String.class)
                .list()
                .stream()
                .map(this::bandWithItems)
                .toList();
        return row("academicYearId", year.get("id"), "academicYear", year.get("label"), "bands", bands);
    }

    public Map<String, Object> matchBand(String classId) {
        int sort = classSortOrder(classId);
        String academicYearId = currentAcademicYearId();
        return jdbc.sql("""
                        SELECT id
                        FROM fee.fee_bands
                        WHERE academic_year_id = :academicYearId
                          AND class_from <= :sort
                          AND class_to >= :sort
                        ORDER BY class_from ASC, name ASC
                        LIMIT 1
                        """)
                .param("academicYearId", academicYearId)
                .param("sort", sort)
                .query(String.class)
                .optional()
                .map(this::bandWithItems)
                .orElseGet(this::row);
    }

    public byte[] feeStructurePdf(String academicYearId) {
        Map<String, Object> year = academicYear(academicYearId);
        Long bandCount = jdbc.sql("SELECT COUNT(*) FROM fee.fee_bands WHERE academic_year_id = :academicYearId")
                .param("academicYearId", year.get("id"))
                .query(Long.class)
                .single();
        return simplePdf("Fee structure " + year.get("label") + " | bands " + (bandCount == null ? 0 : bandCount));
    }

    @Transactional
    public Map<String, Object> createBand(Map<String, Object> request) {
        String name = requireText(request.get("name"), "Band name is required");
        int classFrom = intValue(request.get("classFrom"), 1);
        int classTo = intValue(request.get("classTo"), classFrom);
        validateClassRange(classFrom, classTo);
        String schedules = schedulesCsv(request.get("schedules"), true);
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        String academicYearId = currentAcademicYearId();

        jdbc.sql("""
                INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, active_schedules_csv,
                                      created_at, updated_at, academic_year_id)
                VALUES (:id, :name, :classFrom, :classTo, :discount, :schedules, :createdAt, :updatedAt, :academicYearId)
                """)
                .param("id", id)
                .param("name", name)
                .param("classFrom", classFrom)
                .param("classTo", classTo)
                .param("discount", doubleValue(request.get("discount"), 0))
                .param("schedules", schedules)
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("academicYearId", academicYearId)
                .update();
        return bandWithItems(id);
    }

    @Transactional
    public Map<String, Object> updateBand(String id, Map<String, Object> request) {
        Map<String, Object> current = bandRecord(id);
        String name = textOrDefault(request.get("name"), String.valueOf(current.get("name")));
        int classFrom = request.containsKey("classFrom") ? intValue(request.get("classFrom"), 1)
                : ((Number) current.get("classFrom")).intValue();
        int classTo = request.containsKey("classTo") ? intValue(request.get("classTo"), classFrom)
                : ((Number) current.get("classTo")).intValue();
        validateClassRange(classFrom, classTo);
        String schedules = schedulesCsv(request.get("schedules"), false);
        String scheduleValue = schedules.isBlank() ? (String) current.get("activeSchedulesCsv") : schedules;

        jdbc.sql("""
                UPDATE fee.fee_bands
                SET name = :name, class_from = :classFrom, class_to = :classTo, discount = :discount,
                    active_schedules_csv = :schedules, updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("id", id)
                .param("name", name)
                .param("classFrom", classFrom)
                .param("classTo", classTo)
                .param("discount", request.containsKey("discount")
                        ? doubleValue(request.get("discount"), 0)
                        : ((Number) current.get("discount")).doubleValue())
                .param("schedules", scheduleValue)
                .param("updatedAt", OffsetDateTime.now())
                .update();
        return bandWithItems(id);
    }

    @Transactional
    public Map<String, Object> patchBand(String id, Map<String, Object> request) {
        bandRecord(id);
        String schedules = schedulesCsv(request.get("schedules"), false);
        if (request.containsKey("discount") || request.containsKey("bandDiscount")) {
            jdbc.sql("UPDATE fee.fee_bands SET discount = :discount, updated_at = :updatedAt WHERE id = :id")
                    .param("id", id)
                    .param("discount", doubleValue(firstPresent(request, "discount", "bandDiscount"), 0))
                    .param("updatedAt", OffsetDateTime.now())
                    .update();
        }
        if (!schedules.isBlank()) {
            jdbc.sql("UPDATE fee.fee_bands SET active_schedules_csv = :schedules, updated_at = :updatedAt WHERE id = :id")
                    .param("id", id)
                    .param("schedules", schedules)
                    .param("updatedAt", OffsetDateTime.now())
                    .update();
        }
        return bandWithItems(id);
    }

    @Transactional
    public void deleteBand(String id) {
        bandRecord(id);
        jdbc.sql("DELETE FROM fee.fee_items WHERE band_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM fee.fee_bands WHERE id = :id").param("id", id).update();
    }

    @Transactional
    public Map<String, Object> createItem(Map<String, Object> request) {
        String bandId = requireText(request.get("bandId"), "Band id is required");
        bandRecord(bandId);
        String id = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO fee.fee_items(id, name, frequency, amount, created_at, updated_at, band_id)
                VALUES (:id, :name, :frequency, :amount, :createdAt, :updatedAt, :bandId)
                """)
                .param("id", id)
                .param("name", requireText(firstPresent(request, "itemName", "name"), "Item name is required"))
                .param("frequency", textOrDefault(request.get("frequency"), "Annual"))
                .param("amount", toPaise(request.get("amount")))
                .param("createdAt", now)
                .param("updatedAt", now)
                .param("bandId", bandId)
                .update();
        return bandWithItems(bandId);
    }

    @Transactional
    public Map<String, Object> updateItem(String id, Map<String, Object> request) {
        Map<String, Object> item = itemRecord(id);
        jdbc.sql("""
                UPDATE fee.fee_items
                SET name = :name, frequency = :frequency, amount = :amount, updated_at = :updatedAt
                WHERE id = :id
                """)
                .param("id", id)
                .param("name", request.containsKey("itemName") || request.containsKey("name")
                        ? requireText(firstPresent(request, "itemName", "name"), "Item name is required")
                        : item.get("name"))
                .param("frequency", request.containsKey("frequency")
                        ? textOrDefault(request.get("frequency"), "Annual")
                        : item.get("frequency"))
                .param("amount", request.containsKey("amount") ? toPaise(request.get("amount")) : item.get("amount"))
                .param("updatedAt", OffsetDateTime.now())
                .update();
        return bandWithItems(String.valueOf(item.get("bandId")));
    }

    @Transactional
    public String deleteItem(String id) {
        Map<String, Object> item = itemRecord(id);
        jdbc.sql("DELETE FROM fee.fee_items WHERE id = :id").param("id", id).update();
        return String.valueOf(item.get("bandId"));
    }

    public List<FeeAssignmentRow> assignments(Long studentId, String academicYearId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, schedule, band_discount, manual_discount, surcharge, net_payable,
                       paid_amount, assigned_by, assigned_at, updated_by, updated_at,
                       student_id, band_id, academic_year_id
                FROM fee.fee_assignments
                WHERE 1=1
                """);
        if (studentId != null) sql.append(" AND student_id = :studentId");
        if (academicYearId != null && !academicYearId.isBlank()) sql.append(" AND academic_year_id = :academicYearId");
        sql.append(" ORDER BY assigned_at DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (studentId != null) spec = spec.param("studentId", studentId);
        if (academicYearId != null && !academicYearId.isBlank()) spec = spec.param("academicYearId", academicYearId);
        return spec.query(FeeAssignmentRow.class).list();
    }

    public List<PaymentRow> payments(Long studentId, String assignmentId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT pr.id, pr.amount, pr.mode, pr.notes, pr.paid_at, pr.recorded_by, pr.receipt_number,
                       pr.created_at, pr.student_id, pr.assignment_id, COALESCE(s.full_name, '') AS student
                FROM fee.payment_records pr
                LEFT JOIN student.students s ON s.id = pr.student_id
                WHERE 1=1
                """);
        if (studentId != null) sql.append(" AND pr.student_id = :studentId");
        if (assignmentId != null && !assignmentId.isBlank()) sql.append(" AND pr.assignment_id = :assignmentId");
        sql.append(" ORDER BY pr.paid_at DESC, pr.created_at DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (studentId != null) spec = spec.param("studentId", studentId);
        if (assignmentId != null && !assignmentId.isBlank()) spec = spec.param("assignmentId", assignmentId);
        return spec.query(PaymentRow.class).list();
    }

    public Map<String, Object> feeReport(String classId, String sectionId, String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId);
        return row("content", feeReportRows(classId, sectionId, academicYearId, schoolId, false));
    }

    public Map<String, Object> feeOverdue(String classId, String sectionId, String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId);
        return row("content", feeReportRows(classId, sectionId, academicYearId, schoolId, true).stream()
                .map(row -> row(
                        "student", row.get("student"),
                        "schedule", row.get("schedule"),
                        "dueAmount", row.get("due"),
                        "daysOverdue", 12 + Math.floorMod((int) longValue(row.get("studentId"), 0L), 24)))
                .toList());
    }

    public Map<String, Object> feeReminderRequests(
            String classId, String sectionId, String academicYearId, Long schoolId, Long actorId) {
        academicYearId = resolveAcademicYearId(academicYearId);
        List<Map<String, Object>> requests = jdbc.sql("""
                        SELECT fa.id AS assignment_id, fa.academic_year_id,
                               GREATEST(fa.net_payable - fa.paid_amount, 0) AS due_amount,
                               s.id AS student_id, s.full_name AS student_name,
                               COALESCE(NULLIF(s.father_contact, ''), NULLIF(s.phone, '')) AS destination,
                               s.school_id, s.class_id, s.section_id
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        WHERE s.school_id = :schoolId
                          AND s.class_id = :classId
                          AND s.section_id = :sectionId
                          AND fa.academic_year_id = :academicYearId
                          AND GREATEST(fa.net_payable - fa.paid_amount, 0) > 0
                        ORDER BY s.full_name ASC
                        """)
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> {
                    String requestId = UUID.randomUUID().toString();
                    long studentId = rs.getLong("student_id");
                    long dueAmount = rs.getLong("due_amount");
                    return row(
                            "reminderRequestId", requestId,
                            "assignmentId", rs.getString("assignment_id"),
                            "studentId", studentId,
                            "schoolId", rs.getLong("school_id"),
                            "academicYearId", rs.getString("academic_year_id"),
                            "classId", rs.getString("class_id"),
                            "sectionId", rs.getString("section_id"),
                            "dueAmount", dueAmount,
                            "actorId", actorId,
                            "sourceEventType", "fees.fee-reminder-requested.v1",
                            "sourceEventId", requestId,
                            "notificationType", "FEE_REMINDER",
                            "channel", "SMS",
                            "destination", rs.getString("destination"),
                            "recipientName", rs.getString("student_name"),
                            "subject", "Fee payment reminder",
                            "template", "fee-reminder.v1",
                            "variables", row(
                                    "assignmentId", rs.getString("assignment_id"),
                                    "studentId", studentId,
                                    "studentName", rs.getString("student_name"),
                                    "academicYearId", rs.getString("academic_year_id"),
                                    "dueAmount", dueAmount));
                })
                .list();
        return row("ok", true, "queued", requests.size(), "classId", classId, "sectionId", sectionId,
                "content", requests);
    }

    public Map<String, Object> feesModule(String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId);
        Long collected = jdbc.sql("""
                        SELECT COALESCE(SUM(p.amount), 0)
                        FROM fee.payment_records p
                        JOIN student.students s ON s.id = p.student_id
                        WHERE s.school_id = :schoolId
                        """)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        Long target = jdbc.sql("""
                        SELECT COALESCE(SUM(net_payable), 0)
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        WHERE fa.academic_year_id = :academicYearId
                          AND s.school_id = :schoolId
                        """)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        List<Map<String, Object>> records = jdbc.sql("""
                        SELECT s.id AS student_id, s.full_name AS student_name, fb.name AS plan_name,
                               fa.schedule, GREATEST(fa.net_payable - fa.paid_amount, 0) AS due_amount,
                               COALESCE(fi.total_annual_fee, 0) AS total_annual_fee,
                               fa.paid_amount
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        JOIN fee.fee_bands fb ON fb.id = fa.band_id
                        LEFT JOIN (
                            SELECT band_id, SUM(amount) AS total_annual_fee
                            FROM fee.fee_items
                            GROUP BY band_id
                        ) fi ON fi.band_id = fb.id
                        WHERE fa.academic_year_id = :academicYearId
                          AND s.school_id = :schoolId
                        ORDER BY s.full_name ASC
                        """)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("student_name"),
                        "planName", rs.getString("plan_name"),
                        "schedule", rs.getString("schedule"),
                        "dueAmount", rs.getLong("due_amount"),
                        "totalAnnualFee", rs.getLong("total_annual_fee"),
                        "paidAmount", rs.getLong("paid_amount")))
                .list();
        return row("summary", row("collected", collected == null ? 0 : collected, "target", target == null ? 0 : target),
                "records", records);
    }

    public Map<String, Object> feeOverdueCount(String academicYearId, Long schoolId) {
        academicYearId = resolveAcademicYearId(academicYearId);
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                        FROM fee.fee_assignments fa
                        JOIN student.students s ON s.id = fa.student_id
                        WHERE fa.academic_year_id = :academicYearId
                          AND s.school_id = :schoolId
                          AND GREATEST(fa.net_payable - fa.paid_amount, 0) > 0
                        """)
                .param("academicYearId", academicYearId)
                .param("schoolId", schoolId)
                .query(Long.class)
                .single();
        return row("count", count == null ? 0 : count);
    }

    public Map<String, Object> receiptByPaymentId(String paymentId) {
        return receipt("p.id = :paymentId", spec -> spec.param("paymentId", paymentId), "Payment not found");
    }

    public Map<String, Object> receiptByReceiptNumber(String receiptNumber) {
        return receipt("p.receipt_number = :receiptNumber", spec -> spec.param("receiptNumber", receiptNumber), "Receipt not found");
    }

    public byte[] receiptPdfByPaymentId(String paymentId) {
        return receiptPdf(receiptByPaymentId(paymentId));
    }

    public byte[] receiptPdfByReceiptNumber(String receiptNumber) {
        return receiptPdf(receiptByReceiptNumber(receiptNumber));
    }

    @Transactional
    public Map<String, Object> assignFeePlan(Map<String, Object> request) {
        long studentId = longValue(request.get("studentId"), -1);
        if (studentId <= 0) {
            throw new IllegalArgumentException("Student id is required");
        }
        String bandId = requireText(request.get("bandId"), "Band id is required");
        String schedule = requireText(request.get("schedule"), "Payment schedule is required");
        String academicYearId = currentAcademicYearId();
        Long schoolId = studentSchoolId(studentId);
        Map<String, Object> band = bandRecord(bandId);

        String assignmentId = jdbc.sql("""
                SELECT id FROM fee.fee_assignments
                WHERE student_id = :studentId AND academic_year_id = :academicYearId
                """)
                .param("studentId", studentId)
                .param("academicYearId", academicYearId)
                .query(String.class)
                .optional()
                .orElse(UUID.randomUUID().toString());
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM fee.fee_assignments WHERE id = :id")
                .param("id", assignmentId)
                .query(Long.class)
                .single() > 0;
        long bandTotal = bandTotal(bandId);
        double bandDiscount = request.containsKey("bandDiscount")
                ? doubleValue(request.get("bandDiscount"), 0)
                : ((Number) band.get("discount")).doubleValue();
        double manualDiscount = doubleValue(request.get("manualDiscount"), 0);
        double surcharge = "Annual".equalsIgnoreCase(schedule) ? 0 : doubleValue(request.get("surcharge"), 0);
        long netPayable = calculateNetPayable(bandTotal, bandDiscount, manualDiscount, surcharge, schedule);
        Long actorId = request.containsKey("actorId") ? longValue(request.get("actorId"), 0) : null;
        OffsetDateTime now = OffsetDateTime.now();

        if (exists) {
            jdbc.sql("""
                    UPDATE fee.fee_assignments
                    SET schedule = :schedule, band_discount = :bandDiscount, manual_discount = :manualDiscount,
                        surcharge = :surcharge, net_payable = :netPayable, updated_by = :actorId,
                        updated_at = :updatedAt, band_id = :bandId, academic_year_id = :academicYearId
                    WHERE id = :id
                    """)
                    .param("id", assignmentId)
                    .param("schedule", schedule)
                    .param("bandDiscount", bandDiscount)
                    .param("manualDiscount", manualDiscount)
                    .param("surcharge", surcharge)
                    .param("netPayable", netPayable)
                    .param("actorId", actorId)
                    .param("updatedAt", now)
                    .param("bandId", bandId)
                    .param("academicYearId", academicYearId)
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO fee.fee_assignments(id, schedule, band_discount, manual_discount, surcharge,
                                                net_payable, paid_amount, assigned_by, assigned_at,
                                                updated_by, updated_at, student_id, band_id, academic_year_id, version,
                                                school_id)
                    VALUES (:id, :schedule, :bandDiscount, :manualDiscount, :surcharge, :netPayable, 0,
                            :actorId, :assignedAt, :actorId, :updatedAt, :studentId, :bandId, :academicYearId, 0,
                            :schoolId)
                    """)
                    .param("id", assignmentId)
                    .param("schedule", schedule)
                    .param("bandDiscount", bandDiscount)
                    .param("manualDiscount", manualDiscount)
                    .param("surcharge", surcharge)
                    .param("netPayable", netPayable)
                    .param("actorId", actorId)
                    .param("assignedAt", now)
                    .param("updatedAt", now)
                    .param("studentId", studentId)
                    .param("bandId", bandId)
                    .param("academicYearId", academicYearId)
                    .param("schoolId", schoolId)
                    .update();
        }
        updateStudentFeeStatus(studentId, assignmentId);
        return row("ok", true, "assignment", assignmentRecord(assignmentId));
    }

    @Transactional
    public Map<String, Object> recordPayment(Map<String, Object> request) {
        long studentId = longValue(request.get("studentId"), -1);
        long amount = longValue(request.get("amount"), 0);
        if (studentId <= 0) {
            throw new IllegalArgumentException("Student id is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        Long schoolId = studentSchoolId(studentId);
        String academicYearId = currentAcademicYearId();
        Map<String, Object> assignment = jdbc.sql("""
                SELECT id, net_payable, paid_amount FROM fee.fee_assignments
                WHERE student_id = :studentId AND academic_year_id = :academicYearId
                ORDER BY assigned_at DESC
                LIMIT 1
                """)
                .param("studentId", studentId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "netPayable", rs.getLong("net_payable"),
                        "paidAmount", rs.getLong("paid_amount")))
                .optional()
                .or(() -> jdbc.sql("""
                        SELECT id, net_payable, paid_amount FROM fee.fee_assignments
                        WHERE student_id = :studentId
                        ORDER BY assigned_at DESC
                        LIMIT 1
                        """)
                        .param("studentId", studentId)
                        .query((rs, rowNum) -> row(
                                "id", rs.getString("id"),
                                "netPayable", rs.getLong("net_payable"),
                                "paidAmount", rs.getLong("paid_amount")))
                        .optional())
                .orElseThrow(() -> new IllegalArgumentException("Fee assignment not found. Assign a fee plan first."));

        String paymentId = UUID.randomUUID().toString();
        OffsetDateTime paidAt = parsePaidAt(request.get("paidAt"));
        OffsetDateTime now = OffsetDateTime.now();
        Long actorId = request.containsKey("actorId") ? longValue(request.get("actorId"), 0) : null;
        String receiptNumber = "RCPT-" + System.currentTimeMillis();
        String assignmentId = String.valueOf(assignment.get("id"));

        jdbc.sql("""
                INSERT INTO fee.payment_records(id, amount, mode, notes, paid_at, recorded_by, receipt_number,
                                            created_at, student_id, assignment_id, version, school_id)
                VALUES (:id, :amount, :mode, :notes, :paidAt, :recordedBy, :receiptNumber,
                        :createdAt, :studentId, :assignmentId, 0, :schoolId)
                """)
                .param("id", paymentId)
                .param("amount", amount)
                .param("mode", textOrDefault(request.get("mode"), "UPI"))
                .param("notes", textOrDefault(request.get("notes"), ""))
                .param("paidAt", paidAt)
                .param("recordedBy", actorId)
                .param("receiptNumber", receiptNumber)
                .param("createdAt", now)
                .param("studentId", studentId)
                .param("assignmentId", assignmentId)
                .param("schoolId", schoolId)
                .update();

        jdbc.sql("""
                UPDATE fee.fee_assignments
                SET paid_amount = paid_amount + :amount, updated_by = :actorId, updated_at = :updatedAt,
                    academic_year_id = :academicYearId
                WHERE id = :assignmentId
                """)
                .param("amount", amount)
                .param("actorId", actorId)
                .param("updatedAt", now)
                .param("academicYearId", academicYearId)
                .param("assignmentId", assignmentId)
                .update();
        updateStudentFeeStatus(studentId, assignmentId);
        Map<String, Object> updatedAssignment = jdbc.sql("""
                        SELECT paid_amount, net_payable
                        FROM fee.fee_assignments
                        WHERE id = :assignmentId
                        """)
                .param("assignmentId", assignmentId)
                .query((rs, rowNum) -> row(
                        "paidAmount", rs.getLong("paid_amount"),
                        "netPayable", rs.getLong("net_payable")))
                .single();
        return row(
                "paymentId", paymentId,
                "receiptNumber", receiptNumber,
                "receiptUrl", "/api/v1/receipts/" + paymentId + "/pdf",
                "studentId", studentId,
                "schoolId", schoolId,
                "assignmentId", assignmentId,
                "academicYearId", academicYearId,
                "amount", amount,
                "mode", textOrDefault(request.get("mode"), "UPI"),
                "paidAmount", updatedAssignment.get("paidAmount"),
                "netPayable", updatedAssignment.get("netPayable"),
                "actorId", actorId,
                "paidAt", paidAt);
    }

    private Map<String, Object> receipt(String predicate, ParamBinder binder, String notFoundMessage) {
        var spec = jdbc.sql("""
                        SELECT p.id, p.amount, p.mode, p.paid_at, p.receipt_number,
                               s.id AS student_id, s.full_name AS student_name
                        FROM fee.payment_records p
                        LEFT JOIN student.students s ON s.id = p.student_id
                        WHERE """ + " " + predicate + " " + """
                        LIMIT 1
                        """);
        return binder.bind(spec)
                .query((rs, rowNum) -> row(
                        "paymentId", rs.getString("id"),
                        "receiptNumber", rs.getString("receipt_number"),
                        "studentId", rs.getLong("student_id"),
                        "student", rs.getString("student_name"),
                        "amount", rs.getLong("amount"),
                        "mode", rs.getString("mode"),
                        "paidAt", rs.getObject("paid_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(notFoundMessage));
    }

    private List<Map<String, Object>> feeReportRows(
            String classId, String sectionId, String academicYearId, Long schoolId, boolean overdueOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT fa.id, fa.schedule, fa.band_discount, fa.manual_discount, fa.surcharge,
                       fa.net_payable, fa.paid_amount, s.id AS student_id, s.full_name AS student_name,
                       fb.id AS band_id, fb.name AS plan_name,
                       COALESCE(fi.total_annual_fee, 0) AS total_annual_fee,
                       latest_payment.id AS latest_payment_id
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                JOIN fee.fee_bands fb ON fb.id = fa.band_id
                LEFT JOIN (
                    SELECT band_id, SUM(amount) AS total_annual_fee
                    FROM fee.fee_items
                    GROUP BY band_id
                ) fi ON fi.band_id = fb.id
                LEFT JOIN LATERAL (
                    SELECT id
                    FROM fee.payment_records p
                    WHERE p.student_id = s.id
                    ORDER BY p.paid_at DESC NULLS LAST, p.created_at DESC NULLS LAST
                    LIMIT 1
                ) latest_payment ON true
                WHERE s.school_id = :schoolId
                  AND s.class_id = :classId
                  AND s.section_id = :sectionId
                  AND fa.academic_year_id = :academicYearId
                """);
        if (overdueOnly) sql.append(" AND GREATEST(fa.net_payable - fa.paid_amount, 0) > 0");
        sql.append(" ORDER BY s.full_name ASC");

        return jdbc.sql(sql.toString())
                .param("schoolId", schoolId)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> {
                    long due = Math.max(0, rs.getLong("net_payable") - rs.getLong("paid_amount"));
                    return row(
                            "studentId", rs.getLong("student_id"),
                            "paymentId", textOrDefault(rs.getString("latest_payment_id"), ""),
                            "student", rs.getString("student_name"),
                            "planName", rs.getString("plan_name"),
                            "schedule", rs.getString("schedule"),
                            "totalAnnualFee", rs.getLong("total_annual_fee"),
                            "discounts", round(rs.getDouble("band_discount") + rs.getDouble("manual_discount")),
                            "surcharge", round(rs.getDouble("surcharge")),
                            "paid", rs.getLong("paid_amount"),
                            "due", due,
                            "status", due <= 0 ? "Paid" : "Overdue");
                })
                .list();
    }

    public record FeeBandRow(
            String id,
            String name,
            Integer classFrom,
            Integer classTo,
            Double discount,
            String activeSchedulesCsv,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String academicYearId) {
    }

    public record FeeItemRow(
            String id,
            String name,
            String frequency,
            Long amount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String bandId) {
    }

    public record FeeAssignmentRow(
            String id,
            String schedule,
            Double bandDiscount,
            Double manualDiscount,
            Double surcharge,
            Long netPayable,
            Long paidAmount,
            Long assignedBy,
            OffsetDateTime assignedAt,
            Long updatedBy,
            OffsetDateTime updatedAt,
            Long studentId,
            String bandId,
            String academicYearId) {
    }

    public record PaymentRow(
            String id,
            Long amount,
            String mode,
            String notes,
            OffsetDateTime paidAt,
            Long recordedBy,
            String receiptNumber,
            OffsetDateTime createdAt,
            Long studentId,
            String assignmentId,
            String student) {
    }

    private interface ParamBinder {
        org.springframework.jdbc.core.simple.JdbcClient.StatementSpec bind(
                org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec);
    }

    private String currentAcademicYearId() {
        return jdbc.sql("SELECT id FROM tenant_school.academic_years WHERE active = true ORDER BY id LIMIT 1")
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private String resolveAcademicYearId(String academicYearId) {
        return academicYearId == null || academicYearId.isBlank() ? currentAcademicYearId() : academicYearId;
    }

    private Map<String, Object> academicYear(String academicYearId) {
        String sql = (academicYearId == null || academicYearId.isBlank())
                ? "SELECT id, label FROM tenant_school.academic_years WHERE active = true ORDER BY id LIMIT 1"
                : "SELECT id, label FROM tenant_school.academic_years WHERE id = :academicYearId LIMIT 1";
        var spec = jdbc.sql(sql);
        if (academicYearId != null && !academicYearId.isBlank()) {
            spec = spec.param("academicYearId", academicYearId);
        }
        return spec.query((rs, rowNum) -> row("id", rs.getString("id"), "label", rs.getString("label")))
                .optional()
                .orElseGet(() -> jdbc.sql("SELECT id, label FROM tenant_school.academic_years WHERE active = true ORDER BY id LIMIT 1")
                        .query((rs, rowNum) -> row("id", rs.getString("id"), "label", rs.getString("label")))
                        .optional()
                        .orElseThrow(() -> new IllegalArgumentException("No active academic year configured")));
    }

    private Map<String, Object> bandWithItems(String id) {
        Map<String, Object> band = bandRecord(id);
        List<Map<String, Object>> items = jdbc.sql("""
                SELECT id, name, frequency, amount, created_at, updated_at, band_id
                FROM fee.fee_items
                WHERE band_id = :id
                ORDER BY created_at
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "frequency", rs.getString("frequency"),
                        "amount", rs.getLong("amount"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "updatedAt", rs.getObject("updated_at", OffsetDateTime.class),
                        "bandId", rs.getString("band_id")))
                .list();
        band.put("items", items);
        band.put("annualTotal", items.stream().mapToLong(item -> ((Number) item.get("amount")).longValue()).sum());
        return band;
    }

    private Map<String, Object> bandRecord(String id) {
        return jdbc.sql("""
                SELECT b.id, b.name, b.class_from, b.class_to, b.discount, b.active_schedules_csv,
                       b.created_at, b.updated_at, b.academic_year_id, y.label AS academic_year
                FROM fee.fee_bands b
                LEFT JOIN tenant_school.academic_years y ON y.id = b.academic_year_id
                WHERE b.id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "groupName", rs.getString("name"),
                        "classFrom", rs.getInt("class_from"),
                        "classTo", rs.getInt("class_to"),
                        "discount", rs.getDouble("discount"),
                        "activeSchedulesCsv", rs.getString("active_schedules_csv"),
                        "activeSchedules", splitCsv(rs.getString("active_schedules_csv")),
                        "allowedSchedules", splitCsv(rs.getString("active_schedules_csv")),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "updatedAt", rs.getObject("updated_at", OffsetDateTime.class),
                        "academicYearId", rs.getString("academic_year_id"),
                        "academicYear", rs.getString("academic_year")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Fee band not found"));
    }

    private Map<String, Object> itemRecord(String id) {
        return jdbc.sql("SELECT id, name, frequency, amount, band_id FROM fee.fee_items WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "name", rs.getString("name"),
                        "frequency", rs.getString("frequency"),
                        "amount", rs.getLong("amount"),
                        "bandId", rs.getString("band_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Fee item not found"));
    }

    private Map<String, Object> assignmentRecord(String id) {
        return jdbc.sql("""
                SELECT fa.id, fa.schedule, fa.band_discount, fa.manual_discount, fa.surcharge,
                       fa.net_payable, fa.paid_amount, fa.assigned_by, fa.assigned_at,
                       fa.updated_by, fa.updated_at, fa.student_id, fa.band_id,
                       fa.academic_year_id, s.school_id
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.id = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> row(
                        "id", rs.getString("id"),
                        "schedule", rs.getString("schedule"),
                        "bandDiscount", rs.getDouble("band_discount"),
                        "manualDiscount", rs.getDouble("manual_discount"),
                        "surcharge", rs.getDouble("surcharge"),
                        "netPayable", rs.getLong("net_payable"),
                        "paidAmount", rs.getLong("paid_amount"),
                        "assignedBy", rs.getLong("assigned_by"),
                        "assignedAt", rs.getObject("assigned_at", OffsetDateTime.class),
                        "updatedBy", rs.getLong("updated_by"),
                        "updatedAt", rs.getObject("updated_at", OffsetDateTime.class),
                        "studentId", rs.getLong("student_id"),
                        "schoolId", rs.getLong("school_id"),
                        "bandId", rs.getString("band_id"),
                        "academicYearId", rs.getString("academic_year_id")))
                .single();
    }

    private void requireStudent(long studentId) {
        studentSchoolId(studentId);
    }

    private Long studentSchoolId(long studentId) {
        return jdbc.sql("SELECT school_id FROM student.students WHERE id = :studentId")
                .param("studentId", studentId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));
    }

    private long bandTotal(String bandId) {
        Long total = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM fee.fee_items WHERE band_id = :bandId")
                .param("bandId", bandId)
                .query(Long.class)
                .single();
        return total == null ? 0 : total;
    }

    private long calculateNetPayable(long total, double bandDiscount, double manualDiscount, double surcharge, String schedule) {
        long bandAmount = percentageAmount(total, bandDiscount);
        long manualAmount = percentageAmount(total, manualDiscount);
        long surchargeAmount = "Annual".equalsIgnoreCase(schedule) ? 0 : percentageAmount(total, surcharge);
        return Math.max(total - bandAmount - manualAmount + surchargeAmount, 0);
    }

    private long percentageAmount(long total, double percent) {
        if (total <= 0 || !Double.isFinite(percent) || percent == 0) {
            return 0;
        }
        return Math.round(total * percent / 100.0);
    }

    private void updateStudentFeeStatus(long studentId, String assignmentId) {
        Map<String, Object> assignment = jdbc.sql("SELECT net_payable, paid_amount FROM fee.fee_assignments WHERE id = :id")
                .param("id", assignmentId)
                .query((rs, rowNum) -> row(
                        "netPayable", rs.getLong("net_payable"),
                        "paidAmount", rs.getLong("paid_amount")))
                .single();
        String status = ((Number) assignment.get("paidAmount")).longValue() >= ((Number) assignment.get("netPayable")).longValue()
                ? "Paid"
                : "Overdue";
        jdbc.sql("UPDATE student.students SET fee_status = :status, updated_at = :updatedAt WHERE id = :studentId")
                .param("status", status)
                .param("updatedAt", OffsetDateTime.now())
                .param("studentId", studentId)
                .update();
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String key : keys) {
            if (request.containsKey(key) && request.get(key) != null) {
                return request.get(key);
            }
        }
        return null;
    }

    private String requireText(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private String textOrDefault(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double round(double value) {
        if (!Double.isFinite(value)) return 0;
        return Math.round(value * 10.0) / 10.0;
    }

    private long toPaise(Object value) {
        double amount = doubleValue(value, 0);
        if (!Double.isFinite(amount) || amount < 0) {
            return 0;
        }
        return Math.round(amount > 100_000 ? amount : amount * 100.0);
    }

    private OffsetDateTime parsePaidAt(Object value) {
        if (value == null) {
            return OffsetDateTime.now();
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("paidAt must be an ISO-8601 offset date-time", ex);
        }
    }

    private void validateClassRange(int classFrom, int classTo) {
        if (classTo < classFrom) {
            throw new IllegalArgumentException("Class to must be >= class from");
        }
    }

    private String schedulesCsv(Object value, boolean required) {
        if (!(value instanceof List<?> list)) {
            if (required) {
                throw new IllegalArgumentException("At least one payment schedule is required");
            }
            return "";
        }
        String csv = list.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (required && csv.isBlank()) {
            throw new IllegalArgumentException("At least one payment schedule is required");
        }
        return csv;
    }

    private List<String> splitCsv(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
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

    private byte[] receiptPdf(Map<String, Object> payment) {
        return simplePdf("Receipt " + textOrDefault(payment.get("receiptNumber"), "")
                + " | Student: " + textOrDefault(payment.get("studentName"), textOrDefault(payment.get("student"), ""))
                + " | Amount: " + textOrDefault(payment.get("amount"), "0")
                + " | Mode: " + textOrDefault(payment.get("mode"), "")
                + " | Paid at: " + textOrDefault(payment.get("paidAt"), ""));
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
