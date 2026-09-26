package com.custoking.ims.platformservice.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Separate, explicit pilot gate; the ordinary notification provider remains dry-run. */
@Component
public class LiveBroadcastConfiguration {
    private final boolean enabled, senderVerified;
    private final String channel, webhookToken;
    private final Set<String> schools, destinations;

    public LiveBroadcastConfiguration(
            @Value("${notification.broadcast.live.enabled:false}") boolean enabled,
            @Value("${notification.broadcast.live.channel:EMAIL}") String channel,
            @Value("${notification.broadcast.live.sender-verified:false}") boolean senderVerified,
            @Value("${notification.broadcast.live.school-ids:}") String schools,
            @Value("${notification.broadcast.live.destination-sha256:}") String destinations,
            @Value("${notification.broadcast.live.webhook-token:}") String webhookToken) {
        this.enabled = enabled; this.channel = channel == null ? "" : channel.trim(); this.senderVerified = senderVerified;
        this.schools = entries(schools); this.destinations = entries(destinations);
        this.webhookToken = webhookToken == null ? "" : webhookToken.trim();
    }
    private static Set<String> entries(String value) {
        return value == null ? Set.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    public boolean enabled() { return enabled; }
    public String channel() { return channel; }
    public boolean senderVerified() { return senderVerified; }
    public String webhookToken() { return webhookToken; }
    public boolean configured() {
        return enabled && "EMAIL".equals(channel) && senderVerified && webhookToken.length() >= 32
                && !schools.isEmpty() && schools.size() <= 100 && schools.stream().allMatch(s -> s.matches("[1-9][0-9]{0,17}"))
                && !destinations.isEmpty() && destinations.size() <= 1000 && destinations.stream().allMatch(s -> s.matches("[0-9a-f]{64}"));
    }
    public boolean schoolAllowed(long schoolId) { return configured() && schools.contains(Long.toString(schoolId)); }
    public boolean destinationAllowed(String sha256) { return configured() && destinations.contains(sha256); }
}
