package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Read model projected from {@code firefighting-request.upserted.v1} events (SP6 reporting-outbox
 * decoupling). Rows are upserted idempotently by {@code code} (the firefighting_requests primary
 * key) so replaying the same or a later event for the same request never duplicates state.
 */
@Repository
public class FirefightingFactReadRepository {

    private final JdbcClient jdbc;

    public FirefightingFactReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsert(String code, String title, String category, String urgency, String status,
                        Long estimatedBudget, Long schoolId, String winnerVendor, Long winnerAmount,
                        OffsetDateTime createdAt, OffsetDateTime bursarApprovedAt, OffsetDateTime principalApprovedAt,
                        String rejectedReason, OffsetDateTime vendorPaidAt, Long vendorPaidBy,
                        String vendorPaymentNotes, OffsetDateTime occurredAt) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_firefighting_request (
                            code, title, category, urgency, status, estimated_budget, school_id,
                            winner_vendor, winner_amount, created_at, bursar_approved_at,
                            principal_approved_at, rejected_reason, vendor_paid_at, vendor_paid_by,
                            vendor_payment_notes, occurred_at, updated_at
                        ) VALUES (
                            :code, :title, :category, :urgency, :status, :estimatedBudget, :schoolId,
                            :winnerVendor, :winnerAmount, :createdAt, :bursarApprovedAt,
                            :principalApprovedAt, :rejectedReason, :vendorPaidAt, :vendorPaidBy,
                            :vendorPaymentNotes, :occurredAt, now()
                        )
                        ON CONFLICT (code) DO UPDATE SET
                            title = EXCLUDED.title,
                            category = EXCLUDED.category,
                            urgency = EXCLUDED.urgency,
                            status = EXCLUDED.status,
                            estimated_budget = EXCLUDED.estimated_budget,
                            school_id = EXCLUDED.school_id,
                            winner_vendor = EXCLUDED.winner_vendor,
                            winner_amount = EXCLUDED.winner_amount,
                            created_at = EXCLUDED.created_at,
                            bursar_approved_at = EXCLUDED.bursar_approved_at,
                            principal_approved_at = EXCLUDED.principal_approved_at,
                            rejected_reason = EXCLUDED.rejected_reason,
                            vendor_paid_at = EXCLUDED.vendor_paid_at,
                            vendor_paid_by = EXCLUDED.vendor_paid_by,
                            vendor_payment_notes = EXCLUDED.vendor_payment_notes,
                            occurred_at = EXCLUDED.occurred_at,
                            updated_at = now()
                        """)
                .param("code", code)
                .param("title", title)
                .param("category", category)
                .param("urgency", urgency)
                .param("status", status)
                .param("estimatedBudget", estimatedBudget)
                .param("schoolId", schoolId)
                .param("winnerVendor", winnerVendor)
                .param("winnerAmount", winnerAmount)
                .param("createdAt", createdAt)
                .param("bursarApprovedAt", bursarApprovedAt)
                .param("principalApprovedAt", principalApprovedAt)
                .param("rejectedReason", rejectedReason)
                .param("vendorPaidAt", vendorPaidAt)
                .param("vendorPaidBy", vendorPaidBy)
                .param("vendorPaymentNotes", vendorPaymentNotes)
                .param("occurredAt", occurredAt)
                .update();
    }
}
