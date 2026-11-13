-- Item order workflows (design v1 §3.1, D6, D10, D21-D22, D36, D41). The notebook tracker table becomes the one row per
-- workflow order for every item. Its name stays (D6: renaming buys nothing and touches RLS, ACL sync and live sessions).
--   * definition_id / category_code / item_code / item_noun say which template, catalogue category, SOP item code and
--     introduction-message noun the order runs. Existing rows are all notebook trackers, so the defaults describe them.
--   * order_number is the SOP number school-core mints at placement (D10-D11); the CK id stays the key. Existing trackers
--     get theirs later, from school-core's re-queue (catalog V38) and the idempotent create.
--   * school_contact_name / school_contact_phone: the order's school contact (D36).
--   * approved_cdr_link / design_comment: Head Approval binds to the CDR link it approved, with the required comment
--     (D21). The old draft-image approval (approved_draft_ref) is retired; existing values stay.
--   * imported_from marks a workflow created from school-core's PR #380 production workflow (D4).
-- Owner backfills on forced-RLS tables need the deliberate bypass (V7).
SET LOCAL app.bypass_rls = 'on';

ALTER TABLE workflow.workflow_notebook_trackers
    ADD COLUMN definition_id        VARCHAR(100) NOT NULL DEFAULT 'NOTEBOOK_ORDER_TRACKER',
    ADD COLUMN category_code        VARCHAR(60)  NOT NULL DEFAULT 'NOTEBOOKS',
    ADD COLUMN item_code            VARCHAR(4)   NOT NULL DEFAULT 'NB',
    ADD COLUMN item_noun            VARCHAR(40)  NOT NULL DEFAULT 'notebook',
    ADD COLUMN order_number         VARCHAR(40),
    ADD COLUMN school_contact_name  VARCHAR(200),
    ADD COLUMN school_contact_phone VARCHAR(32),
    ADD COLUMN approved_cdr_link    VARCHAR(1000),
    ADD COLUMN design_comment       VARCHAR(1000),
    ADD COLUMN imported_from        VARCHAR(40);

ALTER TABLE workflow.workflow_notebook_trackers
    ADD CONSTRAINT fk_wnt_definition FOREIGN KEY (definition_id) REFERENCES workflow.workflow_definitions (id),
    -- school-core's category code rule (ProductCatalogRepository, CatalogDefinitionRequests).
    ADD CONSTRAINT ck_wnt_category_code CHECK (category_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    ADD CONSTRAINT ck_wnt_item_code CHECK (item_code ~ '^[A-Z]{2,4}$'),
    ADD CONSTRAINT ck_wnt_item_noun CHECK (btrim(item_noun) <> ''),
    ADD CONSTRAINT ck_wnt_order_number CHECK (order_number IS NULL OR btrim(order_number) <> ''),
    ADD CONSTRAINT ck_wnt_imported_from CHECK (imported_from IN ('PRODUCT_WORKFLOW_V36'));

-- Order numbers are globally unique (school-core's catalog_orders.order_number is too); drafts and legacy rows have none.
CREATE UNIQUE INDEX uk_wnt_order_number ON workflow.workflow_notebook_trackers (order_number) WHERE order_number IS NOT NULL;
-- Item-scoped operators read (school, category) pairs (D42).
CREATE INDEX idx_wnt_category_school ON workflow.workflow_notebook_trackers (category_code, school_id);

-- An approval reference (the retired draft id, or the CDR link approved now) exists only while the design is approved.
-- Requesting changes therefore clears approved_cdr_link, and the link must be approved again.
ALTER TABLE workflow.workflow_notebook_trackers DROP CONSTRAINT ck_wnt_approved_ref;
ALTER TABLE workflow.workflow_notebook_trackers ADD CONSTRAINT ck_wnt_approved_ref
    CHECK ((approved_draft_ref IS NULL AND approved_cdr_link IS NULL) OR design_state = 'APPROVED');

-- Pre-print school verification (D22): NOT_REQUIRED_OR_PENDING splits into NOT_REQUIRED and PENDING. Nobody recorded which
-- one an old value meant, so it becomes PENDING, which the new gate does not pass until someone chooses Verified or Not
-- required. Completed steps are never re-gated (D41). The step's version moves so a browser that loaded the old value
-- gets a conflict instead of saving over the change.
UPDATE workflow.workflow_instance_steps
SET data = jsonb_set(data, '{schoolVerification}', '"PENDING"'),
    version = version + 1
WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER'
  AND step_order = 7
  AND data ->> 'schoolVerification' = 'NOT_REQUIRED_OR_PENDING';
