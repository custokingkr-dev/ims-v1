package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.Broadcast;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class BroadcastDispatchService {
    public static final String LIVE_BLOCK = "Live delivery is unavailable: the provider has no documented duplicate-suppression contract. Dry-run checks do not send messages.";
    private final BroadcastDispatchRepository repository;
    private final BroadcastRecipientPolicy policy;
    private final NotificationDeliveryCommandService delivery;
    private final String mode;
    private final boolean workerReady;

    public BroadcastDispatchService(BroadcastDispatchRepository repository, BroadcastRecipientPolicy policy,
            NotificationDeliveryCommandService delivery, @Value("${notification.broadcast.mode:OFF}") String mode,
            @Value("${notification.broadcast.worker-ready:false}") boolean workerReady) {
        this.repository = repository; this.policy = policy; this.delivery = delivery;
        this.mode = "DRY_RUN".equalsIgnoreCase(mode) ? "DRY_RUN" : "OFF";
        this.workerReady = workerReady;
    }

    public Map<String, Object> capabilities(boolean manager) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canCreateDraft", manager); result.put("canApprove", manager && policy.configured());
        result.put("canPreview", manager && policy.configured()); result.put("canSend", false);
        result.put("canQueue", manager && queueEnabled()); result.put("mode", mode);
        result.put("sendUnavailableReason", LIVE_BLOCK);
        result.put("queueUnavailableReason", !policy.configured() ? "The school recipient-policy connection is not configured."
                : !"DRY_RUN".equals(mode) ? "Broadcast processing is off. An administrator must configure a non-production dry run."
                : !workerReady ? "The request-driven delivery-check worker has not been verified. An administrator must verify its authenticated schedule."
                : !delivery.dryRun() ? LIVE_BLOCK : "");
        result.put("supportedAudiences", List.of("ALL_PARENTS")); result.put("supportedChannels", List.of("SMS", "EMAIL", "WHATSAPP"));
        result.put("supportedCategories", List.of("SCHOOL_NOTICE")); return result;
    }

    public boolean queueEnabled() { return "DRY_RUN".equals(mode) && workerReady && policy.configured() && delivery.dryRun(); }

    public Map<String, Object> preview(UUID id) {
        Broadcast broadcast = supported(repository.find(id, false));
        List<Recipient> recipients = policy.resolve(broadcast.schoolId(), id, broadcast.channels(), null);
        return summarize(broadcast, recipients);
    }

    @Transactional
    public void approve(UUID id, Long actorId, String previewFingerprint) {
        Broadcast broadcast = supported(repository.find(id, true));
        if (!"DRAFT".equals(broadcast.status())) return; // replay preserves the already-approved manifest
        List<Recipient> recipients = policy.resolve(broadcast.schoolId(), id, broadcast.channels(), null);
        Map<String, Object> preview = summarize(broadcast, recipients);
        if (previewFingerprint == null || !previewFingerprint.equals(preview.get("fingerprint"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recipient eligibility changed or was not reviewed. Preview the audience again before approving.");
        }
        if ((long) preview.get("eligible") == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "No recipients currently meet the communication policy");
        repository.approve(broadcast, recipients, actorId);
    }

    @Transactional
    public Map<String, Object> queue(UUID id, Long actorId) {
        Broadcast broadcast = supported(repository.find(id, true));
        if (!queueEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, String.valueOf(capabilities(true).get("queueUnavailableReason")));
        if (List.of("QUEUED", "DRY_RUN_COMPLETE", "COMPLETED_WITH_FAILURES").contains(broadcast.status())) return repository.outcomes(broadcast);
        if (!"APPROVED".equals(broadcast.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Preview and approve the recipient list before queuing");
        repository.queue(broadcast, mode, actorId);
        return repository.outcomes(repository.find(id, false));
    }

    public Map<String, Object> outcomes(UUID id) { return repository.outcomes(repository.find(id, false)); }

    @Transactional
    public Map<String, Object> retry(UUID id) {
        Broadcast broadcast = supported(repository.find(id, true));
        if (!queueEnabled() || !"DRY_RUN".equals(broadcast.mode())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Dry-run processing is not available for this broadcast");
        repository.retry(broadcast); // Same recipient/event IDs; terminal outcomes are never reset.
        return repository.outcomes(broadcast);
    }

    private Broadcast supported(Broadcast broadcast) {
        if (broadcast.schoolId() == null || broadcast.schoolId() <= 0 || !"SCHOOL_NOTICE".equals(broadcast.communicationCategory())
                || !"ALL_PARENTS".equals(broadcast.audienceType()) || broadcast.channels().isEmpty()
                || broadcast.channels().stream().anyMatch(channel -> !List.of("SMS", "EMAIL", "WHATSAPP").contains(channel))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Create a school-scoped school notice for primary guardians using SMS, email or WhatsApp. This draft lacks supported recipient policy details.");
        }
        if (!policy.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "School recipient policy service is not configured");
        return broadcast;
    }

    static Map<String, Object> summarize(Broadcast broadcast, List<Recipient> recipients) {
        Set<String> destinations = new HashSet<>();
        Map<String, Long> reasons = new TreeMap<>();
        long eligible = 0, duplicate = 0, suppressed = 0;
        List<String> fingerprints = new ArrayList<>();
        for (Recipient recipient : recipients) {
            if (!recipient.allowed()) { suppressed++; reasons.merge(recipient.reason(), 1L, Long::sum); }
            else if (!destinations.add(recipient.channel() + ":" + recipient.destinationSha256())) duplicate++;
            else eligible++;
            fingerprints.add(recipient.eventId() + "|" + recipient.allowed() + "|" + recipient.reason() + "|" + recipient.guardianId() + "|" + recipient.destinationSha256()
                    + "|" + (recipient.policyEvidence() == null ? "" : recipient.policyEvidence().get("consentEventId")));
        }
        Collections.sort(fingerprints);
        String canonical = broadcast.id() + "|" + broadcast.schoolId() + "|" + broadcast.title() + "|" + broadcast.message() + "|" + broadcast.communicationCategory() + "|" + String.join("\n", fingerprints);
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return Map.of("broadcastId", broadcast.id(), "total", recipients.size(), "eligible", eligible, "suppressed", suppressed,
                    "duplicate", duplicate, "reasons", reasons, "fingerprint", fingerprint,
                    "explanation", "One notice per eligible destination and channel. Consent, preference and contact binding are checked again before every attempt.");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
