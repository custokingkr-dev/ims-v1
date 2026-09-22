package com.custoking.ims.platformservice.security;

import com.google.auth.oauth2.TokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticates Pub/Sub push deliveries by the OIDC identity Pub/Sub attaches to them.
 *
 * <p>Cloud Run IAM only proves the caller is <em>some</em> invoker of this service. The API gateway
 * is an invoker too, so IAM alone cannot distinguish a genuine push from a user request the gateway
 * relayed. The push subscriptions are configured with a dedicated push service account and the
 * service URL as audience; this component requires exactly that identity.
 */
@Component
public class PubSubPushAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(PubSubPushAuthenticator.class);
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    /** Verifies a Google-signed ID token and returns its {@code email} claim when signature and audience hold. */
    @FunctionalInterface
    public interface IdentityTokenVerifier {
        Optional<String> verifiedEmail(String idToken);
    }

    private final IdentityTokenVerifier verifier;
    private final Set<String> allowedServiceAccounts;

    @Autowired
    public PubSubPushAuthenticator(
            @Value("${pubsub.push.oidc.audiences:}") String audiences,
            @Value("${pubsub.push.oidc.service-accounts:}") String allowedServiceAccounts) {
        this(googleVerifier(audiences), allowedServiceAccounts);
    }

    public PubSubPushAuthenticator(IdentityTokenVerifier verifier, String allowedServiceAccounts) {
        this(verifier, parseAllowList(allowedServiceAccounts));
    }

    public PubSubPushAuthenticator(IdentityTokenVerifier verifier, Set<String> allowedServiceAccounts) {
        this.verifier = verifier;
        this.allowedServiceAccounts = allowedServiceAccounts.stream()
                .map(PubSubPushAuthenticator::normalise)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requirePushIdentity(String authorizationHeader) {
        if (allowedServiceAccounts.isEmpty()) {
            throw unauthorized("Pub/Sub push identity allow-list is not configured");
        }
        String bearer = bearerToken(authorizationHeader);
        if (bearer == null) {
            throw unauthorized("Missing Pub/Sub push identity");
        }
        String email = verifier.verifiedEmail(bearer).map(PubSubPushAuthenticator::normalise).orElse(null);
        if (email == null) {
            throw unauthorized("Invalid Pub/Sub push identity");
        }
        if (!allowedServiceAccounts.contains(email)) {
            log.warn("pubsub.push.rejected identity={}", email);
            throw unauthorized("Pub/Sub push identity is not a configured push service account");
        }
    }

    private static String bearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }
        String trimmed = authorizationHeader.trim();
        if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static Set<String> parseList(String csv) {
        if (!StringUtils.hasText(csv)) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> parseAllowList(String csv) {
        return parseList(csv);
    }

    /** {@code aud} may be a single string or a list; a Cloud Run service answers to more than one URL form. */
    static boolean audienceAllowed(Object audienceClaim, Set<String> allowedAudiences) {
        if (audienceClaim == null || allowedAudiences.isEmpty()) {
            return false;
        }
        if (audienceClaim instanceof Iterable<?> many) {
            for (Object one : many) {
                if (one != null && allowedAudiences.contains(one.toString())) {
                    return true;
                }
            }
            return false;
        }
        return allowedAudiences.contains(audienceClaim.toString());
    }

    private static IdentityTokenVerifier googleVerifier(String audiencesCsv) {
        Set<String> audiences = parseList(audiencesCsv);
        if (audiences.isEmpty()) {
            // Without a pinned audience any Google-issued token for any service would pass; refuse all.
            return idToken -> Optional.empty();
        }
        // Audience is checked here against the configured set rather than pinned in the verifier.
        TokenVerifier tokenVerifier = TokenVerifier.newBuilder()
                .setIssuer(GOOGLE_ISSUER)
                .build();
        return idToken -> {
            try {
                var payload = tokenVerifier.verify(idToken).getPayload();
                if (!audienceAllowed(payload.getAudience(), audiences)) {
                    return Optional.empty();
                }
                if (!Boolean.TRUE.equals(payload.get("email_verified"))) {
                    return Optional.empty();
                }
                return Optional.ofNullable((String) payload.get("email"));
            } catch (TokenVerifier.VerificationException e) {
                log.debug("pubsub.push.token-invalid reason={}", e.getMessage());
                return Optional.empty();
            }
        };
    }
}
