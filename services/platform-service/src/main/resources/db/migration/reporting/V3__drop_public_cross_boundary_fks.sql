ALTER TABLE reporting.command_center_actions
    DROP CONSTRAINT IF EXISTS command_center_actions_school_id_fkey,
    DROP CONSTRAINT IF EXISTS command_center_actions_accepted_by_fkey,
    DROP CONSTRAINT IF EXISTS command_center_actions_dismissed_by_fkey;

ALTER TABLE reporting.command_center_feed
    DROP CONSTRAINT IF EXISTS command_center_feed_school_id_fkey,
    DROP CONSTRAINT IF EXISTS command_center_feed_actor_user_id_fkey;

ALTER TABLE reporting.academic_events
    DROP CONSTRAINT IF EXISTS academic_events_school_id_fkey,
    DROP CONSTRAINT IF EXISTS academic_events_academic_year_id_fkey,
    DROP CONSTRAINT IF EXISTS academic_events_created_by_fkey;

ALTER TABLE reporting.event_student_contributions
    DROP CONSTRAINT IF EXISTS event_student_contributions_school_id_fkey,
    DROP CONSTRAINT IF EXISTS event_student_contributions_student_id_fkey;
