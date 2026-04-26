-- =============================================================
-- V100 – Missing FK indexes identified in Phase 4 index audit
-- =============================================================

-- auth_sessions: user_id FK used by deleteByUser_Id (SchoolService admin reset)
CREATE INDEX IF NOT EXISTS idx_auth_sessions_user
    ON auth_sessions (user_id);

-- fee_items: band_id FK used when loading items per fee band
CREATE INDEX IF NOT EXISTS idx_fee_items_band
    ON fee_items (band_id);

-- staff_members: school_id FK used when listing staff per school
CREATE INDEX IF NOT EXISTS idx_staff_members_school
    ON staff_members (school_id);
