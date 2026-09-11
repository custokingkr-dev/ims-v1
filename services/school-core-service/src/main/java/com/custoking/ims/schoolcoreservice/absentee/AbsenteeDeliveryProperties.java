package com.custoking.ims.schoolcoreservice.absentee;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code attendance.absentee-delivery.*} — see {@code application.yml} for the environment variables.
 *
 * <p>{@code mode} is {@code dry-run} by default. {@code live} hands rows to platform-service over
 * HTTP and additionally requires {@code platform.base-url} and {@code notification.status.token}
 * (validated at startup by {@link AbsenteeDeliveryConfiguration}).
 */
@ConfigurationProperties(prefix = "attendance.absentee-delivery")
public class AbsenteeDeliveryProperties {

    public static final String MODE_DRY_RUN = "dry-run";
    public static final String MODE_LIVE = "live";

    private String mode = MODE_DRY_RUN;
    private int batchSize = 25;
    private int maxAttempts = 6;
    private Duration initialBackoff = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofMinutes(30);
    private long fixedDelayMs = 15_000;
    private long initialDelayMs = 20_000;
    /** MSG91 WhatsApp template name to send with; blank until the product owner supplies one. */
    private String whatsappTemplateName = "";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode == null ? MODE_DRY_RUN : mode.trim().toLowerCase(); }
    public boolean isLive() { return MODE_LIVE.equals(mode); }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
    public long getFixedDelayMs() { return fixedDelayMs; }
    public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
    public String getWhatsappTemplateName() { return whatsappTemplateName; }
    public void setWhatsappTemplateName(String whatsappTemplateName) {
        this.whatsappTemplateName = whatsappTemplateName == null ? "" : whatsappTemplateName.trim();
    }
}
