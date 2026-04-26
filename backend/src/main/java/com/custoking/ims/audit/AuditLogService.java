package com.custoking.ims.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public void loginSuccess(Long userId, String email, String ip) {
        write(Map.of(
                "event", "LOGIN_SUCCESS",
                "userId", userId,
                "email", email,
                "ip", ip,
                "ts", Instant.now().toString()
        ));
    }

    public void loginFailure(String email, String ip) {
        write(Map.of(
                "event", "LOGIN_FAILURE",
                "email", email,
                "ip", ip,
                "ts", Instant.now().toString()
        ));
    }

    public void statusTransition(String entityType, String entityId,
                                  String oldStatus, String newStatus, Long actorUserId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", "STATUS_TRANSITION");
        entry.put("entityType", entityType);
        entry.put("entityId", entityId);
        entry.put("oldStatus", oldStatus);
        entry.put("newStatus", newStatus);
        entry.put("actorUserId", actorUserId);
        entry.put("ts", Instant.now().toString());
        write(entry);
    }

    private void write(Map<String, Object> fields) {
        try {
            log.info(mapper.writeValueAsString(fields));
        } catch (Exception e) {
            log.info("audit: {}", fields);
        }
    }
}
