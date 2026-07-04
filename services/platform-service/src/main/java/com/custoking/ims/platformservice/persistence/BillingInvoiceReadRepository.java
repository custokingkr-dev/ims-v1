package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Read model projected from {@code billing.invoice-upserted.v1} events (Phase 3
 * reporting-outbox spike). Rows are upserted idempotently by {@code id} so replaying the
 * same or a later event for the same invoice never duplicates state.
 *
 * <p>Task 4b widened this from a 4-column metrics-only projection into a FULL invoice
 * projection (all 15 billing-service invoice table columns) so the reporting
 * {@code invoices()} LIST endpoint can also read from here, eliminating the last
 * cross-schema read of billing tables from platform-service.
 */
@Repository
public class BillingInvoiceReadRepository {

    private final JdbcClient jdbc;

    public BillingInvoiceReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsert(String id, Long schoolId, String status, BigDecimal total, OffsetDateTime occurredAt) {
        upsert(id, null, null, schoolId, null, null, null, null, null, total, status,
                null, null, null, null, occurredAt);
    }

    @Transactional
    public void upsert(String id, String orderRef, String school, Long schoolId, String description,
                        Integer qty, Long rate, Long amount, Long gstAmount, BigDecimal total, String status,
                        String issuedAt, String dueAt, String notes, OffsetDateTime createdAt,
                        OffsetDateTime occurredAt) {
        jdbc.sql("""
                        INSERT INTO reporting.billing_invoice_read (
                            id, order_ref, school, school_id, description, qty, rate, amount,
                            gst_amount, total, status, issued_at, due_at, notes, created_at,
                            occurred_at, updated_at
                        ) VALUES (
                            :id, :orderRef, :school, :schoolId, :description, :qty, :rate, :amount,
                            :gstAmount, :total, :status, :issuedAt, :dueAt, :notes, :createdAt,
                            :occurredAt, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            order_ref = EXCLUDED.order_ref,
                            school = EXCLUDED.school,
                            school_id = EXCLUDED.school_id,
                            description = EXCLUDED.description,
                            qty = EXCLUDED.qty,
                            rate = EXCLUDED.rate,
                            amount = EXCLUDED.amount,
                            gst_amount = EXCLUDED.gst_amount,
                            total = EXCLUDED.total,
                            status = EXCLUDED.status,
                            issued_at = EXCLUDED.issued_at,
                            due_at = EXCLUDED.due_at,
                            notes = EXCLUDED.notes,
                            created_at = EXCLUDED.created_at,
                            occurred_at = EXCLUDED.occurred_at,
                            updated_at = now()
                        """)
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
                .param("status", status)
                .param("issuedAt", issuedAt)
                .param("dueAt", dueAt)
                .param("notes", notes)
                .param("createdAt", createdAt)
                .param("occurredAt", occurredAt)
                .update();
    }
}
