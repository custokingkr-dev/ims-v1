package com.custoking.ims.schoolcoreservice.absentee;

import java.time.LocalDate;
import java.util.Map;

/**
 * Everything a gateway needs to deliver one absentee notification. {@code eventId} is the
 * service-prefixed idempotency key ({@code school-core:absentee:<row id>}) — the same prefixing rule
 * the outbox relay applies, because an unprefixed id once collided across services.
 */
public record AbsenteeDeliveryRequest(
        String eventId,
        String notificationId,
        long schoolId,
        long studentId,
        String channel,
        String destination,
        String guardianId,
        LocalDate attendanceDate,
        String message,
        Map<String, Object> policyEvidence) {

    public static final String EVENT_ID_PREFIX = "school-core:absentee:";

    public static String eventIdFor(String notificationId) {
        return EVENT_ID_PREFIX + notificationId;
    }
}
