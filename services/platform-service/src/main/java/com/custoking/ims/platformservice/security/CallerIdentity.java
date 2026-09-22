package com.custoking.ims.platformservice.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared assertion that a request's OIDC bearer belongs to one of a set of service accounts.
 *
 * <p>Cloud Run IAM only proves the caller is <em>some</em> invoker of this service. The API gateway is
 * an invoker too, so IAM alone cannot tell a service-to-service call from a user request the gateway
 * relayed. Each internal surface therefore names the exact identities allowed to reach it.
 */
final class CallerIdentity {

    private static final Logger log = LoggerFactory.getLogger(CallerIdentity.class);

    private CallerIdentity() {
    }

    static void requireOneOf(IdentityTokenVerifier verifier, String authorizationHeader,
                             Set<String> allowedServiceAccounts, String surface) {
        if (allowedServiceAccounts.isEmpty()) {
            throw unauthorized(surface + " caller allow-list is not configured");
        }
        String bearer = bearerToken(authorizationHeader);
        if (bearer == null) {
            throw unauthorized("Missing " + surface + " caller identity");
        }
        String email = verifier.verifiedEmail(bearer).map(CallerIdentity::normalise).orElse(null);
        if (email == null) {
            throw unauthorized("Invalid " + surface + " caller identity");
        }
        if (!allowedServiceAccounts.contains(email)) {
            log.warn("caller.rejected surface={} identity={}", surface, email);
            throw unauthorized(surface + " caller identity is not an allowed service account");
        }
    }

    static Set<String> normalisedSet(Set<String> values) {
        return values.stream().map(CallerIdentity::normalise).collect(Collectors.toUnmodifiableSet());
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
}
