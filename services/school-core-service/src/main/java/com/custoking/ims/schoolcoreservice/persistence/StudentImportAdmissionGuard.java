package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PostgreSQL-backed admission control for synchronous student-import confirmations.
 *
 * <p>Transaction-scoped advisory locks make the limit effective across threads,
 * connection pools, and Cloud Run replicas without an always-on coordinator. The
 * caller must invoke this inside the same transaction as the import. Locks are released
 * automatically on commit, rollback, or connection loss, so there is no stale in-memory
 * queue or lease cleanup process.</p>
 */
final class StudentImportAdmissionGuard {
    static final int FLEET_SLOTS = 2;
    private static final int RETRY_AFTER_SECONDS = 5;
    private static final long SCHOOL_LOCK_NAMESPACE = Long.MIN_VALUE;
    private static final long FLEET_SLOT_ONE = Long.MAX_VALUE;
    private static final long FLEET_SLOT_TWO = Long.MAX_VALUE - 1;

    private StudentImportAdmissionGuard() {
    }

    static void acquire(JdbcClient jdbc, long schoolId) {
        if (schoolId <= 0) {
            throw new IllegalArgumentException("School not found");
        }

        long schoolLockKey = SCHOOL_LOCK_NAMESPACE + schoolId;
        if (!tryLock(jdbc, schoolLockKey)) {
            throw new ImportAdmissionException(
                    "school_import_active",
                    "Another student import is already running for this school",
                    RETRY_AFTER_SECONDS);
        }

        if (tryLock(jdbc, FLEET_SLOT_ONE) || tryLock(jdbc, FLEET_SLOT_TWO)) {
            return;
        }
        throw new ImportAdmissionException(
                "import_capacity_busy",
                "Student import capacity is busy; retry shortly",
                RETRY_AFTER_SECONDS);
    }

    private static boolean tryLock(JdbcClient jdbc, long key) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
                .param("key", key)
                .query(Boolean.class)
                .single());
    }
}
