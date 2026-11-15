package com.custoking.ims.platformservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for POST /notifications/sender-profiles/schools/{schoolId}/whatsapp-onboarding/{sessionId}/complete.
 * Maps to SenderProfileRepository.completeWhatsappOnboarding(schoolId, sessionId, Map) keys:
 *   required: integratedNumber (no fallback in repo)
 *   optional: providerReference, plus sender-profile fields forwarded to the internal upsert call
 *             (profileName, emailFromName, emailFromAddress, emailDomain, emailReplyTo,
 *              whatsappDisplayName, whatsappTemplateNamespace, whatsappDefaultTemplateName,
 *              whatsappLanguageCode, msg91SmsFlowId, msg91OtpTemplateId, msg91EmailTemplateId)
 * Each bound is its column's width (notification V6/V7), so a value too long to store is a 400 naming the field,
 * never a 500 from the database.
 */
public record CompleteWhatsappOnboardingRequest(
        @NotBlank(message = "integratedNumber is required") @Size(max = 40) String integratedNumber,
        @Size(max = 220) String providerReference,
        @Size(max = 160) String profileName,
        @Size(max = 160) String emailFromName,
        @Email @Size(max = 320) String emailFromAddress,
        @Size(max = 255) String emailDomain,
        @Email @Size(max = 320) String emailReplyTo,
        @Size(max = 160) String whatsappDisplayName,
        @Size(max = 160) String whatsappTemplateNamespace,
        @Size(max = 160) String whatsappDefaultTemplateName,
        @Size(max = 20) String whatsappLanguageCode,
        @Size(max = 160) String msg91SmsFlowId,
        @Size(max = 160) String msg91OtpTemplateId,
        @Size(max = 160) String msg91EmailTemplateId
) {}
