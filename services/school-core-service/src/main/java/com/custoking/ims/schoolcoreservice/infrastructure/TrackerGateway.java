package com.custoking.ims.schoolcoreservice.infrastructure;

import java.time.OffsetDateTime;

/** What school-core needs from the notebook order tracker, which lives in operations-service. */
public interface TrackerGateway {

    record NewTracker(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                      String timeZone, Long placedBy, Long assigneeUserId, String schoolName) {
        public NewTracker(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                          String timeZone, Long placedBy, Long assigneeUserId) {
            this(orderId, schoolId, customised, orderedCount, placedAt, timeZone, placedBy, assigneeUserId, null);
        }
    }

    /** The tracker's answer to "may this user upload or delete draft images for this order right now". */
    record ImageAccess(boolean allowed, String reason) { }

    /** Creates the tracker for a placed order; idempotent. Throws {@link IllegalStateException} when it cannot be reached. */
    void create(NewTracker tracker);

    /** Throws {@link IllegalStateException} when the tracker cannot be reached, so callers refuse instead of guessing. */
    ImageAccess imageAccess(String orderId, long userId, String role);
}
