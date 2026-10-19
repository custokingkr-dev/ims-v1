-- Operators scoped to a school AND a catalogue item (notebook order tracker, spec v4).
--
-- A school-wide operator is an OPERATIONS assignment with a school_id and no catalogue item; that is what exists
-- today and its meaning does not change. An item-specific operator is the same assignment with catalog_category set
-- (for example NOTEBOOKS). Application code that means "school-wide" must therefore filter catalog_category IS NULL.
--
-- catalog_category carries a catalogue category code. The catalogue lives in another service, so there is no
-- foreign key; the code is checked for shape here and offered from the catalogue by the superadmin screen.
ALTER TABLE identity.user_role_assignments ADD COLUMN catalog_category VARCHAR(40);

ALTER TABLE identity.user_role_assignments ADD CONSTRAINT ck_ura_catalog_category_shape
    CHECK (catalog_category IS NULL OR catalog_category ~ '^[A-Z][A-Z0-9_]{1,39}$');

-- A catalogue scope narrows a school assignment, so it needs a school and cannot be a zone assignment.
ALTER TABLE identity.user_role_assignments ADD CONSTRAINT ck_ura_catalog_category_needs_school
    CHECK (catalog_category IS NULL OR (school_id IS NOT NULL AND zone_id IS NULL));

-- One active assignment per user, role, school and catalogue item. The index covers item-specific rows only:
-- uniqueness of the existing school-wide and zone rows is enforced in application code and is deliberately not
-- changed here, because deployed data was never constrained and this migration must not fail on it.
CREATE UNIQUE INDEX uk_ura_active_catalog_scope
    ON identity.user_role_assignments (user_id, role_id, school_id, catalog_category)
    WHERE catalog_category IS NOT NULL AND active AND revoked_at IS NULL;

-- Directory lookups: "which operators cover this school and item".
CREATE INDEX idx_ura_catalog_school_active
    ON identity.user_role_assignments (school_id, catalog_category)
    WHERE catalog_category IS NOT NULL AND active AND revoked_at IS NULL;
