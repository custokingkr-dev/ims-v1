package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.persistence.ReportingReadRepository.InvoiceRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Reporting-owned projections for invoice lists and metrics. */
@Repository
public class InvoiceReportingQueries {
    private final JdbcClient jdbc;

    public InvoiceReportingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<InvoiceRow> invoices(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, order_ref, school, school_id, description, qty, rate, amount,
                       gst_amount, total, status, issued_at, due_at, notes, created_at
                FROM reporting.billing_invoice_read
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(InvoiceRow.class).list();
    }

    public Map<String, Object> invoiceStats(Long schoolId) {
        if (schoolId == null) {
            return Map.of(
                    "sentThisMonth", count("SELECT count(*) FROM reporting.billing_invoice_read"),
                    "paid", count("SELECT count(*) FROM reporting.billing_invoice_read WHERE LOWER(status) = 'paid'"),
                    "pending", count("SELECT count(*) FROM reporting.billing_invoice_read WHERE LOWER(status) = 'awaiting payment'"),
                    "totalInvoiced", countAmount("SELECT COALESCE(SUM(total), 0) FROM reporting.billing_invoice_read"));
        }
        return Map.of(
                "sentThisMonth", count("SELECT count(*) FROM reporting.billing_invoice_read WHERE school_id = :schoolId", schoolId),
                "paid", count("SELECT count(*) FROM reporting.billing_invoice_read WHERE school_id = :schoolId AND LOWER(status) = 'paid'", schoolId),
                "pending", count("SELECT count(*) FROM reporting.billing_invoice_read WHERE school_id = :schoolId AND LOWER(status) = 'awaiting payment'", schoolId),
                "totalInvoiced", countAmount("SELECT COALESCE(SUM(total), 0) FROM reporting.billing_invoice_read WHERE school_id = :schoolId", schoolId));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long count(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

    private long countAmount(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long countAmount(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

}
