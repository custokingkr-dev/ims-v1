package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.SharedQuotaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class AuthAbuseProtection {
    private final SharedQuotaRepository quotas;
    private final byte[] secret;
    private final int loginGlobal;
    private final int userRead;
    private final int userWrite;
    public AuthAbuseProtection(SharedQuotaRepository quotas, @Value("${app.jwt-secret}") String secret,
            @Value("${identity.quotas.login-per-minute:300}") int loginGlobal,
            @Value("${identity.quotas.user-reads-per-minute:900}") int userRead,
            @Value("${identity.quotas.user-writes-per-minute:180}") int userWrite) {
        if (loginGlobal < 1 || userRead < 1 || userWrite < 1) throw new IllegalArgumentException("Quotas must be positive");
        this.quotas = quotas; this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.loginGlobal = loginGlobal; this.userRead = userRead; this.userWrite = userWrite;
    }

    public void login(String email) {
        require("login:fleet", loginGlobal, 60);
        require("login:account:" + fingerprint(email), 10, 900);
    }

    public void resetRequest(String email) {
        require("reset-request:fleet", 50, 60);
        require("reset-request:account:" + fingerprint(email), 3, 3600);
    }

    public void resetConfirm(String token) {
        require("reset-confirm:fleet", 120, 60);
        require("reset-confirm:token:" + fingerprint(token), 6, 60);
    }

    public void authenticated(IdentityAuthService.AuthResponse principal, String method, String path, Long requestedSchoolId) {
        if (principal == null || principal.userId() == null) return;
        boolean write = method != null && !method.equals("GET") && !method.equals("HEAD");
        String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String kind = write ? "write" : "read";
        int userLimit = write ? userWrite : userRead;
        int schoolLimit = write ? 1000 : 5000;
        if (write && normalized.contains("import")) { kind = "import"; userLimit = 10; schoolLimit = 30; }
        else if (normalized.contains("export")) { kind = "export"; userLimit = 30; schoolLimit = 60; }
        require(kind + ":user:" + principal.userId(), userLimit, 60);
        // A route/query school hint can charge a school only when current identity grants access.
        // It is an accounting hint, never authorization for the downstream operation itself.
        Long schoolId = principal.branchId();
        if (requestedSchoolId != null && requestedSchoolId > 0
                && ("SUPERADMIN".equals(principal.role()) || requestedSchoolId.equals(principal.branchId())
                    || (principal.operatorSchools() != null && principal.operatorSchools().contains(requestedSchoolId)))) {
            schoolId = requestedSchoolId;
        }
        if (schoolId != null) require(kind + ":school:" + schoolId, schoolLimit, 60);
    }

    private void require(String key, int count, int seconds) {
        if (!quotas.consume(key, count, seconds)) throw new QuotaExceeded(seconds);
    }

    private String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }

    public static final class QuotaExceeded extends ResponseStatusException {
        private final int seconds;
        QuotaExceeded(int seconds) { super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later."); this.seconds = seconds; }
        @Override public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders(); headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(seconds)); return headers;
        }
    }
}
