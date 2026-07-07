package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Fee fact read models projected from tenant_school (school-core) outbox events
 * ({@code fee-assignment.upserted.v1}, {@code payment.recorded.v1}), per Reporting
 * Decoupling SP2. Rows are upserted idempotently by {@code id} so replaying the same or a
 * later event never duplicates state; last-writer-wins is acceptable for fact projections.
 */
@Repository
public class FeeFactReadRepository {

    private final JdbcClient jdbc;

    public FeeFactReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsertFeeAssignment(String id, Long studentId, Long schoolId, String academicYearId,
                                     Long netPayable, Long paidAmount, Long dueAmount, String status,
                                     OffsetDateTime assignedAt) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_fee_assignment (
                            id, student_id, school_id, academic_year_id, net_payable, paid_amount,
                            due_amount, status, assigned_at, updated_at
                        ) VALUES (
                            :id, :studentId, :schoolId, :academicYearId, :netPayable, :paidAmount,
                            :dueAmount, :status, :assignedAt, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            student_id = EXCLUDED.student_id,
                            school_id = EXCLUDED.school_id,
                            academic_year_id = EXCLUDED.academic_year_id,
                            net_payable = EXCLUDED.net_payable,
                            paid_amount = EXCLUDED.paid_amount,
                            due_amount = EXCLUDED.due_amount,
                            status = EXCLUDED.status,
                            assigned_at = EXCLUDED.assigned_at,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .param("academicYearId", academicYearId)
                .param("netPayable", netPayable)
                .param("paidAmount", paidAmount)
                .param("dueAmount", dueAmount)
                .param("status", status)
                .param("assignedAt", assignedAt)
                .update();
    }

    @Transactional
    public void upsertPayment(String id, String assignmentId, Long schoolId, Long studentId,
                               Long amount, OffsetDateTime paidAt) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_payment (
                            id, assignment_id, school_id, student_id, amount, paid_at, updated_at
                        ) VALUES (
                            :id, :assignmentId, :schoolId, :studentId, :amount, :paidAt, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            assignment_id = EXCLUDED.assignment_id,
                            school_id = EXCLUDED.school_id,
                            student_id = EXCLUDED.student_id,
                            amount = EXCLUDED.amount,
                            paid_at = EXCLUDED.paid_at,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("assignmentId", assignmentId)
                .param("schoolId", schoolId)
                .param("studentId", studentId)
                .param("amount", amount)
                .param("paidAt", paidAt)
                .update();
    }
}
