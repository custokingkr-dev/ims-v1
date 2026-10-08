package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.security.IdentityTokenVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Existing dedicated internal report purpose; this authenticates an operator, never the vendor. */
public final class NotificationReportAuthority {
    private final IdentityTokenVerifier identities;
    private final boolean enabled;
    private final String token, sharedToken, providerToken;
    private final Set<String> callers, forbidden;
    public NotificationReportAuthority(IdentityTokenVerifier identities, boolean enabled, String token,
            String sharedToken, String providerToken, String callers, String forbidden) {
        this.identities=identities; this.enabled=enabled; this.token=value(token);
        this.sharedToken=value(sharedToken); this.providerToken=value(providerToken);
        this.callers=accounts(callers); this.forbidden=accounts(forbidden);
    }
    public VerifiedReporter verify(String authorization, String supplied) {
        if(!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification report reconciliation is unavailable");
        int length=token.getBytes(StandardCharsets.UTF_8).length;
        if(length<32 || length>512 || token.isBlank() || equal(token,sharedToken) || equal(token,providerToken)
                || callers.isEmpty() || !Collections.disjoint(callers,forbidden) || !equal(token,supplied)) deny();
        String principal=authorization!=null && authorization.startsWith("Bearer ")
                ? identities.verifiedEmail(authorization.substring(7)).orElse(null):null;
        if(principal==null || !callers.contains(principal.toLowerCase(Locale.ROOT))) deny();
        return new VerifiedReporter(principal.toLowerCase(Locale.ROOT));
    }
    public static final class VerifiedReporter {
        private final String principal;
        private VerifiedReporter(String principal) { this.principal=principal; }
        public String principal() { return principal; }
        @Override public String toString() { return "VerifiedReporter[purpose=notification:report-reconcile, principal=REDACTED]"; }
    }
    private static void deny() { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid notification report authority"); }
    private static boolean equal(String expected,String supplied) {
        return supplied!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));
    }
    private static String value(String value) { return value==null?"":value; }
    private static Set<String> accounts(String input) {
        return Arrays.stream(value(input).split(",")).map(String::trim).filter(v->!v.isEmpty())
                .map(v->v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
