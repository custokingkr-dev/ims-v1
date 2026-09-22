package com.custoking.ims.platformservice.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Authenticates Pub/Sub push deliveries by the OIDC identity Pub/Sub attaches to them: the push
 * subscriptions are configured with a dedicated push service account and this service's URL as
 * audience, and only those identities may reach the push ingest endpoints.
 */
@Component
public class PubSubPushAuthenticator {

    private final IdentityTokenVerifier verifier;
    private final Set<String> allowedServiceAccounts;

    @Autowired
    public PubSubPushAuthenticator(
            GoogleIdentityTokenVerifier verifier,
            @Value("${pubsub.push.oidc.service-accounts:}") String allowedServiceAccounts) {
        this((IdentityTokenVerifier) verifier, allowedServiceAccounts);
    }

    public PubSubPushAuthenticator(IdentityTokenVerifier verifier, String allowedServiceAccounts) {
        this(verifier, CallerIdentity.parseList(allowedServiceAccounts));
    }

    public PubSubPushAuthenticator(IdentityTokenVerifier verifier, Set<String> allowedServiceAccounts) {
        this.verifier = verifier;
        this.allowedServiceAccounts = CallerIdentity.normalisedSet(allowedServiceAccounts);
    }

    public void requirePushIdentity(String authorizationHeader) {
        CallerIdentity.requireOneOf(verifier, authorizationHeader, allowedServiceAccounts, "Pub/Sub push");
    }
}
