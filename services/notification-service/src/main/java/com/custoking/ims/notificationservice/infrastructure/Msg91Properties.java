package com.custoking.ims.notificationservice.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "notification.msg91")
public class Msg91Properties {

    private boolean dryRun = true;
    private String authKey = "";
    private Duration timeout = Duration.ofSeconds(10);
    private String smsEndpoint = "https://control.msg91.com/api/v5/flow";
    private String otpEndpoint = "https://control.msg91.com/api/v5/otp";
    private String emailEndpoint = "https://control.msg91.com/api/v5/email/send";
    private String whatsappTemplateEndpoint = "https://control.msg91.com/api/v5/whatsapp/whatsapp-outbound-message/bulk/";
    private String smsFlowId = "";
    private String otpTemplateId = "";
    private String emailFromName = "";
    private String emailFromAddress = "";
    private String emailDomain = "";
    private String whatsappIntegratedNumber = "";
    private String whatsappLanguageCode = "en";

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public String getAuthKey() { return authKey; }
    public void setAuthKey(String authKey) { this.authKey = authKey == null ? "" : authKey.trim(); }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout; }
    public String getSmsEndpoint() { return smsEndpoint; }
    public void setSmsEndpoint(String smsEndpoint) {
        this.smsEndpoint = blankToDefault(smsEndpoint, "https://control.msg91.com/api/v5/flow");
    }
    public String getOtpEndpoint() { return otpEndpoint; }
    public void setOtpEndpoint(String otpEndpoint) {
        this.otpEndpoint = blankToDefault(otpEndpoint, "https://control.msg91.com/api/v5/otp");
    }
    public String getEmailEndpoint() { return emailEndpoint; }
    public void setEmailEndpoint(String emailEndpoint) {
        this.emailEndpoint = blankToDefault(emailEndpoint, "https://control.msg91.com/api/v5/email/send");
    }
    public String getWhatsappTemplateEndpoint() { return whatsappTemplateEndpoint; }
    public void setWhatsappTemplateEndpoint(String whatsappTemplateEndpoint) {
        this.whatsappTemplateEndpoint = blankToDefault(whatsappTemplateEndpoint,
                "https://control.msg91.com/api/v5/whatsapp/whatsapp-outbound-message/bulk/");
    }
    public String getSmsFlowId() { return smsFlowId; }
    public void setSmsFlowId(String smsFlowId) { this.smsFlowId = smsFlowId == null ? "" : smsFlowId.trim(); }
    public String getOtpTemplateId() { return otpTemplateId; }
    public void setOtpTemplateId(String otpTemplateId) {
        this.otpTemplateId = otpTemplateId == null ? "" : otpTemplateId.trim();
    }
    public String getEmailFromName() { return emailFromName; }
    public void setEmailFromName(String emailFromName) {
        this.emailFromName = emailFromName == null ? "" : emailFromName.trim();
    }
    public String getEmailFromAddress() { return emailFromAddress; }
    public void setEmailFromAddress(String emailFromAddress) {
        this.emailFromAddress = emailFromAddress == null ? "" : emailFromAddress.trim();
    }
    public String getEmailDomain() { return emailDomain; }
    public void setEmailDomain(String emailDomain) {
        this.emailDomain = emailDomain == null ? "" : emailDomain.trim();
    }
    public String getWhatsappIntegratedNumber() { return whatsappIntegratedNumber; }
    public void setWhatsappIntegratedNumber(String whatsappIntegratedNumber) {
        this.whatsappIntegratedNumber = whatsappIntegratedNumber == null ? "" : whatsappIntegratedNumber.trim();
    }
    public String getWhatsappLanguageCode() { return whatsappLanguageCode; }
    public void setWhatsappLanguageCode(String whatsappLanguageCode) {
        this.whatsappLanguageCode = whatsappLanguageCode == null || whatsappLanguageCode.isBlank()
                ? "en"
                : whatsappLanguageCode.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
