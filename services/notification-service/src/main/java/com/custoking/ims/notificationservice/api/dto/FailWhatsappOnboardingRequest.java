package com.custoking.ims.notificationservice.api.dto;

import jakarta.validation.constraints.Size;

/**
 * DTO for POST /notifications/sender-profiles/schools/{schoolId}/whatsapp-onboarding/{sessionId}/fail.
 *
 * Both fields are optional:
 *   failureReason — the repo's required(request, "failureReason", "Onboarding failed") supplies a
 *                   DEFAULT fallback when the key is absent, so the field is truly optional here.
 *   providerReference — the repo uses text(request, "providerReference") which returns null when
 *                       absent; that null is written directly to the column.
 *
 * FORMAT-ONLY @Size constraints are skipped automatically when the field is null.
 * No @NotNull/@NotBlank annotations are used here.
 *
 * Keys forwarded to SenderProfileRepository.failWhatsappOnboarding(schoolId, sessionId, map):
 *   failureReason, providerReference
 */
public record FailWhatsappOnboardingRequest(
        @Size(max = 1000) String failureReason,
        @Size(max = 500) String providerReference
) {}
