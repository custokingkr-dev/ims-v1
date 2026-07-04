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
 */
@Repository
public class BillingInvoiceReadRepository {

    private final JdbcClient jdbc;

    public BillingInvoiceReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsert(String id, Long schoolId, String status, BigDecimal total, OffsetDateTime occurredAt) {
        jdbc.sql("""
                        INSERT INTO reporting.billing_invoice_read (
                            id, school_id, status, total, occurred_at, updated_at
                        ) VALUES (
                            :id, :schoolId, :status, :total, :occurredAt, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            school_id = EXCLUDED.school_id,
                            status = EXCLUDED.status,
                            total = EXCLUDED.total,
                            occurred_at = EXCLUDED.occurred_at,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("status", status)
                .param("total", total)
                .param("occurredAt", occurredAt)
                .update();
    }
}
