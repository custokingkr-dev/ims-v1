package com.custoking.ims.schoolcoreservice.absentee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The default gateway. Makes no network call at all — it must be safe before any IAM, URL or
 * template exists — and logs what it <em>would</em> have sent as a structured argument. The row is
 * then marked {@code SENT_DRY_RUN}, never {@code SENT}.
 *
 * <p>No PII in the log line: the destination appears only as its SHA-256 (the same hash the policy
 * evidence carries) and the message text is not logged, mirroring the MSG91 provider's dry-run rule.
 */
public class DryRunAbsenteeDeliveryGateway implements AbsenteeDeliveryGateway {

    private static final Logger log = LoggerFactory.getLogger(DryRunAbsenteeDeliveryGateway.class);
    static final String PROVIDER = "dry-run";

    private final String whatsappTemplateName;

    public DryRunAbsenteeDeliveryGateway(String whatsappTemplateName) {
        this.whatsappTemplateName = whatsappTemplateName == null ? "" : whatsappTemplateName.trim();
    }

    @Override
    public DeliveryOutcome deliver(AbsenteeDeliveryRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", "absentee.delivery.would_send");
        fields.put("notificationId", request.notificationId());
        fields.put("eventId", request.eventId());
        fields.put("schoolId", request.schoolId());
        fields.put("channel", request.channel());
        fields.put("template", PlatformAbsenteeDeliveryGateway.TEMPLATE);
        fields.put("templateName", whatsappTemplateName.isBlank() ? "(unset)" : whatsappTemplateName);
        fields.put("guardianId", request.guardianId());
        fields.put("destinationSha256", DispatchDecision.destinationSha256(request.channel(), request.destination()));
        fields.put("attendanceDate", String.valueOf(request.attendanceDate()));
        fields.put("messageLength", request.message() == null ? 0 : request.message().length());
        log.info("absentee.delivery.would_send {}", kv("absentee", fields));
        return DeliveryOutcome.dryRun(PROVIDER);
    }
}
