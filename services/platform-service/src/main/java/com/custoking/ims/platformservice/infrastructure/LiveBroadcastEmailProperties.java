package com.custoking.ims.platformservice.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** An explicitly verified fixed sender/template; never selected by broadcast input. */
@ConfigurationProperties(prefix = "notification.broadcast.live.email")
public class LiveBroadcastEmailProperties {
    private String senderEmail = "", senderName = "", domain = "", templateId = "";
    private boolean templateVerified;

    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String value) { senderEmail = trim(value); }
    public String getSenderName() { return senderName; }
    public void setSenderName(String value) { senderName = trim(value); }
    public String getDomain() { return domain; }
    public void setDomain(String value) { domain = trim(value); }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String value) { templateId = trim(value); }
    public boolean isTemplateVerified() { return templateVerified; }
    public void setTemplateVerified(boolean value) { templateVerified = value; }
    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
