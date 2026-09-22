CREATE TABLE catalog.product_categories (
    code VARCHAR(40) PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    emoji VARCHAR(16) NOT NULL DEFAULT '',
    description VARCHAR(200) NOT NULL DEFAULT '',
    order_type VARCHAR(20) NOT NULL CHECK (order_type IN ('Recurring', 'One-time', 'Service')),
    form_enabled BOOLEAN NOT NULL DEFAULT false,
    sort_order INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by BIGINT
);

CREATE TABLE catalog.product_option_groups (
    id BIGSERIAL PRIMARY KEY,
    category_code VARCHAR(40) NOT NULL REFERENCES catalog.product_categories(code),
    code VARCHAR(40) NOT NULL,
    label VARCHAR(80) NOT NULL,
    level INT NOT NULL CHECK (level > 0),
    selection_type VARCHAR(10) NOT NULL DEFAULT 'SINGLE' CHECK (selection_type = 'SINGLE'),
    required BOOLEAN NOT NULL DEFAULT true,
    scope VARCHAR(10) NOT NULL CHECK (scope IN ('ORDER', 'LINE')),
    active BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (category_code, code)
);

CREATE TABLE catalog.product_options (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES catalog.product_option_groups(id),
    code VARCHAR(60) NOT NULL,
    label VARCHAR(120) NOT NULL,
    spec_text VARCHAR(120) NOT NULL DEFAULT '',
    width_mm INT CHECK (width_mm > 0),
    height_mm INT CHECK (height_mm > 0),
    spec_status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED' CHECK (spec_status IN ('CONFIRMED', 'PENDING_SPEC')),
    sort_order INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (group_id, code),
    CHECK ((width_mm IS NULL) = (height_mm IS NULL))
);

CREATE TABLE catalog.product_option_dependencies (
    id BIGSERIAL PRIMARY KEY,
    parent_option_id BIGINT NOT NULL REFERENCES catalog.product_options(id),
    child_option_id BIGINT NOT NULL REFERENCES catalog.product_options(id),
    allowed BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (parent_option_id, child_option_id),
    CHECK (parent_option_id <> child_option_id)
);

CREATE TABLE catalog.product_form_rules (
    id BIGSERIAL PRIMARY KEY,
    category_code VARCHAR(40) NOT NULL REFERENCES catalog.product_categories(code),
    rule_type VARCHAR(40) NOT NULL CHECK (rule_type IN ('REQUIRE_QUANTITY_TOTAL', 'ROUND_TO_MULTIPLE', 'MIN_VALUE', 'MAX_VALUE', 'REQUIRE_ASSET')),
    target_field VARCHAR(40) CHECK (target_field IN ('BOOK_COUNT', 'PAGE_COUNT')),
    match_options JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(match_options) = 'object'),
    params JSONB NOT NULL CHECK (jsonb_typeof(params) = 'object'),
    priority INT NOT NULL DEFAULT 0,
    message VARCHAR(200) NOT NULL DEFAULT '',
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by BIGINT
);

INSERT INTO catalog.product_categories (code, label, emoji, description, order_type, form_enabled, sort_order) VALUES
('UNIFORMS', 'Uniforms', U&'\+01F455', 'Full uniform sets by size and house', 'Recurring', false, 1),
('NOTEBOOKS', 'Notebooks', U&'\+01F4D8', 'Ruled, unruled, graph and custom books', 'Recurring', true, 2),
('STATIONERY', 'Stationery', U&'\270F\FE0F', 'Student stationery kits and classroom essentials', 'Recurring', false, 3),
('IDCARDS', 'ID Cards', U&'\+01FAAA', 'Student and staff ID cards, lanyards and holders', 'One-time', false, 4),
('HOUSEKEEPING', 'Housekeeping', U&'\+01F9F9', 'Cleaning consumables and support services', 'Service', false, 5),
('EVENTS', 'Events', U&'\+01F389', 'Trophies, certificates, banners and event kits', 'One-time', false, 6),
('HEALTH', 'Health', U&'\+01FA7A', 'Infirmary essentials and annual health services', 'Service', false, 7);

INSERT INTO catalog.product_option_groups (category_code, code, label, level, scope) VALUES
('NOTEBOOKS', 'CUSTOMIZATION', 'Customization', 1, 'ORDER'),
('NOTEBOOKS', 'SIZE', 'Size', 2, 'LINE'),
('NOTEBOOKS', 'RULING', 'Ruling', 3, 'LINE');

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('CUSTOMIZED', 'Customized', 1), ('NON_CUSTOMIZED', 'Non-customized', 2)
) v(code, label, sort_order) WHERE g.category_code = 'NOTEBOOKS' AND g.code = 'CUSTOMIZATION';

INSERT INTO catalog.product_options (group_id, code, label, spec_text, width_mm, height_mm, spec_status, sort_order)
SELECT g.id, v.code, v.label, v.spec_text, v.width_mm, v.height_mm, v.spec_status, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('LONG', 'Long', '17 cm x 27 cm', 170, 270, 'CONFIRMED', 1),
('JUMBO_LONG', 'Jumbo Long', '18 cm x 24 cm', 180, 240, 'CONFIRMED', 2),
('KING', 'King', '', NULL, NULL, 'PENDING_SPEC', 3),
('JUMBO_KING', 'Jumbo King', '19 cm x 26 cm', 190, 260, 'CONFIRMED', 4),
('FA_A4', 'FA / A4 notebook', '21 cm x 29.7 cm', 210, 297, 'CONFIRMED', 5),
('DRAWING_BOOK', 'Drawing book', '', NULL, NULL, 'PENDING_SPEC', 6)
) v(code, label, spec_text, width_mm, height_mm, spec_status, sort_order)
WHERE g.category_code = 'NOTEBOOKS' AND g.code = 'SIZE';

INSERT INTO catalog.product_options (group_id, code, label, sort_order)
SELECT g.id, v.code, v.label, v.sort_order
FROM catalog.product_option_groups g CROSS JOIN (VALUES
('SINGLE_RULE', 'Single rule', 1), ('DOUBLE_RULE', 'Double rule', 2), ('FOUR_RULE', 'Four rule', 3),
('SQUARE_RULE', 'Square rule', 4), ('BROAD_RULE', 'Broad rule', 5), ('MATH_RULE', 'Math rule', 6),
('SPECIAL_MATH_RULE', 'Special math rule', 7), ('ONE_SIDE_SINGLE_RULE', 'One side single rule', 8),
('BIG_SQUARE_RULE', 'Big square rule', 9), ('PLAIN', 'Plain', 10), ('SKY_RULE', 'Sky rule', 11),
('FIVE_RULE', 'Five rule', 12), ('THREE_RULE', 'Three rule', 13),
('ONE_SIDE_FOUR_RULE', 'One side four rule', 14), ('ONE_SIDE_BROAD_RULE', 'One side broad rule', 15)
) v(code, label, sort_order) WHERE g.category_code = 'NOTEBOOKS' AND g.code = 'RULING';

INSERT INTO catalog.product_form_rules (category_code, rule_type, target_field, match_options, params, priority, message) VALUES
('NOTEBOOKS', 'REQUIRE_QUANTITY_TOTAL', 'BOOK_COUNT', '{"CUSTOMIZATION":"CUSTOMIZED"}', '{"value":1000,"comparison":"EQ","scope":"ORDER","stage":"ON_PLACE"}', 10, 'All sizes combined must meet the required book total'),
('NOTEBOOKS', 'ROUND_TO_MULTIPLE', 'PAGE_COUNT', '{}', '{"multiple":7,"mode":"NEAREST","minimum":7}', 20, 'Printed pages adjusted to the configured multiple'),
('NOTEBOOKS', 'REQUIRE_ASSET', NULL, '{"CUSTOMIZATION":"CUSTOMIZED"}', '{"assetKind":"DESIGN","stage":"ON_PLACE"}', 30, 'Upload design artwork before placing this customized order'),
('NOTEBOOKS', 'REQUIRE_ASSET', NULL, '{"CUSTOMIZATION":"CUSTOMIZED"}', '{"assetKind":"PRE_DELIVERY_PHOTO","stage":"BEFORE_DELIVERY"}', 40, 'Upload a pre-delivery photo before marking this order delivered');

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.product_categories, catalog.product_option_groups,
            catalog.product_options, catalog.product_option_dependencies, catalog.product_form_rules TO app_rt;
        GRANT USAGE, SELECT ON SEQUENCE catalog.product_option_groups_id_seq, catalog.product_options_id_seq,
            catalog.product_option_dependencies_id_seq, catalog.product_form_rules_id_seq TO app_rt;
    END IF;
END $$;
