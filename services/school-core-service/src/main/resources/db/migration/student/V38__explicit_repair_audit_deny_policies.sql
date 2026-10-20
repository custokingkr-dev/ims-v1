-- These owner-operated audit tables intentionally have no runtime grants (V26).
-- Replace implicit default-deny RLS with explicit default-deny policies so the
-- startup guard can distinguish intentional isolation from an absent policy.
-- Preserve FORCE RLS, ownership, function permissions, and all existing grants.
CREATE POLICY repair_audit_deny_all
    ON student.guardian_safe_create_repair_runs
    FOR ALL TO PUBLIC USING (false) WITH CHECK (false);

CREATE POLICY repair_audit_deny_all
    ON student.guardian_safe_create_repair_actions
    FOR ALL TO PUBLIC USING (false) WITH CHECK (false);
