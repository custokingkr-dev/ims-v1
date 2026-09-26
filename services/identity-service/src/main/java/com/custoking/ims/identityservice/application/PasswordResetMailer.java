package com.custoking.ims.identityservice.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import java.net.URI;

@Component
public class PasswordResetMailer {
    private final boolean enabled;
    private final String from;
    private final String resetUrl;
    private final JavaMailSenderImpl sender;

    public PasswordResetMailer(@Value("${identity.password-reset.enabled:false}") boolean enabled,
            @Value("${identity.password-reset.url:}") String resetUrl,
            @Value("${identity.password-reset.from:}") String from,
            @Value("${identity.password-reset.smtp-host:}") String host,
            @Value("${identity.password-reset.smtp-port:587}") int port,
            @Value("${identity.password-reset.smtp-user:}") String username,
            @Value("${identity.password-reset.smtp-password:}") String password) {
        this.enabled = enabled; this.from = from; this.resetUrl = resetUrl;
        sender = new JavaMailSenderImpl();
        if (!enabled) return;
        URI uri = URI.create(resetUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !"/reset-password".equals(uri.getPath())
                || host.isBlank() || from.isBlank() || from.contains("\r") || from.contains("\n")
                || username.isBlank() || password.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Password reset requires HTTPS reset URL, sender and authenticated SMTP configuration");
        }
        sender.setHost(host); sender.setPort(port); sender.setUsername(username); sender.setPassword(password);
        var props = sender.getJavaMailProperties();
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.starttls.enable", "true");
        props.setProperty("mail.smtp.starttls.required", "true");
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        props.setProperty("mail.smtp.connectiontimeout", "5000");
        props.setProperty("mail.smtp.timeout", "5000");
        props.setProperty("mail.smtp.writetimeout", "5000");
    }

    public boolean enabled() { return enabled; }
    public void send(String email, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject("Reset your Custoking password");
        // Fragment keeps the bearer secret out of HTTP access logs and referrer URLs.
        message.setText("A password reset was requested for your Custoking account.\n\n"
                + "Open this link within 30 minutes:\n" + resetUrl + "#token=" + token
                + "\n\nIf you did not request this, you can ignore this email. "
                + "Using the link will sign out existing sessions. Custoking will never ask you to share this link.");
        sender.send(message);
    }
}
