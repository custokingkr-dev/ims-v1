WITH client_backends AS (
  SELECT
    a.pid,
    COALESCE(s.ssl, false) AS ssl
  FROM pg_stat_activity a
  LEFT JOIN pg_stat_ssl s ON s.pid = a.pid
  WHERE a.backend_type = 'client backend'
    AND a.pid <> pg_backend_pid()
    AND a.datname = current_database()
    -- Cloud SQL owns this documented system role. Its local managed-service
    -- processes are not application clients and can appear without pg_stat_ssl.
    AND a.usename IS DISTINCT FROM 'cloudsqladmin'
)
SELECT json_build_object(
  'clientBackends', count(*),
  'encryptedBackends', count(*) FILTER (WHERE ssl),
  'unencryptedBackends', count(*) FILTER (WHERE NOT ssl)
)
FROM client_backends;
