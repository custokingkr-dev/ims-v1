-- Auth sessions store SHA-256 token digests, not raw bearer tokens.
-- Rename the columns and constraints so schema names reflect that security model.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'auth_sessions' AND column_name = 'access_token'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'auth_sessions' AND column_name = 'access_token_hash'
    ) THEN
        ALTER TABLE auth_sessions RENAME COLUMN access_token TO access_token_hash;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'auth_sessions' AND column_name = 'refresh_token'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'auth_sessions' AND column_name = 'refresh_token_hash'
    ) THEN
        ALTER TABLE auth_sessions RENAME COLUMN refresh_token TO refresh_token_hash;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'auth_sessions'::regclass AND conname = 'idx_auth_session_access'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'auth_sessions'::regclass AND conname = 'idx_auth_session_access_hash'
    ) THEN
        ALTER TABLE auth_sessions RENAME CONSTRAINT idx_auth_session_access TO idx_auth_session_access_hash;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'auth_sessions'::regclass AND conname = 'idx_auth_session_refresh'
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'auth_sessions'::regclass AND conname = 'idx_auth_session_refresh_hash'
    ) THEN
        ALTER TABLE auth_sessions RENAME CONSTRAINT idx_auth_session_refresh TO idx_auth_session_refresh_hash;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'auth_sessions'::regclass AND conname = 'idx_auth_session_access_hash'
    ) THEN
        ALTER TABLE auth_sessions ADD CONSTRAINT idx_auth_session_access_hash UNIQUE (access_token_hash);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'auth_sessions'::regclass AND conname = 'idx_auth_session_refresh_hash'
    ) THEN
        ALTER TABLE auth_sessions ADD CONSTRAINT idx_auth_session_refresh_hash UNIQUE (refresh_token_hash);
    END IF;
END $$;
