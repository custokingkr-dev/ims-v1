ALTER TABLE command_center_feed
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_id VARCHAR(120);

CREATE UNIQUE INDEX IF NOT EXISTS uq_cc_feed_source
    ON command_center_feed(source_type, source_id)
    WHERE source_type IS NOT NULL AND source_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cc_feed_source
    ON command_center_feed(source_type, source_id);
