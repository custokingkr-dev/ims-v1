-- Backfills tenant_school.outbox_events with student-review-item.upserted.v1 events for every
-- existing student.student_review_items row, per Reporting Decoupling SP7 (student-review).
-- school_id already lives directly on student_review_items (see student/V1__student_schema.sql),
-- so no join to student_review_campaigns is required to resolve it.
--
-- This migration lives in the student schema's own Flyway history (not tenant_school's) because
-- SchoolCoreFlywayConfig migrates tenant_school BEFORE student (tenantSchoolFlyway ->
-- @DependsOn studentFlyway): by the time this migration runs against the full app,
-- tenant_school.outbox_events (created in tenant_school/V7) already exists. Guarded with
-- to_regclass (mirrors student/V5__student_outbox_backfill.sql) so isolated tests that migrate
-- only the student schema (no tenant_school schema present) skip this backfill instead of
-- failing on a missing relation.
DO $$
BEGIN
    IF to_regclass('tenant_school.outbox_events') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'StudentReviewItemUpserted:'||i.id, 'student-review-item.upserted.v1', 'StudentReviewItem', i.id, i.school_id,
               jsonb_build_object('id', i.id, 'schoolId', i.school_id, 'campaignId', i.campaign_id, 'status', i.status)
        FROM student.student_review_items i;
    END IF;
END $$;
