package com.custoking.ims.notificationservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /notifications/sender-profiles/schools/{schoolId}/whatsapp-onboarding/{sessionId}/complete.
 * Maps to SenderProfileRepository.completeWhatsappOnboarding(schoolId, sessionId, Map) keys:
 *   required: integratedNumber (no fallback in repo)
 *   optional: providerReference, plus sender-profile fields forwarded to the internal upsert call
 *             (profileName, emailFromName, emailFromAddress, emailDomain, emailReplyTo,
 *              whatsappDisplayName, whatsappTemplateNamespace, whatsappDefaultTemplateName,
 *              whatsappLanguageCode, msg91SmsFlowId, msg91OtpTemplateId, msg91EmailTemplateId)
 */
public record CompleteWhatsappOnboardingRequest(
        @NotBlank(message = "integratedNumber is required") String integratedNumber,
        String providerReference,
        String profileName,
        String emailFromName,
        String emailFromAddress,
        String emailDomain,
        String emailReplyTo,
        String whatsappDisplayName,
        String whatsappTemplateNamespace,
        String whatsappDefaultTemplateName,
        String whatsappLanguageCode,
        String msg91SmsFlowId,
        String msg91OtpTemplateId,
        String msg91EmailTemplateId
) {}
