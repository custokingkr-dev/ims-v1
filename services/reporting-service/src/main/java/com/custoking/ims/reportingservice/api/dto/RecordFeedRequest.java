package com.custoking.ims.reportingservice.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DTO for POST /command-center/feed.
 * Maps to recordFeed(Map) repo keys. All fields have defaults in the repo; none are required.
 * schoolId is intentionally nullable — feed rows do not require a tenant (per migration design).
 */
public record RecordFeedRequest(
        Long schoolId,
        String module,
        String eventType,
        String title,
        String message,
        String severity,
        String entityType,
        String entityId,
        Long actorUserId
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schoolId", schoolId);
        map.put("module", module);
        map.put("eventType", eventType);
        map.put("title", title);
        map.put("message", message);
        map.put("severity", severity);
        map.put("entityType", entityType);
        map.put("entityId", entityId);
        map.put("actorUserId", actorUserId);
        return map;
    }
}
