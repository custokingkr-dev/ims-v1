package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.security.ProjectorRls;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model projected from {@code student-review-item.upserted.v1} events (school-core-service
 * outbox), per Reporting Decoupling SP7 (student-review). Rows are upserted idempotently by
 * {@code id} so replaying the same or a later event for the same review item never duplicates
 * state.
 */
@Repository
public class StudentReviewFactReadRepository {

    private final JdbcClient jdbc;

    public StudentReviewFactReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsert(String id, Long schoolId, String campaignId, String status) {
        ProjectorRls.allow(jdbc);
        jdbc.sql("""
                        INSERT INTO reporting.fact_student_review_item (
                            id, school_id, campaign_id, status, updated_at
                        ) VALUES (
                            :id, :schoolId, :campaignId, :status, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            school_id = EXCLUDED.school_id,
                            campaign_id = EXCLUDED.campaign_id,
                            status = EXCLUDED.status,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("campaignId", campaignId)
                .param("status", status)
                .update();
    }

    @Transactional
    public void updateCampaignStatus(String campaignId, String status) {
        ProjectorRls.allow(jdbc);
        jdbc.sql("""
                        UPDATE reporting.fact_student_review_item
                        SET campaign_status = :status, updated_at = now()
                        WHERE campaign_id = :campaignId
                        """)
                .param("status", status)
                .param("campaignId", campaignId)
                .update();
    }
}
