package com.custoking.ims.notificationservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * DTO for PUT /notifications/sender-profiles/default and PUT /notifications/sender-profiles/schools/{schoolId}.
 * Shared across both endpoints.
 *
 * Partial-upsert semantics: all fields nullable. Any non-null field overwrites the stored value;
 * absent (null) fields fall back to the existing stored value, then to repo hard-coded defaults.
 * The repo's value(request, key, existing, fallback) helper drives this logic — missing map keys
 * are treated the same as null, so null-gating each put preserves true partial-upsert behaviour.
 *
 * FORMAT-ONLY constraints (@Email, @Size) — Jakarta Validation skips them automatically when the
 * field is null, so no @NotNull/@NotBlank is needed here.
 *
 * Keys forwarded to SenderProfileRepository.upsert(schoolId, map):
 *   profileName, emailFromName, emailFromAddress, emailDomain, emailReplyTo,
 *   whatsappIntegratedNumber, whatsappDisplayName, whatsappTemplateNamespace,
 *   whatsappDefaultTemplateName, whatsappLanguageCode, msg91SmsFlowId,
 *   msg91OtpTemplateId, msg91EmailTemplateId
 */
public record SenderProfileUpsertRequest(
        @Size(max = 200) String profileName,
        @Size(max = 200) String emailFromName,
        @Email @Size(max = 255) String emailFromAddress,
        @Size(max = 255) String emailDomain,
        @Email @Size(max = 255) String emailReplyTo,
        @Size(max = 20) String whatsappIntegratedNumber,
        @Size(max = 200) String whatsappDisplayName,
        @Size(max = 500) String whatsappTemplateNamespace,
        @Size(max = 200) String whatsappDefaultTemplateName,
        @Size(max = 20) String whatsappLanguageCode,
        @Size(max = 100) String msg91SmsFlowId,
        @Size(max = 100) String msg91OtpTemplateId,
        @Size(max = 100) String msg91EmailTemplateId
) {}
