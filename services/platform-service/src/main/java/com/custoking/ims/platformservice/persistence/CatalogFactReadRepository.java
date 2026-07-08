package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.security.ProjectorRls;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Read model projected from {@code catalog-order.upserted.v1} events (Reporting Decoupling
 * SP4). Rows are upserted idempotently by {@code id} so replaying the same or a later event
 * for the same catalog order never duplicates state.
 */
@Repository
public class CatalogFactReadRepository {

    private final JdbcClient jdbc;

    public CatalogFactReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsert(String id, Long schoolId, String category, String status, Long totalAmount,
                        String superadminApprovalStatus, OffsetDateTime vendorPaidAt, OffsetDateTime createdAt,
                        LocalDate requiredByDate, String designStatus, String notes) {
        ProjectorRls.allow(jdbc);
        jdbc.sql("""
                        INSERT INTO reporting.fact_catalog_order (
                            id, school_id, category, status, total_amount,
                            superadmin_approval_status, vendor_paid_at, created_at,
                            required_by_date, design_status, notes, updated_at
                        ) VALUES (
                            :id, :schoolId, :category, :status, :totalAmount,
                            :superadminApprovalStatus, :vendorPaidAt, :createdAt,
                            :requiredByDate, :designStatus, :notes, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            school_id = EXCLUDED.school_id,
                            category = EXCLUDED.category,
                            status = EXCLUDED.status,
                            total_amount = EXCLUDED.total_amount,
                            superadmin_approval_status = EXCLUDED.superadmin_approval_status,
                            vendor_paid_at = EXCLUDED.vendor_paid_at,
                            created_at = EXCLUDED.created_at,
                            required_by_date = EXCLUDED.required_by_date,
                            design_status = EXCLUDED.design_status,
                            notes = EXCLUDED.notes,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("category", category)
                .param("status", status)
                .param("totalAmount", totalAmount)
                .param("superadminApprovalStatus", superadminApprovalStatus)
                .param("vendorPaidAt", vendorPaidAt)
                .param("createdAt", createdAt)
                .param("requiredByDate", requiredByDate)
                .param("designStatus", designStatus)
                .param("notes", notes)
                .update();
    }
}
