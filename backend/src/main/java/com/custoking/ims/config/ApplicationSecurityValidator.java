package com.custoking.ims.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates security-critical configuration at startup.
 * Throws {@link IllegalStateException} with a consolidated error list if any
 * check fails — the application will not start.
 *
 * JwtService depends on this bean (via @DependsOn) so this validator always
 * runs first and produces a clear error before JJWT can throw a cryptic one.
 */
@Component
public class ApplicationSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSecurityValidator.class);

    private static final Set<String> KNOWN_WEAK_PASSWORDS = Set.of(
            "admin123", "Admin@123", "password", "123456"
    );

    @Value("${app.jwt-secret:}")
    private String jwtSecret;

    @Value("${app.aadhar-secret:}")
    private String aadharSecret;

    @Value("${app.bootstrap-users:true}")
    private boolean bootstrapUsers;

    // Optional bootstrap credentials — null means the env var was not set.
    @Value("${SUPERADMIN_PASSWORD:#{null}}")
    private String superadminPassword;

    @Value("${DEMO_ADMIN_PASSWORD:#{null}}")
    private String demoAdminPassword;

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();

        // ── Secret keys ───────────────────────────────────────────────────────
        if (jwtSecret.isBlank()) {
            errors.add("APP_JWT_SECRET is required but was not set");
        } else if (jwtSecret.length() < 32) {
            errors.add("APP_JWT_SECRET must be at least 32 characters (current length: "
                    + jwtSecret.length() + ")");
        }

        if (aadharSecret.isBlank()) {
            errors.add("APP_AADHAR_SECRET is required but was not set");
        } else if (aadharSecret.length() < 16) {
            errors.add("APP_AADHAR_SECRET must be at least 16 characters (current length: "
                    + aadharSecret.length() + ")");
        }

        // ── Bootstrap credentials (only checked when bootstrap is enabled) ────
        if (bootstrapUsers) {
            if (superadminPassword == null) {
                errors.add("SUPERADMIN_PASSWORD is required when APP_BOOTSTRAP_USERS=true");
            } else if (KNOWN_WEAK_PASSWORDS.contains(superadminPassword)) {
                errors.add("SUPERADMIN_PASSWORD matches a known weak value — use a strong, unique password");
            }

            // DEMO_ADMIN_PASSWORD is optional: absent means the demo admin is skipped.
            // If explicitly set it must still pass the weak-password check.
            if (demoAdminPassword != null && KNOWN_WEAK_PASSWORDS.contains(demoAdminPassword)) {
                errors.add("DEMO_ADMIN_PASSWORD matches a known weak value — use a strong, unique password");
            }
        }

        if (!errors.isEmpty()) {
            String message = "Application security configuration is invalid:\n  - "
                    + String.join("\n  - ", errors);
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Security configuration validated successfully (bootstrap={})", bootstrapUsers);
    }
}
