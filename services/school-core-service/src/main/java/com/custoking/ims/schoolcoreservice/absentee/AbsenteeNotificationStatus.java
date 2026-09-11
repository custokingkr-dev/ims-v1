package com.custoking.ims.schoolcoreservice.absentee;

/**
 * Lifecycle of an {@code attendance.absentee_notifications} row.
 *
 * <pre>
 *   QUEUED ──claim──▶ (deliver) ──▶ SENT           delivered by the provider (live mode only)
 *                                 ──▶ SENT_DRY_RUN   every step ran except the real send
 *                                 ──▶ SUPPRESSED     consent/preference policy denied at dispatch
 *                                 ──▶ FAILED         transient; retried after next_attempt_at
 *     FAILED ──retry──▶ ...        ──▶ DEAD_LETTER    retry budget exhausted, or permanent failure
 * </pre>
 *
 * <p>{@code SENT_DRY_RUN} exists so that nobody can mistake a dry-run for a delivery: a row only ever
 * reads {@code SENT} when the platform provider reported a real, non-dry-run send.
 */
public final class AbsenteeNotificationStatus {

    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";
    public static final String SENT_DRY_RUN = "SENT_DRY_RUN";
    public static final String SUPPRESSED = "SUPPRESSED";
    public static final String FAILED = "FAILED";
    public static final String DEAD_LETTER = "DEAD_LETTER";

    private AbsenteeNotificationStatus() {
    }
}
