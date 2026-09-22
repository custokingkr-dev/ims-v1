package com.custoking.ims.platformservice.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Authenticates service-to-service calls to {@code /api/v1/internal/**} by the caller's Cloud Run
 * OIDC identity, in addition to the shared route token.
 *
 * <p>The shared token is also carried by the API gateway, so on its own it cannot prove that a
 * request originated from another backend. When {@code internal.oidc.require} is on, the bearer
 * must resolve to one of the configured runtime service accounts. It is off by default so local
 * and direct deployments without Cloud Run identities keep working on the shared token alone.
 */
@Component
public class InternalCallerAuthenticator {

    private final IdentityTokenVerifier verifier;
    private final boolean required;
    private final Set<String> allowedServiceAccounts;

    @Autowired
    public InternalCallerAuthenticator(
            GoogleIdentityTokenVerifier verifier,
            @Value("${internal.oidc.require:false}") boolean required,
            @Value("${internal.oidc.service-accounts:}") String allowedServiceAccounts) {
        this((IdentityTokenVerifier) verifier, required, allowedServiceAccounts);
    }

    public InternalCallerAuthenticator(IdentityTokenVerifier verifier, boolean required, String allowedServiceAccounts) {
        this.verifier = verifier;
        this.required = required;
        this.allowedServiceAccounts = CallerIdentity.normalisedSet(CallerIdentity.parseList(allowedServiceAccounts));
    }

    /** A permissive instance for wiring that has not been given caller identities (tests, local runs). */
    public static InternalCallerAuthenticator sharedTokenOnly() {
        return new InternalCallerAuthenticator(idToken -> Optional.empty(), false, "");
    }

    public void requireCaller(String authorizationHeader) {
        if (!required) {
            return;
        }
        CallerIdentity.requireOneOf(verifier, authorizationHeader, allowedServiceAccounts, "internal");
    }
}
