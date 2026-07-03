package com.custoking.ims.platformservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * DTO for POST /notifications/sender-profiles/schools/{schoolId}/whatsapp-onboarding.
 *
 * All fields are optional — the repo inserts NULL for any absent field.
 * actorId is forwarded as the requestedBy parameter; all other fields populate the body map.
 *
 * FORMAT-ONLY constraints (@Email, @Size, @Positive) are skipped automatically when the field
 * is null, so no @NotNull/@NotBlank is used here.
 *
 * Keys forwarded to SenderProfileRepository.requestWhatsappOnboarding(schoolId, actorId, map):
 *   actorId → requestedBy parameter (not in the body map)
 *   schoolName, contactName, contactEmail, contactMobile,
 *   desiredDisplayName, desiredPhoneNumber, notes → body map
 */
public record StartWhatsappOnboardingRequest(
        @Positive Long actorId,
        @Size(max = 200) String schoolName,
        @Size(max = 200) String contactName,
        @Email @Size(max = 255) String contactEmail,
        @Size(max = 20) String contactMobile,
        @Size(max = 200) String desiredDisplayName,
        @Size(max = 20) String desiredPhoneNumber,
        @Size(max = 1000) String notes
) {}
