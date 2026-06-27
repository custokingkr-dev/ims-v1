package com.custoking.ims.billingservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class BillingInvoiceRepository {

    private static final String SEQUENCE_ID = "SINGLETON";

    private final JdbcClient jdbc;
    private final String invoiceTable;
    private final String sequenceTable;
    private final String customerTable;
    private final String schoolInvoiceTable;
    private final String schoolInvoiceItemTable;
    private final String paymentTable;

    public BillingInvoiceRepository(
            JdbcClient jdbc,
            @Value("${billing.db.schema:billing}") String schema) {
        this.jdbc = jdbc;
        this.invoiceTable = qualifiedTable(schema, "superadmin_invoices");
        this.sequenceTable = qualifiedTable(schema, "superadmin_order_seq");
        this.customerTable = qualifiedTable(schema, "billing_customers");
        this.schoolInvoiceTable = qualifiedTable(schema, "billing_invoices");
        this.schoolInvoiceItemTable = qualifiedTable(schema, "billing_invoice_items");
        this.paymentTable = qualifiedTable(schema, "billing_payments");
    }

    public List<InvoiceRow> list(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder(invoiceSelect()).append(" WHERE 1 = 1");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(InvoiceRow.class).list();
    }

    public InvoiceRow byId(String id) {
        return jdbc.sql(invoiceSelect() + " WHERE id = :id")
                .param("id", id)
                .query(InvoiceRow.class)
                .optional()
                .orElse(null);
    }

    public Map<String, Object> stats() {
        List<InvoiceRow> all = list(null, null, 500);
        long paid = all.stream()
                .filter(invoice -> "Paid".equalsIgnoreCase(str(invoice.status(), "")))
                .count();
        long pending = all.stream()
                .filter(invoice -> "Awaiting payment".equalsIgnoreCase(str(invoice.status(), "")))
                .count();
        long total = all.stream()
                .mapToLong(invoice -> invoice.total() == null ? 0L : invoice.total())
                .sum();
        return Map.of(
                "sentThisMonth", (long) all.size(),
                "paid", paid,
                "pending", pending,
                "totalInvoiced", total);
    }

    public InvoiceRow byOrderRef(String orderRef) {
        return jdbc.sql(invoiceSelect() + " WHERE order_ref = :orderRef ORDER BY created_at DESC LIMIT 1")
                .param("orderRef", orderRef)
                .query(InvoiceRow.class)
                .optional()
                .orElse(null);
    }

    public InvoiceRow create(Map<String, Object> request) {
        String id = allocateInvoiceId();
        String orderRef = str(request.get("orderRef"), "");
        String school = str(request.get("school"), "");
        Long schoolId = request.get("schoolId") == null ? null : longNum(request.get("schoolId"), 0L);
        String description = str(request.get("description"), "");
        int qty = (int) longNum(request.get("qty"), 1);
        long rate = longNum(request.get("rate"), 0L);
        long amount = longNum(request.get("amount"), (long) qty * rate);
        long gstAmount = Math.round(amount * 0.12);
        long total = amount + gstAmount;
        String issuedAt = LocalDate.now().toString();
        String dueAt = LocalDate.now().plusDays(14).toString();

        jdbc.sql("""
                        INSERT INTO %s
                            (id, order_ref, school, school_id, description, qty, rate, amount,
                             gst_amount, total, status, issued_at, due_at, notes, created_at)
                        VALUES
                            (:id, :orderRef, :school, :schoolId, :description, :qty, :rate, :amount,
                             :gstAmount, :total, :status, :issuedAt, :dueAt, :notes, now())
                        """.formatted(invoiceTable))
                .param("id", id)
                .param("orderRef", orderRef)
                .param("school", school)
                .param("schoolId", schoolId)
                .param("description", description)
                .param("qty", qty)
                .param("rate", rate)
                .param("amount", amount)
                .param("gstAmount", gstAmount)
                .param("total", total)
                .param("status", "Awaiting payment")
                .param("issuedAt", issuedAt)
                .param("dueAt", dueAt)
                .param("notes", trimToNull(str(request.get("notes"), "")))
                .update();
        return byId(id);
    }

    public InvoiceRow update(String id, Map<String, Object> request) {
        InvoiceRow existing = byId(id);
        if (existing == null) {
            return null;
        }
        String description = request.containsKey("description")
                ? str(request.get("description"), "") : existing.description();
        int qty = request.containsKey("qty")
                ? (int) longNum(request.get("qty"), existing.qty()) : existing.qty();
        long rate = request.containsKey("rate")
                ? longNum(request.get("rate"), existing.rate()) : existing.rate();
        String school = request.containsKey("school")
                ? str(request.get("school"), existing.school()) : existing.school();
        String status = request.containsKey("status")
                ? str(request.get("status"), existing.status()) : existing.status();
        String notes = request.containsKey("notes")
                ? trimToNull(str(request.get("notes"), "")) : existing.notes();
        long amount = (long) qty * rate;
        long gstAmount = Math.round(amount * 0.12);
        long total = amount + gstAmount;

        jdbc.sql("""
                        UPDATE %s
                        SET description = :description,
                            qty = :qty,
                            rate = :rate,
                            school = :school,
                            status = :status,
                            notes = :notes,
                            amount = :amount,
                            gst_amount = :gstAmount,
                            total = :total
                        WHERE id = :id
                        """.formatted(invoiceTable))
                .param("id", id)
                .param("description", description)
                .param("qty", qty)
                .param("rate", rate)
                .param("school", school)
                .param("status", status)
                .param("notes", notes)
                .param("amount", amount)
                .param("gstAmount", gstAmount)
                .param("total", total)
                .update();
        return byId(id);
    }

    public List<CustomerRow> customers() {
        return jdbc.sql("""
                SELECT id, code, name, email, phone, gstin, address_line, branch_id, branch_name, active
                FROM %s
                ORDER BY created_at DESC, id DESC
                """.formatted(customerTable))
                .query(CustomerRow.class)
                .list();
    }

    public CustomerRow createCustomer(Map<String, Object> request) {
        String name = str(request.get("name"), "").trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        Long branchId = longObject(request.get("branchId"), 1L);
        String code = str(request.get("code"), "").trim();
        if (code.isBlank()) {
            code = "CUST-" + System.currentTimeMillis();
        }
        Long id = jdbc.sql("""
                INSERT INTO %s (code, name, email, phone, gstin, address_line, branch_id, branch_name, active)
                VALUES (:code, :name, :email, :phone, :gstin, :addressLine, :branchId, :branchName, :active)
                RETURNING id
                """.formatted(customerTable))
                .param("code", code)
                .param("name", name)
                .param("email", trimToNull(str(request.get("email"), "")))
                .param("phone", trimToNull(str(request.get("phone"), "")))
                .param("gstin", trimToNull(str(request.get("gstin"), "")))
                .param("addressLine", trimToNull(str(request.get("addressLine"), "")))
                .param("branchId", branchId)
                .param("branchName", str(request.get("branchName"), "Main Branch"))
                .param("active", booleanValue(request.get("active"), true))
                .query(Long.class)
                .single();
        return customerById(id);
    }

    public List<Map<String, Object>> schoolInvoices() {
        return jdbc.sql("""
                SELECT i.id, i.invoice_no, i.customer_id, c.name AS customer_name, i.branch_id, i.branch_name,
                       i.invoice_date, i.due_date, i.subtotal, i.discount_percent, i.discount_amount,
                       i.tax_amount, i.grand_total, i.paid_amount, i.balance_amount, i.status,
                       i.payment_status, i.approval_status, i.notes
                FROM %s i
                JOIN %s c ON c.id = i.customer_id
                ORDER BY i.created_at DESC, i.id DESC
                """.formatted(schoolInvoiceTable, customerTable))
                .query((rs, rowNum) -> schoolInvoiceMap(
                        rs.getLong("id"),
                        rs.getString("invoice_no"),
                        rs.getLong("customer_id"),
                        rs.getString("customer_name"),
                        rs.getLong("branch_id"),
                        rs.getString("branch_name"),
                        rs.getObject("invoice_date", LocalDate.class).toString(),
                        rs.getObject("due_date", LocalDate.class).toString(),
                        rs.getLong("subtotal"),
                        rs.getBigDecimal("discount_percent").doubleValue(),
                        rs.getLong("discount_amount"),
                        rs.getLong("tax_amount"),
                        rs.getLong("grand_total"),
                        rs.getLong("paid_amount"),
                        rs.getLong("balance_amount"),
                        rs.getString("status"),
                        rs.getString("payment_status"),
                        rs.getString("approval_status"),
                        rs.getString("notes")))
                .list();
    }

    public Map<String, Object> schoolInvoice(Long id) {
        return jdbc.sql("""
                SELECT i.id, i.invoice_no, i.customer_id, c.name AS customer_name, i.branch_id, i.branch_name,
                       i.invoice_date, i.due_date, i.subtotal, i.discount_percent, i.discount_amount,
                       i.tax_amount, i.grand_total, i.paid_amount, i.balance_amount, i.status,
                       i.payment_status, i.approval_status, i.notes
                FROM %s i
                JOIN %s c ON c.id = i.customer_id
                WHERE i.id = :id
                """.formatted(schoolInvoiceTable, customerTable))
                .param("id", id)
                .query((rs, rowNum) -> schoolInvoiceMap(
                        rs.getLong("id"),
                        rs.getString("invoice_no"),
                        rs.getLong("customer_id"),
                        rs.getString("customer_name"),
                        rs.getLong("branch_id"),
                        rs.getString("branch_name"),
                        rs.getObject("invoice_date", LocalDate.class).toString(),
                        rs.getObject("due_date", LocalDate.class).toString(),
                        rs.getLong("subtotal"),
                        rs.getBigDecimal("discount_percent").doubleValue(),
                        rs.getLong("discount_amount"),
                        rs.getLong("tax_amount"),
                        rs.getLong("grand_total"),
                        rs.getLong("paid_amount"),
                        rs.getLong("balance_amount"),
                        rs.getString("status"),
                        rs.getString("payment_status"),
                        rs.getString("approval_status"),
                        rs.getString("notes")))
                .optional()
                .orElse(null);
    }

    public Map<String, Object> createSchoolInvoice(Map<String, Object> request) {
        Long customerId = longObject(request.get("customerId"), null);
        if (customerId == null || customerById(customerId) == null) {
            throw new IllegalArgumentException("Customer not found");
        }
        List<Map<String, Object>> items = itemRequests(request.get("items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("At least one invoice item is required");
        }

        long subtotal = 0;
        long taxAmount = 0;
        for (Map<String, Object> item : items) {
            long quantity = longNum(item.get("quantity"), 1);
            long unitPrice = longNum(item.get("unitPrice"), 0);
            double taxRate = doubleNum(item.get("taxRate"), 0);
            long lineSubtotal = quantity * unitPrice;
            subtotal += lineSubtotal;
            taxAmount += Math.round(lineSubtotal * taxRate / 100);
        }
        double discountPercent = doubleNum(request.get("discountPercent"), 0);
        long discountAmount = Math.round(subtotal * discountPercent / 100);
        long grandTotal = Math.max(0, subtotal - discountAmount + taxAmount);
        LocalDate invoiceDate = parseDate(str(request.get("invoiceDate"), ""), LocalDate.now());
        LocalDate dueDate = parseDate(str(request.get("dueDate"), ""), invoiceDate.plusDays(14));
        String draftInvoiceNo = "DRAFT-" + System.nanoTime();

        Long invoiceId = jdbc.sql("""
                INSERT INTO %s (invoice_no, customer_id, branch_id, branch_name, invoice_date, due_date,
                                subtotal, discount_percent, discount_amount, tax_amount, grand_total,
                                paid_amount, balance_amount, status, payment_status, approval_status, notes)
                VALUES (:draftInvoiceNo, :customerId, :branchId, :branchName, :invoiceDate, :dueDate,
                        :subtotal, :discountPercent, :discountAmount, :taxAmount, :grandTotal,
                        0, :grandTotal, 'ISSUED', 'UNPAID', 'APPROVED', :notes)
                RETURNING id
                """.formatted(schoolInvoiceTable))
                .param("draftInvoiceNo", draftInvoiceNo)
                .param("customerId", customerId)
                .param("branchId", longObject(request.get("branchId"), 1L))
                .param("branchName", str(request.get("branchName"), "Main Branch"))
                .param("invoiceDate", invoiceDate)
                .param("dueDate", dueDate)
                .param("subtotal", subtotal)
                .param("discountPercent", discountPercent)
                .param("discountAmount", discountAmount)
                .param("taxAmount", taxAmount)
                .param("grandTotal", grandTotal)
                .param("notes", trimToNull(str(request.get("notes"), "")))
                .query(Long.class)
                .single();

        String invoiceNo = "INV-" + invoiceDate.getYear() + "-" + String.format("%05d", invoiceId);
        jdbc.sql("UPDATE %s SET invoice_no = :invoiceNo WHERE id = :id".formatted(schoolInvoiceTable))
                .param("invoiceNo", invoiceNo)
                .param("id", invoiceId)
                .update();

        for (Map<String, Object> item : items) {
            long quantity = longNum(item.get("quantity"), 1);
            long unitPrice = longNum(item.get("unitPrice"), 0);
            double taxRate = doubleNum(item.get("taxRate"), 0);
            long lineTotal = quantity * unitPrice + Math.round(quantity * unitPrice * taxRate / 100);
            jdbc.sql("""
                    INSERT INTO %s (invoice_id, description, quantity, unit_price, tax_rate, line_total)
                    VALUES (:invoiceId, :description, :quantity, :unitPrice, :taxRate, :lineTotal)
                    """.formatted(schoolInvoiceItemTable))
                    .param("invoiceId", invoiceId)
                    .param("description", str(item.get("description"), "Invoice item"))
                    .param("quantity", quantity)
                    .param("unitPrice", unitPrice)
                    .param("taxRate", taxRate)
                    .param("lineTotal", lineTotal)
                    .update();
        }
        return schoolInvoice(invoiceId);
    }

    public byte[] schoolInvoicePdf(Long id) {
        Map<String, Object> invoice = schoolInvoice(id);
        if (invoice == null) {
            throw new IllegalArgumentException("Invoice not found");
        }
        String text = "Invoice " + invoice.get("invoiceNo") + "\\nCustomer: " + invoice.get("customerName")
                + "\\nTotal: " + invoice.get("grandTotal");
        return minimalPdf(text);
    }

    public List<PaymentRow> billingPayments() {
        return jdbc.sql("""
                SELECT p.id, p.invoice_id, i.invoice_no, p.branch_id, p.branch_name, p.payment_date,
                       p.amount, p.payment_mode, p.reference_no, p.notes, p.received_by
                FROM %s p
                JOIN %s i ON i.id = p.invoice_id
                ORDER BY p.created_at DESC, p.id DESC
                """.formatted(paymentTable, schoolInvoiceTable))
                .query(PaymentRow.class)
                .list();
    }

    public PaymentRow createBillingPayment(Map<String, Object> request) {
        Long invoiceId = longObject(request.get("invoiceId"), null);
        if (invoiceId == null || schoolInvoice(invoiceId) == null) {
            throw new IllegalArgumentException("Invoice not found");
        }
        Long id = jdbc.sql("""
                INSERT INTO %s (invoice_id, branch_id, branch_name, payment_date, amount,
                                payment_mode, reference_no, notes, received_by)
                VALUES (:invoiceId, :branchId, :branchName, :paymentDate, :amount,
                        :paymentMode, :referenceNo, :notes, :receivedBy)
                RETURNING id
                """.formatted(paymentTable))
                .param("invoiceId", invoiceId)
                .param("branchId", longObject(request.get("branchId"), 1L))
                .param("branchName", str(request.get("branchName"), "Main Branch"))
                .param("paymentDate", parseDate(str(request.get("paymentDate"), ""), LocalDate.now()))
                .param("amount", longNum(request.get("amount"), 0))
                .param("paymentMode", str(request.get("paymentMode"), "UPI"))
                .param("referenceNo", trimToNull(str(request.get("referenceNo"), "")))
                .param("notes", trimToNull(str(request.get("notes"), "")))
                .param("receivedBy", str(request.get("receivedBy"), "System"))
                .query(Long.class)
                .single();
        refreshSchoolInvoicePaymentStatus(invoiceId);
        return billingPayment(id);
    }

    private String allocateInvoiceId() {
        jdbc.sql("""
                        INSERT INTO %s (id, order_seq, invoice_seq)
                        VALUES (:id, 0, 0)
                        ON CONFLICT (id) DO NOTHING
                        """.formatted(sequenceTable))
                .param("id", SEQUENCE_ID)
                .update();
        Long next = jdbc.sql("""
                        UPDATE %s
                        SET invoice_seq = invoice_seq + 1
                        WHERE id = :id
                        RETURNING invoice_seq
                        """.formatted(sequenceTable))
                .param("id", SEQUENCE_ID)
                .query(Long.class)
                .single();
        return "INV-2025-0" + next;
    }

    private CustomerRow customerById(Long id) {
        return jdbc.sql("""
                SELECT id, code, name, email, phone, gstin, address_line, branch_id, branch_name, active
                FROM %s
                WHERE id = :id
                """.formatted(customerTable))
                .param("id", id)
                .query(CustomerRow.class)
                .optional()
                .orElse(null);
    }

    private PaymentRow billingPayment(Long id) {
        return jdbc.sql("""
                SELECT p.id, p.invoice_id, i.invoice_no, p.branch_id, p.branch_name, p.payment_date,
                       p.amount, p.payment_mode, p.reference_no, p.notes, p.received_by
                FROM %s p
                JOIN %s i ON i.id = p.invoice_id
                WHERE p.id = :id
                """.formatted(paymentTable, schoolInvoiceTable))
                .param("id", id)
                .query(PaymentRow.class)
                .single();
    }

    private void refreshSchoolInvoicePaymentStatus(Long invoiceId) {
        Long paid = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM %s WHERE invoice_id = :invoiceId".formatted(paymentTable))
                .param("invoiceId", invoiceId)
                .query(Long.class)
                .single();
        Map<String, Object> invoice = schoolInvoice(invoiceId);
        long total = longNum(invoice.get("grandTotal"), 0);
        long balance = Math.max(0, total - (paid == null ? 0 : paid));
        String paymentStatus = balance == 0 ? "PAID" : (paid == null || paid == 0 ? "UNPAID" : "PARTIAL");
        String status = balance == 0 ? "PAID" : "ISSUED";
        jdbc.sql("""
                UPDATE %s
                SET paid_amount = :paidAmount,
                    balance_amount = :balanceAmount,
                    payment_status = :paymentStatus,
                    status = :status
                WHERE id = :invoiceId
                """.formatted(schoolInvoiceTable))
                .param("paidAmount", paid == null ? 0L : paid)
                .param("balanceAmount", balance)
                .param("paymentStatus", paymentStatus)
                .param("status", status)
                .param("invoiceId", invoiceId)
                .update();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemRequests(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((key, itemValue) -> normalized.put(String.valueOf(key), itemValue));
                items.add(normalized);
            }
        }
        return items;
    }

    private Map<String, Object> schoolInvoiceMap(
            Long id,
            String invoiceNo,
            Long customerId,
            String customerName,
            Long branchId,
            String branchName,
            String invoiceDate,
            String dueDate,
            Long subtotal,
            Double discountPercent,
            Long discountAmount,
            Long taxAmount,
            Long grandTotal,
            Long paidAmount,
            Long balanceAmount,
            String status,
            String paymentStatus,
            String approvalStatus,
            String notes) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("invoiceNo", invoiceNo),
                Map.entry("customerId", customerId),
                Map.entry("customerName", customerName),
                Map.entry("branchId", branchId),
                Map.entry("branchName", branchName),
                Map.entry("invoiceDate", invoiceDate),
                Map.entry("dueDate", dueDate),
                Map.entry("subtotal", subtotal),
                Map.entry("discountPercent", discountPercent),
                Map.entry("discountAmount", discountAmount),
                Map.entry("taxAmount", taxAmount),
                Map.entry("grandTotal", grandTotal),
                Map.entry("paidAmount", paidAmount),
                Map.entry("balanceAmount", balanceAmount),
                Map.entry("status", status),
                Map.entry("paymentStatus", paymentStatus),
                Map.entry("approvalStatus", approvalStatus),
                Map.entry("notes", notes == null ? "" : notes),
                Map.entry("items", invoiceItems(id)));
    }

    private List<Map<String, Object>> invoiceItems(Long invoiceId) {
        return jdbc.sql("""
                SELECT id, description, quantity, unit_price, tax_rate, line_total
                FROM %s
                WHERE invoice_id = :invoiceId
                ORDER BY id
                """.formatted(schoolInvoiceItemTable))
                .param("invoiceId", invoiceId)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "description", rs.getString("description"),
                        "quantity", rs.getLong("quantity"),
                        "unitPrice", rs.getLong("unit_price"),
                        "taxRate", rs.getBigDecimal("tax_rate").doubleValue(),
                        "lineTotal", rs.getLong("line_total")))
                .list();
    }

    private byte[] minimalPdf(String text) {
        String escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("\n", ") Tj T* (");
        String stream = "BT /F1 12 Tf 72 720 Td (" + escaped + ") Tj ET";
        String pdf = "%PDF-1.4\n"
                + "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                + "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n"
                + "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >> endobj\n"
                + "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n"
                + "5 0 obj << /Length " + stream.length() + " >> stream\n"
                + stream + "\nendstream endobj\n"
                + "trailer << /Root 1 0 R >>\n%%EOF\n";
        return pdf.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String invoiceSelect() {
        return """
                SELECT id, order_ref, school, school_id, description, qty, rate, amount,
                       gst_amount, total, status, issued_at, due_at, notes, created_at
                FROM %s
                """.formatted(invoiceTable);
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Long longObject(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private double doubleNum(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(value);
    }

    public record InvoiceRow(
            String id,
            String orderRef,
            String school,
            Long schoolId,
            String description,
            Integer qty,
            Long rate,
            Long amount,
            Long gstAmount,
            Long total,
            String status,
            String issuedAt,
            String dueAt,
            String notes,
            OffsetDateTime createdAt) {}

    public record CustomerRow(
            Long id,
            String code,
            String name,
            String email,
            String phone,
            String gstin,
            String addressLine,
            Long branchId,
            String branchName,
            Boolean active) {}

    public record PaymentRow(
            Long id,
            Long invoiceId,
            String invoiceNo,
            Long branchId,
            String branchName,
            LocalDate paymentDate,
            Long amount,
            String paymentMode,
            String referenceNo,
            String notes,
            String receivedBy) {}
}
