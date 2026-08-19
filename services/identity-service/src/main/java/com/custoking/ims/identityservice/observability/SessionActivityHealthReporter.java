package com.custoking.ims.identityservice.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Publishes live session activity as a structured health log so Cloud Monitoring can chart it.
 *
 * <p>Monitoring cannot query Postgres, and session state lives only in the database. The same problem was
 * already solved for outbox depth by emitting a structured log that a log-based metric extracts, so this
 * follows that shape rather than inventing a second mechanism.
 *
 * <p>The counts are deliberately not row counts. Refresh-token rotation means a single continuous login
 * leaves a trail of ROTATED rows behind it — production currently holds 329 ROTATED against 17 ACTIVE — so
 * counting rows would overstate activity by an order of magnitude. Only unexpired ACTIVE rows represent a
 * session someone could still be using, and distinct users over those is the figure a human means by
 * "who is on the system right now".
 */
@Component
public class SessionActivityHealthReporter {
    private static final Logger log = LoggerFactory.getLogger(SessionActivityHealthReporter.class);

    private final JdbcClient jdbc;
    private final String sessionsTable;
    private final String usersTable;

    public SessionActivityHealthReporter(JdbcClient jdbc,
                                         @Value("${identity.db.schema:identity}") String schema) {
        this.jdbc = jdbc;
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid identity schema");
        }
        this.sessionsTable = schema + ".auth_sessions";
        this.usersTable = schema + ".app_users";
    }

    @Scheduled(initialDelayString = "${identity.session.health.initial-delay-ms:30000}",
            fixedDelayString = "${identity.session.health.fixed-delay-ms:60000}")
    public void report() {
        try {
            Map<String, Object> state = snapshot();
            log.info("session.activity.health {}", kv("health", Map.of("sessions", state)));
        } catch (RuntimeException ex) {
            // Never fail the service for a metrics emission. A gap in the chart is recoverable; a restart
            // loop caused by observability is not.
            log.warn("session.activity.health.failed error={}", ex.getMessage());
        }
    }

    Map<String, Object> snapshot() {
        return jdbc.sql("""
                        SELECT (SELECT count(DISTINCT user_id) FROM %1$s
                                WHERE status = 'ACTIVE' AND expires_at > now())            AS active_users,
                               (SELECT count(*) FROM %1$s
                                WHERE status = 'ACTIVE' AND expires_at > now())            AS active_sessions,
                               (SELECT count(DISTINCT user_id) FROM %1$s
                                WHERE created_at > now() - interval '15 minutes')          AS users_last_15m,
                               (SELECT count(*) FROM %1$s
                                WHERE created_at > now() - interval '15 minutes')          AS logins_last_15m,
                               (SELECT count(*) FROM %2$s WHERE deleted_at IS NULL)        AS provisioned_users
                        """.formatted(sessionsTable, usersTable))
                .query((rs, row) -> Map.<String, Object>of(
                        "activeUsers", rs.getLong("active_users"),
                        "activeSessions", rs.getLong("active_sessions"),
                        "usersLast15m", rs.getLong("users_last_15m"),
                        "loginsLast15m", rs.getLong("logins_last_15m"),
                        "provisionedUsers", rs.getLong("provisioned_users")))
                .single();
    }
}
