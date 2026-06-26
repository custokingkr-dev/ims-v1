CREATE TABLE IF NOT EXISTS firefighting_requests (
    code                    VARCHAR(255) NOT NULL,
    title                   VARCHAR(255),
    category                VARCHAR(255),
    urgency                 VARCHAR(255),
    required_by_date        DATE,
    estimated_budget        BIGINT       NOT NULL,
    description             TEXT,
    reference_file_url      VARCHAR(255),
    raised_by               BIGINT,
    status                  VARCHAR(255),
    bursar_note             VARCHAR(255),
    principal_note          VARCHAR(255),
    bursar_approved_at      TIMESTAMPTZ,
    principal_approved_at   TIMESTAMPTZ,
    rejected_by             VARCHAR(255),
    rejected_reason         VARCHAR(255),
    custoking_criteria_json TEXT,
    winner_vendor           VARCHAR(255),
    winner_amount           BIGINT,
    created_at              TIMESTAMPTZ,
    school_id               BIGINT,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_by              VARCHAR(255),
    updated_by              VARCHAR(255),
    vendor_paid_at          TIMESTAMPTZ,
    vendor_paid_by          BIGINT,
    vendor_payment_notes    TEXT,
    PRIMARY KEY (code),
    CONSTRAINT fk_ff_request_school FOREIGN KEY (school_id) REFERENCES public.schools (id),
    CONSTRAINT fk_ff_vendor_paid_by FOREIGN KEY (vendor_paid_by) REFERENCES public.app_users (id)
);

CREATE TABLE IF NOT EXISTS ff_quotations (
    id                VARCHAR(255) NOT NULL,
    vendor_name       VARCHAR(255),
    amount            BIGINT       NOT NULL,
    delivery_timeline VARCHAR(255),
    notes             VARCHAR(255),
    document_url      VARCHAR(255),
    is_custoking      BOOLEAN      NOT NULL,
    is_recommended    BOOLEAN      NOT NULL,
    created_at        TIMESTAMPTZ,
    request_id        VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ff_quotation_request FOREIGN KEY (request_id) REFERENCES firefighting_requests (code)
);

CREATE INDEX IF NOT EXISTS idx_ff_requests_school ON firefighting_requests (school_id);
CREATE INDEX IF NOT EXISTS idx_ff_requests_status ON firefighting_requests (status);
CREATE INDEX IF NOT EXISTS idx_ff_requests_school_status ON firefighting_requests (school_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ff_requests_school_id_status ON firefighting_requests (school_id, status);
CREATE INDEX IF NOT EXISTS idx_ff_requests_school_created ON firefighting_requests (school_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ff_requests_vendor_unpaid ON firefighting_requests (school_id, status) WHERE vendor_paid_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ff_quotations_request ON ff_quotations (request_id);

INSERT INTO firefighting_requests
    (code, title, category, urgency, required_by_date, estimated_budget,
     description, reference_file_url, raised_by, status, bursar_note,
     principal_note, bursar_approved_at, principal_approved_at, rejected_by,
     rejected_reason, custoking_criteria_json, winner_vendor, winner_amount,
     created_at, school_id, version, created_by, updated_by, vendor_paid_at,
     vendor_paid_by, vendor_payment_notes)
SELECT code, title, category, urgency, required_by_date, estimated_budget,
       description, reference_file_url, raised_by, status, bursar_note,
       principal_note, bursar_approved_at, principal_approved_at, rejected_by,
       rejected_reason, custoking_criteria_json, winner_vendor, winner_amount,
       created_at, school_id, version, created_by, updated_by, vendor_paid_at,
       vendor_paid_by, vendor_payment_notes
FROM public.firefighting_requests
ON CONFLICT (code) DO NOTHING;

INSERT INTO ff_quotations
    (id, vendor_name, amount, delivery_timeline, notes, document_url,
     is_custoking, is_recommended, created_at, request_id)
SELECT id, vendor_name, amount, delivery_timeline, notes, document_url,
       is_custoking, is_recommended, created_at, request_id
FROM public.ff_quotations
ON CONFLICT (id) DO NOTHING;
