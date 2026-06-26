package com.custoking.ims.notificationservice.api;

import com.custoking.ims.notificationservice.persistence.NotificationLogCommandRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = "X-Notification-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "notification:write");
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

