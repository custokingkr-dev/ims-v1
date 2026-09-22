package com.custoking.ims.platformservice.security;

import com.google.auth.oauth2.TokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Verifies Cloud Run / Pub/Sub OIDC bearers against Google's signing keys and this service's own URLs.
 *
 * <p>A Cloud Run service answers to more than one URL form, so the audience is checked against a
 * configured set rather than a single pinned value. With no audience configured every token is
 * refused: an unpinned audience would accept any Google-issued token minted for any service.
 */
@Component
public class GoogleIdentityTokenVerifier implements IdentityTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdentityTokenVerifier.class);
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    private final Set<String> audiences;
    private final TokenVerifier tokenVerifier;

    public GoogleIdentityTokenVerifier(@Value("${oidc.audiences:}") String audiencesCsv) {
        this.audiences = CallerIdentity.parseList(audiencesCsv);
        this.tokenVerifier = TokenVerifier.newBuilder().setIssuer(GOOGLE_ISSUER).build();
    }

    @Override
    public Optional<String> verifiedEmail(String idToken) {
        if (audiences.isEmpty()) {
            return Optional.empty();
        }
        try {
            var payload = tokenVerifier.verify(idToken).getPayload();
            if (!CallerIdentity.audienceAllowed(payload.getAudience(), audiences)) {
                return Optional.empty();
            }
            if (!Boolean.TRUE.equals(payload.get("email_verified"))) {
                return Optional.empty();
            }
            return Optional.ofNullable((String) payload.get("email"));
        } catch (TokenVerifier.VerificationException e) {
            log.debug("oidc.token-invalid reason={}", e.getMessage());
            return Optional.empty();
        }
    }
}
