package com.custoking.ims.schoolcoreservice.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fail-closed guard: RLS is only enforced when the runtime connects as the unprivileged
 * role (app_rt). If a deployment connects as the table owner / a superuser, RLS is silently
 * bypassed. In the prod profile we refuse to start if the runtime DB user is not app_rt.
 */
@Component
public class RuntimeDbRoleGuard {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDbRoleGuard.class);
    private static final String EXPECTED_RUNTIME_ROLE = "app_rt";

    private final JdbcClient jdbc;
    private final Environment environment;

    public RuntimeDbRoleGuard(JdbcClient jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyRuntimeRole() {
        String currentUser = jdbc.sql("SELECT current_user").query(String.class).single();
        boolean prod = environment.matchesProfiles("prod");
        if (EXPECTED_RUNTIME_ROLE.equals(currentUser)) {
            log.info("Runtime DB role check: connected as '{}' (RLS-subject).", currentUser);
            return;
        }
        String message = "Runtime DB role is '" + currentUser + "', expected '" + EXPECTED_RUNTIME_ROLE
                + "'. RLS is NOT enforced for this role — tenant isolation backstop is inert.";
        if (prod) {
            throw new IllegalStateException(message);
        }
        log.warn("{} (allowed outside the prod profile)", message);
    }
}
