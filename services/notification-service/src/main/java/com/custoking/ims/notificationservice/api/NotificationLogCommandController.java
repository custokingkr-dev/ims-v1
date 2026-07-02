package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.api.dto.CreateLogRequest;
import com.custoking.ims.notificationservice.persistence.NotificationLogCommandRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications/logs")
public class NotificationLogCommandController {

    private final NotificationLogCommandRepository logs;
    private final String statusToken;

    public NotificationLogCommandController(NotificationLogCommandRepository logs,
                                            @Value("${notification.status.token:}") String statusToken) {
        this.logs = logs;
        this.statusToken = statusToken == null ? "" : statusToken.trim();
    }

    /** CONVERTED: required fields channel + notificationType validated before repo call. */
    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @Valid @RequestBody CreateLogRequest req) {
        requireToken(token, "notification:write");
        Map<String, Object> body = new HashMap<>();
        body.put("channel", req.channel());
        body.put("notificationType", req.notificationType());
        if (req.id() != null) body.put("id", req.id());
        if (req.schoolId() != null) body.put("schoolId", req.schoolId());
        if (req.studentId() != null) body.put("studentId", req.studentId());
        if (req.parentContact() != null) body.put("parentContact", req.parentContact());
        if (req.message() != null) body.put("message", req.message());
        if (req.status() != null) body.put("status", req.status());
        if (req.sentBy() != null) body.put("sentBy", req.sentBy());
        try {
            return logs.createRequestLog(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(statusToken) || !statusToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid notification service token");
        }
    }
}

