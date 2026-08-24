package com.custoking.ims.schoolcoreservice.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

import static java.util.Map.entry;
import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Emits bounded-cardinality storage and access-path health for the attendance fact table.
 *
 * <p>Cloud SQL exposes instance storage, but not relation-level rows, index bytes, or PostgreSQL scan
 * counters. The corresponding Cloud Monitoring metrics are therefore extracted from this structured
 * health log. The query uses planner/autovacuum estimates rather than {@code count(*)}, so its cost does
 * not grow linearly with the attendance table. {@code pg_partition_tree} also makes the same reporter
 * work before and after the staged DATA-01 partition cutover.
 *
 * <p>PostgreSQL scan counters are cumulative since the last statistics reset. Alerting on the raw values
 * would eventually leave an incident open forever, so this reporter emits per-process interval deltas.
 * A fresh Cloud Run instance emits a zero-delta baseline; multiple instances are reduced with MAX in
 * Monitoring rather than summed. Counter resets are detected and never converted into negative values.
 */
@Component
public class AttendanceStorageHealthReporter {
    private static final Logger log = LoggerFactory.getLogger(AttendanceStorageHealthReporter.class);

    private final JdbcClient jdbc;
    private Sample previous;

    public AttendanceStorageHealthReporter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelayString = "${school-core.attendance.storage-health.initial-delay-ms:60000}",
            fixedDelayString = "${school-core.attendance.storage-health.fixed-delay-ms:300000}")
    public synchronized void report() {
        try {
            Sample current = snapshot();
            Interval interval = interval(previous, current);
            previous = current;
            Map<String, Object> state = Map.ofEntries(
                    entry("approximateRows", current.approximateRows()),
                    entry("heapBytes", current.heapBytes()),
                    entry("indexBytes", current.indexBytes()),
                    entry("totalBytes", current.totalBytes()),
                    entry("sequentialScansInterval", interval.sequentialScans()),
                    entry("sequentialTuplesReadInterval", interval.sequentialTuplesRead()),
                    entry("indexScansInterval", interval.indexScans()),
                    entry("fullTableScanEquivalentsMilli", interval.fullTableScanEquivalentsMilli()),
                    entry("statisticsResetDetected", interval.statisticsResetDetected()),
                    entry("baseline", interval.baseline()));
            log.info("attendance.storage.health {}", kv("health", Map.of("attendanceStorage", state)));
        } catch (RuntimeException ex) {
            // Storage telemetry must never turn a healthy application into a restart loop.
            log.warn("attendance.storage.health.failed error={}", ex.getMessage());
        }
    }

    Sample snapshot() {
        return jdbc.sql("""
                        WITH partition_tree AS MATERIALIZED (
                            SELECT relid
                            FROM pg_partition_tree('attendance.attendance_student_records'::regclass)
                        ), relations AS MATERIALIZED (
                            SELECT relid FROM partition_tree
                            UNION ALL
                            SELECT 'attendance.attendance_student_records'::regclass
                            WHERE NOT EXISTS (SELECT 1 FROM partition_tree)
                        )
                        SELECT COALESCE(sum(GREATEST(stats.n_live_tup, classes.reltuples)), 0)::bigint
                                   AS approximate_rows,
                               COALESCE(sum(pg_table_size(relations.relid)), 0)::bigint AS heap_bytes,
                               COALESCE(sum(pg_indexes_size(relations.relid)), 0)::bigint AS index_bytes,
                               COALESCE(sum(pg_total_relation_size(relations.relid)), 0)::bigint AS total_bytes,
                               COALESCE(sum(stats.seq_scan), 0)::bigint AS seq_scan,
                               COALESCE(sum(stats.seq_tup_read), 0)::bigint AS seq_tup_read,
                               COALESCE(sum(stats.idx_scan), 0)::bigint AS idx_scan
                        FROM relations
                        JOIN pg_class classes ON classes.oid = relations.relid
                        LEFT JOIN pg_stat_user_tables stats ON stats.relid = relations.relid
                        """)
                .query((rs, row) -> new Sample(
                        rs.getLong("approximate_rows"),
                        rs.getLong("heap_bytes"),
                        rs.getLong("index_bytes"),
                        rs.getLong("total_bytes"),
                        rs.getLong("seq_scan"),
                        rs.getLong("seq_tup_read"),
                        rs.getLong("idx_scan")))
                .single();
    }

    static Interval interval(Sample previous, Sample current) {
        if (previous == null) {
            return new Interval(0, 0, 0, 0, false, true);
        }
        boolean reset = current.sequentialScans() < previous.sequentialScans()
                || current.sequentialTuplesRead() < previous.sequentialTuplesRead()
                || current.indexScans() < previous.indexScans();
        if (reset) {
            return new Interval(0, 0, 0, 0, true, false);
        }
        long sequentialScans = current.sequentialScans() - previous.sequentialScans();
        long sequentialTuplesRead = current.sequentialTuplesRead() - previous.sequentialTuplesRead();
        long indexScans = current.indexScans() - previous.indexScans();
        // Sequential scans are expected and cheap while the relation is small. Suppress the normalized
        // risk signal below the documented 1M partition-preparation floor so tiny healthy tables cannot
        // open a permanent alert merely because PostgreSQL correctly chose a sequential scan.
        long equivalentsMilli = current.approximateRows() < 1_000_000 ? 0
                : Math.round(sequentialTuplesRead * 1000.0 / current.approximateRows());
        return new Interval(sequentialScans, sequentialTuplesRead, indexScans,
                equivalentsMilli, false, false);
    }

    record Sample(long approximateRows, long heapBytes, long indexBytes, long totalBytes,
                  long sequentialScans, long sequentialTuplesRead, long indexScans) {
    }

    record Interval(long sequentialScans, long sequentialTuplesRead, long indexScans,
                    long fullTableScanEquivalentsMilli, boolean statisticsResetDetected,
                    boolean baseline) {
    }
}
