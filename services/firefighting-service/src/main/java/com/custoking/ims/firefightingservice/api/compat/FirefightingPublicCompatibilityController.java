package com.custoking.ims.firefightingservice.api.compat;

import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Supplier;

@RestController
public class FirefightingPublicCompatibilityController {

    private final FirefightingReadRepository firefighting;
    private final String readToken;

    public FirefightingPublicCompatibilityController(
            FirefightingReadRepository firefighting,
            @Value("${firefighting.read-token:}") String readToken) {
        this.firefighting = firefighting;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @PostMapping("/api/v1/workspace/firefighting")
    public Map<String, Object> createFromWorkspace(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token);
        return run(() -> firefighting.createRequest(request));
    }

    private void requireToken(String token) {
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid firefighting service token");
        }
    }

    private Map<String, Object> run(Supplier<Map<String, Object>> command) {
        try {
            return command.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
