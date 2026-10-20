package com.custoking.ims.billingservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final BillingInvoiceStatistics statistics;
    @Value("${billing.legacy-gst-percent:12.00}")
    private java.math.BigDecimal legacyGstPercent = new java.math.BigDecimal("12.00");

    public BillingInvoiceRepository(
            JdbcClient jdbc, String schema) {
        this(jdbc, schema, "UTC");
    }

    @Autowired
    public BillingInvoiceRepository(JdbcClient jdbc,
            @Value("${billing.db.schema:billing}") String schema,
            @Value("${billing.reporting-time-zone:UTC}") String reportingTimeZone) {
        this.jdbc = jdbc;
        this.invoiceTable = qualifiedTable(schema, "superadmin_invoices");
        this.sequenceTable = qualifiedTable(schema, "superadmin_order_seq");
        this.customerTable = qualifiedTable(schema, "billing_customers");
        this.schoolInvoiceTable = qualifiedTable(schema, "billing_invoices");
        this.schoolInvoiceItemTable = qualifiedTable(schema, "billing_invoice_items");
        this.paymentTable = qualifiedTable(schema, "billing_payments");
        this.statistics = new BillingInvoiceStatistics(jdbc, invoiceTable,
                java.time.Clock.systemUTC(), java.time.ZoneId.of(reportingTimeZone));
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
        return statistics.current();
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
        int qty = Math.toIntExact(request.get("qty") == null ? 1 : exactLong(request.get("qty")));
        long rate = request.get("rate") == null ? 0 : exactLong(request.get("rate"));
        long amount = request.get("amount") == null ? product(qty, rate) : nonnegative(exactLong(request.get("amount")), "amount");
        if (qty <= 0) throw new IllegalArgumentException("Quantity must be positive");
        nonnegative(rate, "rate");
        long gstAmount = percentage(amount, legacyGstPercent);
        long total = Math.addExact(amount, gstAmount);
        String issuedAt = LocalDate.now().toString();
        String dueAt = LocalDate.now().plusDays(14).toString();

        jdbc.sql("""
                        INSERT INTO %s
                            (id, order_ref, school, school_id, description, qty, rate, amount,
                             gst_amount, total, status, issued_at, due_at, notes, created_at, tax_percent, currency, monetary_unit)
                        VALUES
                            (:id, :orderRef, :school, :schoolId, :description, :qty, :rate, :amount,
                             :gstAmount, :total, :status, :issuedAt, :dueAt, :notes, now(), :taxPercent, 'INR', 'WHOLE_RUPEE')
                        """.formatted(invoiceTable))
                .param("id", id)
                .param("orderRef", orderRef)
                .param("school", school)
                .param("schoolId", schoolId)
                .param("description", description)
                .param("qty", qty)
                .param("rate", rate)
                .param("amount", amount)
                .param("taxPercent", legacyGstPercent)
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
                ? Math.toIntExact(exactLong(request.get("qty"))) : existing.qty();
        long rate = request.containsKey("rate")
                ? exactLong(request.get("rate")) : existing.rate();
        String school = request.containsKey("school")
                ? str(request.get("school"), existing.school()) : existing.school();
        String status = request.containsKey("status")
                ? str(request.get("status"), existing.status()) : existing.status();
        String notes = request.containsKey("notes")
                ? trimToNull(str(request.get("notes"), "")) : existing.notes();
        long amount = product(qty, rate);
        java.math.BigDecimal taxPercent = jdbc.sql("SELECT tax_percent FROM " + invoiceTable + " WHERE id = :id")
                .param("id", id).query(java.math.BigDecimal.class).single();
        long gstAmount = percentage(amount, taxPercent);
        long total = Math.addExact(amount, gstAmount);

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
                        rs.getBigDecimal("discount_percent"),
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
                        rs.getBigDecimal("discount_percent"),
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
            long quantity = item.get("quantity") == null ? 1 : exactLong(item.get("quantity"));
            long unitPrice = item.get("unitPrice") == null ? 0 : exactLong(item.get("unitPrice"));
            java.math.BigDecimal taxRate = decimalPercent(item.get("taxRate"));
            long lineSubtotal = product(quantity, unitPrice);
            subtotal = Math.addExact(subtotal, lineSubtotal);
            taxAmount = Math.addExact(taxAmount, percentage(lineSubtotal, taxRate));
        }
        java.math.BigDecimal discountPercent = decimalPercent(request.get("discountPercent"));
        long discountAmount = percentage(subtotal, discountPercent);
        long grandTotal = Math.addExact(Math.subtractExact(subtotal, discountAmount), taxAmount);
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
            long quantity = item.get("quantity") == null ? 1 : exactLong(item.get("quantity"));
            long unitPrice = item.get("unitPrice") == null ? 0 : exactLong(item.get("unitPrice"));
            java.math.BigDecimal taxRate = decimalPercent(item.get("taxRate"));
            long base = product(quantity, unitPrice);
            long lineTotal = Math.addExact(base, percentage(base, taxRate));
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
                       p.amount, p.payment_mode, p.reference_no, p.notes, p.received_by, p.received_by_user_id
                FROM %s p
                JOIN %s i ON i.id = p.invoice_id
                ORDER BY p.created_at DESC, p.id DESC
                """.formatted(paymentTable, schoolInvoiceTable))
                .query(PaymentRow.class)
                .list();
    }

    public PaymentRow createBillingPayment(com.custoking.ims.billingservice.api.dto.CreateBillingPaymentRequest request) {
        com.custoking.ims.billingservice.security.TenantScope.requireSuperAdmin();
        var actor = com.custoking.ims.billingservice.security.TenantContext.get();
        if (actor.userId() == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Authenticated payment actor required");
        if (request.invoiceId() == null || request.invoiceId() <= 0 || request.amount() == null || request.amount() <= 0
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9._:-]{8,128}"))
            throw new IllegalArgumentException("Positive invoice/amount and a valid idempotency key are required");
        LocalDate paymentDate = request.paymentDate() == null ? LocalDate.now() : request.paymentDate();
        if (paymentDate.isAfter(LocalDate.now())) throw new IllegalArgumentException("Payment date cannot be in the future");
        if (request.paymentMode() == null || !request.paymentMode().matches("CASH|UPI|BANK_TRANSFER|CHEQUE|CARD|OTHER"))
            throw new IllegalArgumentException("Invalid payment mode");
        // Serialize both the replay check and balance mutation against this invoice.
        Map<String, Object> locked = jdbc.sql("SELECT branch_id, branch_name, grand_total, paid_amount, status FROM " + schoolInvoiceTable + " WHERE id = :id FOR UPDATE")
                .param("id", request.invoiceId()).query((rs, n) -> { var row = new LinkedHashMap<String,Object>(); row.put("branch_id", rs.getLong("branch_id")); row.put("branch_name", rs.getString("branch_name")); row.put("grand_total", rs.getLong("grand_total")); row.put("paid_amount", rs.getLong("paid_amount")); row.put("status", rs.getString("status")); return row; }).optional().orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        String fingerprint = paymentFingerprint(request, paymentDate);
        var replay = jdbc.sql("SELECT id, request_fingerprint FROM " + paymentTable + " WHERE invoice_id = :invoiceId AND idempotency_key = :key")
                .param("invoiceId", request.invoiceId()).param("key", request.idempotencyKey()).query((rs, n) -> Map.<String,Object>of("id", rs.getLong("id"), "request_fingerprint", rs.getString("request_fingerprint"))).optional();
        if (replay.isPresent()) {
            if (!fingerprint.equals(replay.get().get("request_fingerprint")))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Idempotency key was used for a different payment");
            return billingPayment(((Number) replay.get().get("id")).longValue());
        }
        if (java.util.Set.of("CANCELLED", "VOID", "REJECTED").contains(String.valueOf(locked.get("status")).toUpperCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("Payments cannot be applied to a cancelled invoice");
        long balance = Math.subtractExact(((Number) locked.get("grand_total")).longValue(), ((Number) locked.get("paid_amount")).longValue());
        if (request.amount() > balance) throw new IllegalArgumentException("Payment exceeds the outstanding invoice balance");
        Long id = jdbc.sql("""
                INSERT INTO %s (invoice_id, branch_id, branch_name, payment_date, amount,
                                payment_mode, reference_no, notes, received_by, received_by_user_id,
                                idempotency_key, request_fingerprint)
                VALUES (:invoiceId, :branchId, :branchName, :paymentDate, :amount,
                        :paymentMode, :referenceNo, :notes, :receivedBy, :actorId, :key, :fingerprint)
                RETURNING id
                """.formatted(paymentTable))
                .param("invoiceId", request.invoiceId()).param("branchId", locked.get("branch_id"))
                .param("branchName", locked.get("branch_name")).param("paymentDate", paymentDate)
                .param("amount", request.amount()).param("paymentMode", request.paymentMode())
                .param("referenceNo", trimToNull(request.referenceNo())).param("notes", trimToNull(request.notes()))
                .param("receivedBy", actor.email() == null ? "User " + actor.userId() : actor.email())
                .param("actorId", actor.userId()).param("key", request.idempotencyKey()).param("fingerprint", fingerprint)
                .query(Long.class).single();
        refreshSchoolInvoicePaymentStatus(request.invoiceId());
        return billingPayment(id);
    }

    private String paymentFingerprint(com.custoking.ims.billingservice.api.dto.CreateBillingPaymentRequest request, LocalDate date) {
        // Length-prefix the fields so separators in user text cannot produce ambiguous fingerprints.
        StringBuilder canonical = new StringBuilder();
        Object[] fields = {request.invoiceId(), request.amount(), request.paymentDate(), request.paymentMode(),
                trimToNull(request.referenceNo()), trimToNull(request.notes())};
        for (Object field : fields) {
            if (field == null) canonical.append("-1:");
            else { String value = String.valueOf(field); canonical.append(value.length()).append(':').append(value); }
        }
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
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
        return "INV-" + java.time.Year.now().getValue() + "-0" + next;
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
                       p.amount, p.payment_mode, p.reference_no, p.notes, p.received_by, p.received_by_user_id
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
            java.math.BigDecimal discountPercent,
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
                        "taxRate", rs.getBigDecimal("tax_rate"),
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

    private static long exactLong(Object value) {
        try { return new java.math.BigDecimal(String.valueOf(value)).longValueExact(); }
        catch (ArithmeticException | NumberFormatException ex) { throw new IllegalArgumentException("Money and quantities must be whole integers within the ledger range"); }
    }
    private static long nonnegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be nonnegative");
        return value;
    }
    private static long product(long quantity, long price) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        nonnegative(price, "Unit price");
        try { return Math.multiplyExact(quantity, price); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Invoice exceeds the supported monetary range"); }
    }
    private static java.math.BigDecimal decimalPercent(Object value) {
        try {
            var rate = value == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(String.valueOf(value));
            if (rate.signum() < 0 || rate.compareTo(new java.math.BigDecimal("100")) > 0 || rate.scale() > 2)
                throw new IllegalArgumentException("Percentage must be between 0 and 100 with at most two decimals");
            return rate;
        } catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid percentage"); }
    }
    private static long percentage(long amount, java.math.BigDecimal rate) {
        decimalPercent(rate);
        try { return java.math.BigDecimal.valueOf(amount).multiply(rate).divide(new java.math.BigDecimal("100"), 0, java.math.RoundingMode.HALF_UP).longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Invoice exceeds the supported monetary range"); }
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
            String receivedBy,
            Long receivedByUserId) {
        public PaymentRow(Long id, Long invoiceId, String invoiceNo, Long branchId, String branchName,
                          LocalDate paymentDate, Long amount, String paymentMode, String referenceNo,
                          String notes, String receivedBy) {
            this(id, invoiceId, invoiceNo, branchId, branchName, paymentDate, amount, paymentMode, referenceNo, notes, receivedBy, null);
        }
    }
}
