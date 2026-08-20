package com.custoking.ims.schoolcoreservice.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Publishes platform-wide business counts as a structured health log, for the product dashboard.
 *
 * <p>These are deliberately cross-tenant. "How many schools are on the platform" is a question about the
 * platform, not about any one tenant, and there is no authenticated school to scope it to — this runs on a
 * scheduler, not on a request.
 *
 * <p>That makes the RLS bypass load-bearing rather than incidental. {@code tenant_school.schools} and
 * {@code student.students} both carry a {@code tenant_isolation} policy keyed on
 * {@code app.current_school_id}, and the runtime role is NOBYPASSRLS. Counting them without the bypass
 * does not fail — it silently returns <b>0</b>, which would render as a perfectly plausible chart showing
 * a platform with no schools and no students. Measured against production: 0 and 0, where the truth was
 * 11 and 1,257.
 *
 * <p>The bypass is applied inside a single statement rather than across a {@code @Transactional} method.
 * That is deliberate: this class calls its own snapshot method, and Spring's proxy-based transactions do
 * not apply to self-invocation, so the annotation would have been silently inert -- {@code set_config}'s
 * transaction-local form would have cleared before the counts ran, and every number would have been zero
 * again. A MATERIALIZED CTE forces the bypass to be evaluated before the counts within one statement,
 * which is its own transaction under autocommit. Verified against production as the runtime role: 11
 * schools and 1,257 students, not 0 and 0.
 */
@Component
public class PlatformBusinessHealthReporter {
    private static final Logger log = LoggerFactory.getLogger(PlatformBusinessHealthReporter.class);

    private final JdbcClient jdbc;

    public PlatformBusinessHealthReporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelayString = "${school-core.business.health.initial-delay-ms:45000}",
            fixedDelayString = "${school-core.business.health.fixed-delay-ms:300000}")
    public void report() {
        try {
            Map<String, Object> state = snapshot();
            log.info("platform.business.health {}", kv("health", Map.of("platform", state)));
        } catch (RuntimeException ex) {
            // Never fail the service for a metrics emission. A gap in a product chart is recoverable; a
            // restart loop caused by observability is not.
            log.warn("platform.business.health.failed error={}", ex.getMessage());
        }
    }

    Map<String, Object> snapshot() {
        return jdbc.sql("""
                        WITH bypass AS MATERIALIZED (SELECT set_config('app.bypass_rls', 'on', true))
                        SELECT (SELECT count(*) FROM tenant_school.schools)                       AS schools,
                               (SELECT count(*) FROM tenant_school.school_sections)               AS sections,
                               (SELECT count(*) FROM student.students WHERE deleted_at IS NULL)   AS students_live,
                               (SELECT count(*) FROM student.students)                            AS students_total,
                               -- Reach rather than inventory: a school with no students is provisioned but
                               -- not actually in use, and the difference is the interesting number.
                               (SELECT count(DISTINCT school_id) FROM student.students
                                 WHERE deleted_at IS NULL)                                        AS schools_with_students
                        FROM bypass
                        """)
                .query((rs, row) -> Map.<String, Object>of(
                        "schools", rs.getLong("schools"),
                        "sections", rs.getLong("sections"),
                        "studentsLive", rs.getLong("students_live"),
                        "studentsTotal", rs.getLong("students_total"),
                        "schoolsWithStudents", rs.getLong("schools_with_students")))
                .single();
    }
}
