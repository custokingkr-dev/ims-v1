package com.custoking.ims.platformservice.security;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PubSubPushAuthenticatorTest {

    private static final String PUSH_SA = "ims-reporting-push-prod@custoking-prod.iam.gserviceaccount.com";

    private static PubSubPushAuthenticator.IdentityTokenVerifier verifierReturning(String email) {
        return idToken -> "valid-token".equals(idToken) ? Optional.ofNullable(email) : Optional.empty();
    }

    @Test
    void acceptsBearerWhoseVerifiedEmailIsAnAllowListedPushServiceAccount() {
        PubSubPushAuthenticator authenticator =
                new PubSubPushAuthenticator(verifierReturning(PUSH_SA), Set.of(PUSH_SA));

        assertThatCode(() -> authenticator.requirePushIdentity("Bearer valid-token")).doesNotThrowAnyException();
    }

    @Test
    void rejectsRequestWithoutBearer() {
        PubSubPushAuthenticator authenticator =
                new PubSubPushAuthenticator(verifierReturning(PUSH_SA), Set.of(PUSH_SA));

        assertUnauthorized(() -> authenticator.requirePushIdentity(null));
        assertUnauthorized(() -> authenticator.requirePushIdentity("Basic abc"));
    }

    @Test
    void rejectsBearerThatDoesNotVerify() {
        PubSubPushAuthenticator authenticator =
                new PubSubPushAuthenticator(verifierReturning(PUSH_SA), Set.of(PUSH_SA));

        assertUnauthorized(() -> authenticator.requirePushIdentity("Bearer forged-token"));
    }

    @Test
    void rejectsVerifiedIdentityThatIsNotAPushServiceAccount() {
        // The API gateway's own runtime identity is a Cloud Run invoker too; it must never pass.
        PubSubPushAuthenticator authenticator = new PubSubPushAuthenticator(
                verifierReturning("ims-api-gateway-prod@custoking-prod.iam.gserviceaccount.com"), Set.of(PUSH_SA));

        assertUnauthorized(() -> authenticator.requirePushIdentity("Bearer valid-token"));
    }

    @Test
    void failsClosedWhenNoPushServiceAccountIsConfigured() {
        PubSubPushAuthenticator authenticator = new PubSubPushAuthenticator(verifierReturning(PUSH_SA), Set.of());

        assertUnauthorized(() -> authenticator.requirePushIdentity("Bearer valid-token"));
    }

    @Test
    void parsesCommaSeparatedAllowListIgnoringCaseAndWhitespace() {
        PubSubPushAuthenticator authenticator = new PubSubPushAuthenticator(
                verifierReturning(PUSH_SA.toUpperCase()), " other@x.iam.gserviceaccount.com , " + PUSH_SA + " ");

        assertThatCode(() -> authenticator.requirePushIdentity("Bearer valid-token")).doesNotThrowAnyException();
    }

    @Test
    void audienceCheckAcceptsAnyConfiguredAudienceFormAndRejectsOthers() {
        Set<String> audiences = PubSubPushAuthenticator.parseList(
                "https://custoking-platform-service-prod-182609177023.asia-south2.run.app, https://custoking-platform-service-prod-abc-em.a.run.app");

        assertThat(PubSubPushAuthenticator.audienceAllowed(
                "https://custoking-platform-service-prod-182609177023.asia-south2.run.app", audiences)).isTrue();
        assertThat(PubSubPushAuthenticator.audienceAllowed(
                java.util.List.of("https://custoking-platform-service-prod-abc-em.a.run.app"), audiences)).isTrue();
        assertThat(PubSubPushAuthenticator.audienceAllowed(
                "https://custoking-api-gateway-prod-182609177023.asia-south2.run.app", audiences)).isFalse();
        assertThat(PubSubPushAuthenticator.audienceAllowed(null, audiences)).isFalse();
        assertThat(PubSubPushAuthenticator.audienceAllowed("https://anything", Set.of())).isFalse();
    }

    private static void assertUnauthorized(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
