package com.custoking.ims.platformservice.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CallerIdentityTest {

    @Test
    void audienceCheckAcceptsAnyConfiguredAudienceFormAndRejectsOthers() {
        Set<String> audiences = CallerIdentity.parseList(
                "https://custoking-platform-service-prod-182609177023.asia-south2.run.app, https://custoking-platform-service-prod-abc-em.a.run.app");

        assertThat(CallerIdentity.audienceAllowed(
                "https://custoking-platform-service-prod-182609177023.asia-south2.run.app", audiences)).isTrue();
        assertThat(CallerIdentity.audienceAllowed(
                List.of("https://custoking-platform-service-prod-abc-em.a.run.app"), audiences)).isTrue();
        assertThat(CallerIdentity.audienceAllowed(
                "https://custoking-api-gateway-prod-182609177023.asia-south2.run.app", audiences)).isFalse();
        assertThat(CallerIdentity.audienceAllowed(null, audiences)).isFalse();
        assertThat(CallerIdentity.audienceAllowed("https://anything", Set.of())).isFalse();
    }
}
