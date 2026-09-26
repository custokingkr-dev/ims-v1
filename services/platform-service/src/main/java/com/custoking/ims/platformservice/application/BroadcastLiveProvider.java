package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;

/** A single submission attempt. ACCEPTED means queued by the provider, never delivered. */
public interface BroadcastLiveProvider {
    boolean configured();
    Prepared prepare(BroadcastDispatchRepository.QueuedRecipient row, BroadcastRecipientPolicy.Recipient decision);
    Result submit(Prepared prepared);

    record Prepared(String channel, String correlationId, String destinationSha256, String requestSha256,
                    String senderSha256, String body) {
        // This in-memory value contains personal data. Only its hashes belong in the durable ledger.
        @Override public String toString() { return "Prepared[channel=" + channel + ", body=REDACTED]"; }
    }

    record Result(String status, String providerMessageId, String reason) {}
}
