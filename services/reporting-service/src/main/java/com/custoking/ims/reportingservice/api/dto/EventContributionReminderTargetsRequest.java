package com.custoking.ims.reportingservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for POST /event-contributions/reminder-targets.
 * Maps to eventPaymentReminderTargets(Map): eventId and schoolId are required by the repo.
 * studentIds is optional — an empty list causes the repo to return early with empty targets.
 */
public record EventContributionReminderTargetsRequest(
        @NotBlank(message = "eventId is required") String eventId,
        @NotNull(message = "schoolId is required") Long schoolId,
        List<Long> studentIds
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", eventId);
        map.put("schoolId", schoolId);
        if (studentIds != null) map.put("studentIds", studentIds);
        return map;
    }
}
