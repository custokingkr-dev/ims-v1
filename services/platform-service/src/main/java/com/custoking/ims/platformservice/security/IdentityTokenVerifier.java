package com.custoking.ims.platformservice.security;

import java.util.Optional;

/** Verifies a Google-signed OIDC ID token and yields its {@code email} when signature and audience hold. */
@FunctionalInterface
public interface IdentityTokenVerifier {
    Optional<String> verifiedEmail(String idToken);
}
