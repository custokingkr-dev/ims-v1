-- School order code (item order workflows design v1, D12): the <SchoolOrderCode> part of an order number such as
-- NB-DPS_GUR-26-11-13-0042. It is <Initials>_<Location>, upper case, unique, and has no hyphen, so an order number splits
-- cleanly on "-". short_code is left alone: it cannot change after creation and already names Drive folders and exports.
--
-- Derivation (SchoolOrderCode.java derives the same suggestion for new schools; keep the two in step):
--   initials  first letter of each word of the name (A-Z only), ignoring OF, THE, AND, FOR and "&", up to 8 letters;
--             with fewer than 2, the first 3 letters of the first such word, padded with X to 2.
--   location  first 3 letters (A-Z) of the city, else of the state, else IN.
--   collision 2..9 is appended to the initials (DPS2_GUR), then 10, 11 and so on, shortening the initials to fit 8.
-- Superadmin may change the code later; a change affects only order numbers minted afterwards.

ALTER TABLE tenant_school.schools ADD COLUMN order_code VARCHAR(16);

CREATE FUNCTION tenant_school.school_order_code_base(p_name text, p_city text, p_state text) RETURNS text
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    word text;
    letters text;
    initials text := '';
    first_word text;
    location text;
BEGIN
    FOREACH word IN ARRAY regexp_split_to_array(upper(btrim(coalesce(p_name, ''))), '\s+') LOOP
        letters := regexp_replace(word, '[^A-Z]', '', 'g');
        CONTINUE WHEN letters = '' OR letters IN ('OF', 'THE', 'AND', 'FOR');
        IF first_word IS NULL THEN first_word := letters; END IF;
        IF length(initials) < 8 THEN initials := initials || left(letters, 1); END IF;
    END LOOP;
    IF length(initials) < 2 THEN initials := left(coalesce(first_word, ''), 3); END IF;
    IF length(initials) < 2 THEN initials := rpad(initials, 2, 'X'); END IF;
    location := left(regexp_replace(upper(coalesce(p_city, '')), '[^A-Z]', '', 'g'), 3);
    IF length(location) < 2 THEN location := left(regexp_replace(upper(coalesce(p_state, '')), '[^A-Z]', '', 'g'), 3); END IF;
    IF length(location) < 2 THEN location := 'IN'; END IF;
    RETURN initials || '_' || location;
END $$;

-- The first free code for a base: the base itself, else 2, 3 ... appended to its initials. Sees the rows the caller may
-- see, so a caller that does not bypass row-level security can still collide on the unique index, which then refuses.
CREATE FUNCTION tenant_school.unique_school_order_code(p_base text, p_school_id bigint) RETURNS text
LANGUAGE plpgsql STABLE AS $$
DECLARE
    initials text := split_part(p_base, '_', 1);
    location text := split_part(p_base, '_', 2);
    candidate text := p_base;
    suffix integer := 1;
BEGIN
    WHILE EXISTS (SELECT 1 FROM tenant_school.schools
                  WHERE order_code = candidate AND id IS DISTINCT FROM p_school_id) LOOP
        suffix := suffix + 1;
        candidate := left(initials, 8 - length(suffix::text)) || suffix::text || '_' || location;
    END LOOP;
    RETURN candidate;
END $$;

-- Backfill every school, in id order so the oldest school keeps the plain code. FORCE ROW LEVEL SECURITY applies to the
-- owner too, so the backfill bypasses it for this transaction only.
DO $$
DECLARE
    school record;
BEGIN
    PERFORM set_config('app.bypass_rls', 'on', true);
    FOR school IN SELECT id, name, city, state FROM tenant_school.schools WHERE order_code IS NULL ORDER BY id LOOP
        UPDATE tenant_school.schools
        SET order_code = tenant_school.unique_school_order_code(
                tenant_school.school_order_code_base(school.name, school.city, school.state), school.id)
        WHERE id = school.id;
    END LOOP;
END $$;

-- A school inserted without a code (a script, a fixture) gets the derived one; a given code is stored in upper case. The
-- application always supplies the code it derived or was given, so this is the fallback, not the main path.
CREATE FUNCTION tenant_school.default_school_order_code() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.order_code IS NULL OR btrim(NEW.order_code) = '' THEN
        NEW.order_code := tenant_school.unique_school_order_code(
                tenant_school.school_order_code_base(NEW.name, NEW.city, NEW.state), NEW.id);
    ELSE
        NEW.order_code := upper(btrim(NEW.order_code));
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_schools_default_order_code
    BEFORE INSERT ON tenant_school.schools
    FOR EACH ROW EXECUTE FUNCTION tenant_school.default_school_order_code();

ALTER TABLE tenant_school.schools
    ADD CONSTRAINT ck_schools_order_code CHECK (order_code ~ '^[A-Z0-9]{2,8}_[A-Z0-9]{2,6}$'),
    ALTER COLUMN order_code SET NOT NULL;

CREATE UNIQUE INDEX uk_schools_order_code ON tenant_school.schools (order_code);
