-- Reserve an immutable key before touching object storage. Pending/retired rows
-- form a durable cleanup queue, including crashes between object write and commit.
CREATE TABLE firefighting.quotation_documents (
    id VARCHAR(36) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    request_code VARCHAR(255) NOT NULL,
    quotation_id VARCHAR(255) NOT NULL,
    object_key VARCHAR(600) NOT NULL UNIQUE,
    filename VARCHAR(200) NOT NULL,
    content_type VARCHAR(100) NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    checksum_sha256 VARCHAR(64) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    uploaded_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'READY', 'RETIRED', 'DELETED')),
    cleanup_attempts INTEGER NOT NULL DEFAULT 0,
    next_cleanup_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(id, school_id, quotation_id, request_code)
);
ALTER TABLE firefighting.ff_quotations ADD COLUMN document_id VARCHAR(36);
ALTER TABLE firefighting.ff_quotations ADD CONSTRAINT fk_quotation_private_document
    FOREIGN KEY (document_id, school_id, id, request_id)
    REFERENCES firefighting.quotation_documents(id, school_id, quotation_id, request_code);
CREATE INDEX idx_quotation_document_cleanup ON firefighting.quotation_documents(status, next_cleanup_at, created_at)
    WHERE status IN ('PENDING', 'RETIRED', 'DELETED');
CREATE INDEX idx_quotation_document_school ON firefighting.quotation_documents(school_id);
ALTER TABLE firefighting.quotation_documents ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON firefighting.quotation_documents
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
        OR current_setting('app.bypass_rls', true) = 'on');

-- Quotation deletion/replacement atomically retires the old object. Cleanup never
-- deletes a key still referenced by a live quotation.
CREATE FUNCTION firefighting.retire_quotation_document() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.document_id IS NOT NULL AND (TG_OP = 'DELETE' OR OLD.document_id IS DISTINCT FROM NEW.document_id) THEN
        UPDATE firefighting.quotation_documents SET status = 'RETIRED', next_cleanup_at = now()
        WHERE id = OLD.document_id;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER retire_quotation_document AFTER DELETE OR UPDATE OF document_id ON firefighting.ff_quotations
    FOR EACH ROW EXECUTE FUNCTION firefighting.retire_quotation_document();

-- Retained tombstones reconcile late storage writes. Runtime may advance cleanup
-- state, but may neither erase them nor rewrite the reserved object identity.
REVOKE ALL ON firefighting.quotation_documents FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
    REVOKE ALL ON firefighting.quotation_documents FROM app_rt;
    GRANT SELECT, INSERT ON firefighting.quotation_documents TO app_rt;
    GRANT UPDATE (status, uploaded_at, cleanup_attempts, next_cleanup_at)
        ON firefighting.quotation_documents TO app_rt;
END IF; END $$;
