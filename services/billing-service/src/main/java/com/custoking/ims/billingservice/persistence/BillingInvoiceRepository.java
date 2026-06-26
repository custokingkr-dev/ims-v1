package com.custoking.ims.billingservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class BillingInvoiceRepository {

    private static final String SEQUENCE_ID = "SINGLETON";

    private final JdbcClient jdbc;
    private final String invoiceTable;
    private final String sequenceTable;

    public BillingInvoiceRepository(
            JdbcClient jdbc,
            @Value("${billing.db.schema:public}") String schema) {
        this.jdbc = jdbc;
        this.invoiceTable = qualifiedTable(schema, "superadmin_invoices");
        this.sequenceTable = qualifiedTable(schema, "superadmin_order_seq");
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
}
