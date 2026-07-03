package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.application.SenderProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SenderProfileRepository {

    private final JdbcClient jdbc;

    public SenderProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SenderProfile resolve(Long schoolId) {
        SenderProfile defaultProfile = defaultProfile();
        if (schoolId != null) {
            Optional<SenderProfile> schoolProfile = findBySchoolId(schoolId);
            if (schoolProfile.isPresent()) {
                return merge(schoolProfile.get(), defaultProfile);
            }
        }
        return defaultProfile;
    }

    public SenderProfile defaultProfile() {
        return jdbc.sql("""
                        SELECT *
                        FROM notification.notification_sender_profiles
                        WHERE school_id IS NULL AND active = TRUE
                        ORDER BY created_at DESC
                        LIMIT 1
                        """)
                .query(this::mapProfile)
                .optional()
                .orElseGet(this::propertyBackedDefault);
    }

    public Optional<SenderProfile> findBySchoolId(Long schoolId) {
        return jdbc.sql("""
                        SELECT *
                        FROM notification.notification_sender_profiles
                        WHERE school_id = :schoolId AND active = TRUE
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """)
                .param("schoolId", schoolId)
                .query(this::mapProfile)
                .optional();
    }

    public List<SenderProfile> list(Long schoolId) {
        String sql = schoolId == null
                ? """
                  SELECT *
                  FROM notification.notification_sender_profiles
                  WHERE active = TRUE
                  ORDER BY school_id NULLS FIRST, profile_name
                  """
                : """
                  SELECT *
                  FROM notification.notification_sender_profiles
                  WHERE active = TRUE AND (school_id = :schoolId OR school_id IS NULL)
                  ORDER BY school_id NULLS FIRST, profile_name
                  """;
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (schoolId != null) {
            spec = spec.param("schoolId", schoolId);
        }
        return spec.query(this::mapProfile).list();
    }

    public SenderProfile upsert(Long schoolId, Map<String, Object> request) {
        UUID id = findProfileId(schoolId).orElse(UUID.randomUUID());
        SenderProfile existing = schoolId == null
                ? defaultProfile()
                : findBySchoolId(schoolId).orElse(defaultProfile());
        jdbc.sql("""
                        INSERT INTO notification.notification_sender_profiles (
                            id, school_id, profile_name, email_from_name, email_from_address, email_domain, email_reply_to,
                            whatsapp_integrated_number, whatsapp_display_name, whatsapp_template_namespace,
                            whatsapp_default_template_name, whatsapp_language_code, msg91_sms_flow_id,
                            msg91_otp_template_id, msg91_email_template_id, active, created_at, updated_at
                        ) VALUES (
                            :id, :schoolId, :profileName, :emailFromName, :emailFromAddress, :emailDomain, :emailReplyTo,
                            :whatsappIntegratedNumber, :whatsappDisplayName, :whatsappTemplateNamespace,
                            :whatsappDefaultTemplateName, :whatsappLanguageCode, :msg91SmsFlowId,
                            :msg91OtpTemplateId, :msg91EmailTemplateId, TRUE, now(), now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            profile_name = EXCLUDED.profile_name,
                            email_from_name = EXCLUDED.email_from_name,
                            email_from_address = EXCLUDED.email_from_address,
                            email_domain = EXCLUDED.email_domain,
                            email_reply_to = EXCLUDED.email_reply_to,
                            whatsapp_integrated_number = EXCLUDED.whatsapp_integrated_number,
                            whatsapp_display_name = EXCLUDED.whatsapp_display_name,
                            whatsapp_template_namespace = EXCLUDED.whatsapp_template_namespace,
                            whatsapp_default_template_name = EXCLUDED.whatsapp_default_template_name,
                            whatsapp_language_code = EXCLUDED.whatsapp_language_code,
                            msg91_sms_flow_id = EXCLUDED.msg91_sms_flow_id,
                            msg91_otp_template_id = EXCLUDED.msg91_otp_template_id,
                            msg91_email_template_id = EXCLUDED.msg91_email_template_id,
                            active = TRUE,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("profileName", value(request, "profileName", existing.profileName(),
                        schoolId == null ? "Custoking Platform Default" : "School Sender Profile"))
                .param("emailFromName", value(request, "emailFromName", existing.emailFromName(), null))
                .param("emailFromAddress", value(request, "emailFromAddress", existing.emailFromAddress(), null))
                .param("emailDomain", value(request, "emailDomain", existing.emailDomain(), null))
                .param("emailReplyTo", value(request, "emailReplyTo", existing.emailReplyTo(), null))
                .param("whatsappIntegratedNumber", digits(value(request, "whatsappIntegratedNumber",
                        existing.whatsappIntegratedNumber(), null)))
                .param("whatsappDisplayName", value(request, "whatsappDisplayName", existing.whatsappDisplayName(), null))
                .param("whatsappTemplateNamespace", value(request, "whatsappTemplateNamespace",
                        existing.whatsappTemplateNamespace(), null))
                .param("whatsappDefaultTemplateName", value(request, "whatsappDefaultTemplateName",
                        existing.whatsappDefaultTemplateName(), null))
                .param("whatsappLanguageCode", value(request, "whatsappLanguageCode",
                        existing.whatsappLanguageCode(), "en"))
                .param("msg91SmsFlowId", value(request, "msg91SmsFlowId", existing.msg91SmsFlowId(), null))
                .param("msg91OtpTemplateId", value(request, "msg91OtpTemplateId", existing.msg91OtpTemplateId(), null))
                .param("msg91EmailTemplateId", value(request, "msg91EmailTemplateId",
                        existing.msg91EmailTemplateId(), null))
                .update();
        return schoolId == null ? defaultProfile() : findBySchoolId(schoolId).orElseThrow();
    }

    public Map<String, Object> requestWhatsappOnboarding(Long schoolId, Long actorId, Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO notification.whatsapp_onboarding_sessions (
                            id, school_id, requested_by, school_name, contact_name, contact_email, contact_mobile,
                            desired_display_name, desired_phone_number, notes, status, requested_at, updated_at
                        ) VALUES (
                            :id, :schoolId, :requestedBy, :schoolName, :contactName, :contactEmail, :contactMobile,
                            :desiredDisplayName, :desiredPhoneNumber, :notes, 'REQUESTED', now(), now()
                        )
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("requestedBy", actorId)
                .param("schoolName", text(request, "schoolName"))
                .param("contactName", text(request, "contactName"))
                .param("contactEmail", text(request, "contactEmail"))
                .param("contactMobile", digits(text(request, "contactMobile")))
                .param("desiredDisplayName", text(request, "desiredDisplayName"))
                .param("desiredPhoneNumber", digits(text(request, "desiredPhoneNumber")))
                .param("notes", text(request, "notes"))
                .update();
        return onboarding(id).orElseThrow();
    }

    public Map<String, Object> completeWhatsappOnboarding(Long schoolId, UUID sessionId, Map<String, Object> request) {
        String integratedNumber = required(request, "integratedNumber", null);
        jdbc.sql("""
                        UPDATE notification.whatsapp_onboarding_sessions
                        SET status = 'COMPLETED',
                            provider_reference = :providerReference,
                            integrated_number = :integratedNumber,
                            failure_reason = NULL,
                            updated_at = now(),
                            completed_at = now()
                        WHERE id = :id AND school_id = :schoolId
                        """)
                .param("id", sessionId)
                .param("schoolId", schoolId)
                .param("providerReference", text(request, "providerReference"))
                .param("integratedNumber", digits(integratedNumber))
                .update();
        Map<String, Object> profileUpdate = new LinkedHashMap<>(request);
        profileUpdate.put("whatsappIntegratedNumber", integratedNumber);
        if (profileUpdate.get("profileName") == null) {
            profileUpdate.put("profileName", "School Sender Profile");
        }
        upsert(schoolId, profileUpdate);
        return onboarding(sessionId).orElseThrow();
    }

    public Map<String, Object> failWhatsappOnboarding(Long schoolId, UUID sessionId, Map<String, Object> request) {
        jdbc.sql("""
                        UPDATE notification.whatsapp_onboarding_sessions
                        SET status = 'FAILED',
                            provider_reference = :providerReference,
                            failure_reason = :failureReason,
                            updated_at = now()
                        WHERE id = :id AND school_id = :schoolId
                        """)
                .param("id", sessionId)
                .param("schoolId", schoolId)
                .param("providerReference", text(request, "providerReference"))
                .param("failureReason", required(request, "failureReason", "Onboarding failed"))
                .update();
        return onboarding(sessionId).orElseThrow();
    }

    public List<Map<String, Object>> onboardingSessions(Long schoolId) {
        return jdbc.sql("""
                        SELECT *
                        FROM notification.whatsapp_onboarding_sessions
                        WHERE school_id = :schoolId
                        ORDER BY requested_at DESC
                        """)
                .param("schoolId", schoolId)
                .query(this::mapOnboarding)
                .list();
    }

    private Optional<Map<String, Object>> onboarding(UUID id) {
        return jdbc.sql("SELECT * FROM notification.whatsapp_onboarding_sessions WHERE id = :id")
                .param("id", id)
                .query(this::mapOnboarding)
                .optional();
    }

    private Optional<UUID> findProfileId(Long schoolId) {
        String predicate = schoolId == null ? "school_id IS NULL" : "school_id = :schoolId";
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        SELECT id
                        FROM notification.notification_sender_profiles
                        WHERE active = TRUE AND
                        """ + predicate + """

                        ORDER BY updated_at DESC
                        LIMIT 1
                        """);
        if (schoolId != null) {
            spec = spec.param("schoolId", schoolId);
        }
        return spec.query(UUID.class).optional();
    }

    private SenderProfile mapProfile(ResultSet rs, int rowNum) throws SQLException {
        return new SenderProfile(
                rs.getObject("id", UUID.class),
                rs.getObject("school_id") == null ? null : rs.getLong("school_id"),
                rs.getString("profile_name"),
                rs.getString("email_from_name"),
                rs.getString("email_from_address"),
                rs.getString("email_domain"),
                rs.getString("email_reply_to"),
                rs.getString("whatsapp_integrated_number"),
                rs.getString("whatsapp_display_name"),
                rs.getString("whatsapp_template_namespace"),
                rs.getString("whatsapp_default_template_name"),
                rs.getString("whatsapp_language_code"),
                rs.getString("msg91_sms_flow_id"),
                rs.getString("msg91_otp_template_id"),
                rs.getString("msg91_email_template_id"));
    }

    private Map<String, Object> mapOnboarding(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id", UUID.class));
        row.put("schoolId", rs.getLong("school_id"));
        row.put("provider", rs.getString("provider"));
        row.put("status", rs.getString("status"));
        row.put("requestedBy", rs.getObject("requested_by"));
        row.put("schoolName", rs.getString("school_name"));
        row.put("contactName", rs.getString("contact_name"));
        row.put("contactEmail", rs.getString("contact_email"));
        row.put("contactMobile", rs.getString("contact_mobile"));
        row.put("desiredDisplayName", rs.getString("desired_display_name"));
        row.put("desiredPhoneNumber", rs.getString("desired_phone_number"));
        row.put("notes", rs.getString("notes"));
        row.put("providerReference", rs.getString("provider_reference"));
        row.put("integratedNumber", rs.getString("integrated_number"));
        row.put("failureReason", rs.getString("failure_reason"));
        row.put("requestedAt", rs.getObject("requested_at", OffsetDateTime.class));
        row.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
        row.put("completedAt", rs.getObject("completed_at", OffsetDateTime.class));
        return row;
    }

    private SenderProfile propertyBackedDefault() {
        return new SenderProfile(null, null, "Custoking Platform Default", "Custoking Support",
                "support@custoking.com", "custoking.com", null, null, "Custoking", null, null, "en",
                null, null, null);
    }

    private SenderProfile merge(SenderProfile school, SenderProfile fallback) {
        return new SenderProfile(
                school.id(),
                school.schoolId(),
                first(school.profileName(), fallback.profileName()),
                first(school.emailFromName(), fallback.emailFromName()),
                first(school.emailFromAddress(), fallback.emailFromAddress()),
                first(school.emailDomain(), fallback.emailDomain()),
                first(school.emailReplyTo(), fallback.emailReplyTo()),
                first(school.whatsappIntegratedNumber(), fallback.whatsappIntegratedNumber()),
                first(school.whatsappDisplayName(), fallback.whatsappDisplayName()),
                first(school.whatsappTemplateNamespace(), fallback.whatsappTemplateNamespace()),
                first(school.whatsappDefaultTemplateName(), fallback.whatsappDefaultTemplateName()),
                first(school.whatsappLanguageCode(), fallback.whatsappLanguageCode()),
                first(school.msg91SmsFlowId(), fallback.msg91SmsFlowId()),
                first(school.msg91OtpTemplateId(), fallback.msg91OtpTemplateId()),
                first(school.msg91EmailTemplateId(), fallback.msg91EmailTemplateId()));
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(Map<String, Object> request, String key, String fallback) {
        String value = text(request, key);
        if (value == null || value.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static String value(Map<String, Object> request, String key, String existing, String fallback) {
        String value = text(request, key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return fallback;
    }

    private static String text(Map<String, Object> request, String key) {
        if (request == null || request.get(key) == null) {
            return null;
        }
        String value = String.valueOf(request.get(key)).trim();
        return value.isBlank() ? null : value;
    }

    private static String digits(String value) {
        return value == null ? null : value.replaceAll("[^0-9]", "");
    }
}
