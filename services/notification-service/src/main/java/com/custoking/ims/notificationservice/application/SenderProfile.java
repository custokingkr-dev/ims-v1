package com.custoking.ims.notificationservice.application;

public record SenderProfile(
        java.util.UUID id,
        Long schoolId,
        String profileName,
        String emailFromName,
        String emailFromAddress,
        String emailDomain,
        String emailReplyTo,
        String whatsappIntegratedNumber,
        String whatsappDisplayName,
        String whatsappTemplateNamespace,
        String whatsappDefaultTemplateName,
        String whatsappLanguageCode,
        String msg91SmsFlowId,
        String msg91OtpTemplateId,
        String msg91EmailTemplateId
) {
}
