package com.custoking.ims.platformservice.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface BroadcastRecipientPolicy {
    boolean configured();
    List<Recipient> resolve(long schoolId, UUID broadcastId, List<String> channels, List<Long> studentIds);

    record Recipient(long schoolId, long studentId, String channel, String eventId, boolean allowed,
            String reason, String guardianId, String destination, String destinationSha256, Map<String, Object> policyEvidence) {}
}
