package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.PasswordResetRepository;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class PasswordResetService {
    private final PasswordResetRepository repository;
    private final PasswordResetMailer mailer;
    private final PasswordEncoder encoder;
    private final AuthAbuseProtection abuse;
    private final SecureRandom random = new SecureRandom();
    public PasswordResetService(PasswordResetRepository repository, PasswordResetMailer mailer,
            PasswordEncoder encoder, AuthAbuseProtection abuse,
            @Value("${identity.password-reset.worker-ready:false}") boolean workerReady) {
        if (mailer.enabled() && !workerReady) throw new IllegalArgumentException(
                "Password reset requires an always-allocated worker with a minimum running instance; verify runtime configuration before enabling it.");
        this.repository = repository; this.mailer = mailer; this.encoder = encoder; this.abuse = abuse;
    }

    public boolean enabled() { return mailer.enabled(); }
    public void request(String email) {
        requireEnabled(); abuse.resetRequest(email);
        // The public response never waits for SMTP, preventing delivery latency from exposing
        // account existence. The durable intent survives restarts and transient mail failures.
        repository.enqueue(email);
    }

    @Scheduled(fixedDelayString = "${identity.password-reset.delivery-delay-ms:5000}")
    public void deliverPending() {
        if (!enabled()) return;
        for (int i = 0; i < 10; i++) {
            var pending = repository.claim();
            if (pending.isEmpty()) return;
            var delivery = pending.get();
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            var recipient = repository.issueForDelivery(delivery, token);
            if (recipient.isEmpty()) { repository.finishDelivery(delivery, false); continue; }
            try {
                mailer.send(recipient.get(), token);
                repository.finishDelivery(delivery, true);
            } catch (org.springframework.mail.MailException ex) {
                LoggerFactory.getLogger(PasswordResetService.class).warn("password_reset_delivery_failed");
                repository.finishDelivery(delivery, false);
            }
        }
    }

    public void confirm(String token, String password) {
        requireEnabled(); abuse.resetConfirm(token);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) invalidToken();
        if (password == null || password.codePointCount(0, password.length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use at least 12 characters and at most 72 UTF-8 bytes for your password.");
        }
        if (!repository.confirm(token, password, encoder)) invalidToken();
    }
    private void requireEnabled() {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Contact your administrator for account recovery.");
    }
    private void invalidToken() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This reset link is invalid or expired. Request a new link.");
    }
}
