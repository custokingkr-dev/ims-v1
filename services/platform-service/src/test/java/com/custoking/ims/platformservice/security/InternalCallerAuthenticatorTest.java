package com.custoking.ims.platformservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalCallerAuthenticatorTest {

    private static final String SCHOOL_CORE = "ims-school-core-prod@custoking-prod.iam.gserviceaccount.com";

    private static IdentityTokenVerifier verifierReturning(String email) {
        return idToken -> "valid-token".equals(idToken) ? Optional.ofNullable(email) : Optional.empty();
    }

    @Test
    void whenRequiredAcceptsOnlyAnAllowListedCallerIdentity() {
        InternalCallerAuthenticator authenticator =
                new InternalCallerAuthenticator(verifierReturning(SCHOOL_CORE), true, SCHOOL_CORE);

        assertThatCode(() -> authenticator.requireCaller("Bearer valid-token")).doesNotThrowAnyException();
    }

    @Test
    void whenRequiredRejectsTheGatewayIdentityMissingBearerAndUnverifiableTokens() {
        InternalCallerAuthenticator gatewayCaller = new InternalCallerAuthenticator(
                verifierReturning("ims-api-gateway-prod@custoking-prod.iam.gserviceaccount.com"), true, SCHOOL_CORE);
        InternalCallerAuthenticator authenticator =
                new InternalCallerAuthenticator(verifierReturning(SCHOOL_CORE), true, SCHOOL_CORE);

        assertUnauthorized(() -> gatewayCaller.requireCaller("Bearer valid-token"));
        assertUnauthorized(() -> authenticator.requireCaller(null));
        assertUnauthorized(() -> authenticator.requireCaller("Bearer forged"));
    }

    @Test
    void whenRequiredWithNoAllowListFailsClosed() {
        InternalCallerAuthenticator authenticator = new InternalCallerAuthenticator(verifierReturning(SCHOOL_CORE), true, "");

        assertUnauthorized(() -> authenticator.requireCaller("Bearer valid-token"));
    }

    @Test
    void whenNotRequiredLetsLocalCallersThroughOnTheSharedTokenAlone() {
        InternalCallerAuthenticator authenticator = new InternalCallerAuthenticator(verifierReturning(SCHOOL_CORE), false, "");

        assertThatCode(() -> authenticator.requireCaller(null)).doesNotThrowAnyException();
    }

    private static void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
