-- V104 – Drop NOT NULL constraints on audit_log columns that the entity omits
-- for certain event types (e.g. entity_id is null for login events).
-- These columns were added to the live DB with NOT NULL before the entity
-- mapped them; V101's ADD COLUMN IF NOT EXISTS was a no-op so the constraints
-- remained. Audit events must never block the main request.

ALTER TABLE audit_log ALTER COLUMN entity_id   DROP NOT NULL;
ALTER TABLE audit_log ALTER COLUMN entity_type DROP NOT NULL;
