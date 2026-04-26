-- HUMAN REVIEW REQUIRED before applying to production
-- =============================================================
-- V5 – Audit log table
-- Records every significant state-changing action performed
-- by application users for compliance and debugging.
-- =============================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL    NOT NULL,
    action      VARCHAR(255) NOT NULL,
    user_id     BIGINT,
    school_id   BIGINT,
    entity_type VARCHAR(255),
    timestamp   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);
