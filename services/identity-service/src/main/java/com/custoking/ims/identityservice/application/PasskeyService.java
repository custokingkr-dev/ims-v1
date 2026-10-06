package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.*;
import com.custoking.ims.identityservice.security.TenantContext;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;

/** WebAuthn ceremonies are single-use, bounded and bound to the persisted login session. */
@Service
@Transactional(noRollbackFor=ResponseStatusException.class)
public class PasskeyService {
    private final JdbcClient jdbc;
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final SharedQuotaRepository quotas;
    private final ObjectMapper json;
    private final RelyingParty relyingParty;
    private final String rpId;
    private final String canonicalOrigin;
    public PasskeyService(JdbcClient jdbc, AppUserRepository users, PasswordEncoder passwords,
            SharedQuotaRepository quotas, ObjectMapper json, PasskeyCredentialRepository credentials,
            @Value("${identity.webauthn.rp-id:}") String rpId,
            @Value("${identity.webauthn.origins:}") String origins) {
        this.rpId=rpId; this.canonicalOrigin=java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(v->!v.isBlank()).sorted().findFirst().orElse("");
        this.jdbc=jdbc; this.users=users; this.passwords=passwords; this.quotas=quotas; this.json=json;
        if (rpId.isBlank() && origins.isBlank()) { this.relyingParty=null; return; }
        Set<String> allowed=new HashSet<>();
        for(String value:origins.split(",")) {
            URI origin=URI.create(value.trim());
            if (!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null
                    || origin.getQuery()!=null || origin.getFragment()!=null || !origin.getPath().isEmpty()
                    || !(origin.getHost().equals(rpId) || origin.getHost().endsWith("."+rpId)))
                throw new IllegalStateException("WebAuthn requires explicit HTTPS origins belonging to the RP ID");
            allowed.add(origin.toString());
        }
        if(rpId.isBlank() || allowed.isEmpty()) throw new IllegalStateException("WebAuthn RP ID and origins required");
        this.relyingParty=RelyingParty.builder().identity(RelyingPartyIdentity.builder().id(rpId).name("Custoking IMS").build())
                .credentialRepository(credentials).origins(allowed).allowOriginPort(false).allowOriginSubdomain(false)
                .validateSignatureCounter(true).build();
    }
    public Map<String,Object> status(String sessionId) {
        Session session=session(sessionId);
        return Map.of("rpId",rpId,"canonicalOrigin",canonicalOrigin,"configured", relyingParty!=null, "enrolled", credentialCount(session.userId)>0,
                "recovery", "Use a second enrolled authenticator. Recovery requires two independent administrators with fresh passkey verification, revokes every session and requires new enrollment.");
    }
    public Ceremony registrationOptions(String sessionId,String password) {
        Session session=session(sessionId); available(); quota(session);
        var user=users.findById(session.userId).orElseThrow(()->denied("Invalid session"));
        if(password==null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || !passwords.matches(password,user.getPasswordHash()))
            throw denied("Password confirmation failed");
        if(credentialCount(session.userId)>=5) throw denied("Authenticator limit reached");
        if(credentialCount(session.userId)>0 && !verified(session)) throw denied("Fresh passkey verification required to add an authenticator");
        try {
            var options=relyingParty.startRegistration(StartRegistrationOptions.builder()
                    .user(UserIdentity.builder().name(user.getEmail()).displayName(user.getFullName())
                            .id(PasskeyCredentialRepository.handle(user.getId())).build())
                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder().userVerification(UserVerificationRequirement.REQUIRED).build())
                    .timeout(120000L).build());
            return store(session,"REGISTER",options.toJson(),json.readTree(options.toCredentialsCreateJson()).get("publicKey"));
        } catch(Exception failure) { throw unavailable(); }
    }
    public Map<String,Object> registrationVerify(String sessionId,UUID challengeId,JsonNode credential) {
        Session session=session(sessionId); available(); quota(session);
        String request=consume(session,challengeId,"REGISTER");
        if(credentialCount(session.userId)>=5 || (credentialCount(session.userId)>0 && !verified(session)))
            throw denied("Fresh passkey verification required to add an authenticator");
        try {
            // Library verifies challenge, exact RP/origin, signature, user presence and UV.
            var result=relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(PublicKeyCredentialCreationOptions.fromJson(request))
                    .response(PublicKeyCredential.parseRegistrationResponseJson(json.writeValueAsString(credential))).build());
            jdbc.sql("INSERT INTO identity.passkey_credentials(credential_id,user_id,public_key_cose,signature_count) VALUES (:id,:user,:key,:count)")
                    .param("id",result.getKeyId().getId().getBase64Url()).param("user",session.userId)
                    .param("key",result.getPublicKeyCose().getBase64Url()).param("count",result.getSignatureCount()).update();
            audit(session,"PASSKEY_ENROLLED");
            // Enrollment is not an assertion and never grants sensitive-operation authority.
            return Map.of("enrolled",true);
        } catch(Exception failure) { audit(session,"PASSKEY_ENROLLMENT_REJECTED"); throw denied("Passkey registration rejected"); }
    }
    public Ceremony assertionOptions(String sessionId) {
        Session session=session(sessionId); available(); quota(session);
        if(credentialCount(session.userId)==0) throw denied("Enroll a passkey before verifying");
        var user=users.findById(session.userId).orElseThrow(()->denied("Invalid session"));
        try {
            var request=relyingParty.startAssertion(StartAssertionOptions.builder().username(user.getEmail())
                    .userVerification(UserVerificationRequirement.REQUIRED).timeout(120000L).build());
            return store(session,"ASSERT",request.toJson(),json.readTree(request.toCredentialsGetJson()).get("publicKey"));
        } catch(Exception failure) { throw unavailable(); }
    }
    public Map<String,Object> requestRecovery(String sessionId,long targetUserId,String reason) {
        Session actor=session(sessionId); requireRecoveryApprover(actor); quota(actor);
        if(targetUserId<=0 || actor.userId==targetUserId || reason==null || reason.isBlank() || reason.length()>1000)
            throw denied("Recovery requires a different target and recorded verification reason");
        if(!users.existsById(targetUserId)) throw denied("Recovery target unavailable");
        UUID id=UUID.randomUUID();
        OffsetDateTime expires=jdbc.sql("""
                INSERT INTO identity.passkey_recovery_requests(id,target_user_id,requested_by,reason,expires_at)
                VALUES (:id,:target,:actor,:reason,clock_timestamp()+interval '10 minutes') RETURNING expires_at
                """).param("id",id).param("target",targetUserId).param("actor",actor.userId).param("reason",reason)
                .query(OffsetDateTime.class).single();
        audit(actor,"PASSKEY_RECOVERY_REQUESTED");
        return Map.of("recoveryId",id,"expiresAt",expires,"requiresDifferentApprover",true);
    }
    public Map<String,Object> approveRecovery(String sessionId,UUID recoveryId) {
        Session actor=session(sessionId);requireRecoveryApprover(actor);quota(actor);
        var request=jdbc.sql("""
                SELECT target_user_id,requested_by FROM identity.passkey_recovery_requests
                WHERE id=:id AND completed_at IS NULL AND expires_at>clock_timestamp() FOR UPDATE
                """).param("id",recoveryId).query((rs,n)->new long[]{rs.getLong(1),rs.getLong(2)}).optional()
                .orElseThrow(()->denied("Recovery request expired or completed"));
        if(actor.userId==request[0] || actor.userId==request[1]) throw denied("Recovery requires an independent privileged approver");
        // Recovery invalidates password-reset links, every login family and old step-up evidence.
        // The recovered owner must confirm their password and enroll/verify a new authenticator.
        jdbc.sql("UPDATE identity.app_users SET credential_version=credential_version+1 WHERE id=:id")
                .param("id",request[0]).update();
        jdbc.sql("UPDATE identity.auth_sessions SET status='REVOKED' WHERE user_id=:id").param("id",request[0]).update();
        jdbc.sql("DELETE FROM identity.session_step_up WHERE user_id=:id").param("id",request[0]).update();
        jdbc.sql("DELETE FROM identity.passkey_challenges WHERE user_id=:id").param("id",request[0]).update();
        jdbc.sql("DELETE FROM identity.passkey_credentials WHERE user_id=:id").param("id",request[0]).update();
        jdbc.sql("UPDATE identity.passkey_recovery_requests SET approved_by=:actor,completed_at=clock_timestamp() WHERE id=:id")
                .param("actor",actor.userId).param("id",recoveryId).update();
        audit(actor,"PASSKEY_RECOVERY_COMPLETED");
        return Map.of("recovered",true,"requiresNewEnrollment",true);
    }
    private void requireRecoveryApprover(Session actor) {
        var user=users.findById(actor.userId).orElseThrow(()->denied("Recovery approver unavailable"));
        if(!"SUPERADMIN".equalsIgnoreCase(user.getRole()) || !verified(actor))
            throw denied("Recovery requires a privileged user with fresh passkey verification");
    }
    public Map<String,Object> assertionVerify(String sessionId,UUID challengeId,JsonNode credential) {
        Session session=session(sessionId); available(); quota(session);
        String request=consume(session,challengeId,"ASSERT");
        try {
            var parsed=PublicKeyCredential.parseAssertionResponseJson(json.writeValueAsString(credential));
            // Serialize the authenticator counter against concurrent assertions across replicas.
            jdbc.sql("SELECT credential_id FROM identity.passkey_credentials WHERE credential_id=:id AND user_id=:user FOR UPDATE")
                    .param("id",parsed.getId().getBase64Url()).param("user",session.userId).query(String.class).single();
            var result=relyingParty.finishAssertion(FinishAssertionOptions.builder().request(AssertionRequest.fromJson(request)).response(parsed).build());
            if(!result.isSuccess() || !result.isUserVerified()
                    || PasskeyCredentialRepository.id(result.getUserHandle())!=session.userId) throw new IllegalArgumentException();
            jdbc.sql("UPDATE identity.passkey_credentials SET signature_count=:count,last_used_at=now() WHERE credential_id=:id AND user_id=:user")
                    .param("count",result.getSignatureCount()).param("id",result.getCredentialId().getBase64Url()).param("user",session.userId).update();
            OffsetDateTime until=jdbc.sql("""
                    INSERT INTO identity.session_step_up(family_id,user_id,credential_version,verified_at,expires_at)
                    VALUES (:family,:user,:version,clock_timestamp(),clock_timestamp()+interval '5 minutes')
                    ON CONFLICT(family_id) DO UPDATE SET user_id=EXCLUDED.user_id,credential_version=EXCLUDED.credential_version,
                    verified_at=EXCLUDED.verified_at,expires_at=EXCLUDED.expires_at RETURNING expires_at
                    """).param("family",session.familyId).param("user",session.userId).param("version",session.credentialVersion)
                    .query(OffsetDateTime.class).single();
            audit(session,"PASSKEY_STEP_UP_VERIFIED");
            return Map.of("stepUpExpiresAt",until);
        } catch(Exception failure) { audit(session,"PASSKEY_ASSERTION_REJECTED"); throw denied("Passkey verification rejected"); }
    }
    private Session session(String sessionId) {
        Long user=TenantContext.get().userId();
        if(user==null || sessionId==null || sessionId.length()>255) throw denied("Authenticated session required");
        return jdbc.sql("""
                SELECT s.id,s.user_id,s.family_id,s.credential_version FROM identity.auth_sessions s
                JOIN identity.app_users u ON u.id=s.user_id
                WHERE s.id=:id AND s.user_id=:user AND s.status IN ('ACTIVE','ROTATED') AND s.expires_at>clock_timestamp()
                AND s.credential_version=u.credential_version AND u.deleted_at IS NULL FOR UPDATE OF s,u
                """).param("id",sessionId).param("user",user)
                .query((rs,n)->new Session(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getLong(4))).optional()
                .orElseThrow(()->denied("Authenticated session required"));
    }
    private boolean verified(Session session) {
        return jdbc.sql("SELECT 1 FROM identity.session_step_up WHERE family_id=:family AND user_id=:user AND credential_version=:version AND expires_at>clock_timestamp()")
                .param("family",session.familyId).param("user",session.userId).param("version",session.credentialVersion).query(Integer.class).optional().isPresent();
    }
    private long credentialCount(long user) {
        return jdbc.sql("SELECT count(*) FROM identity.passkey_credentials WHERE user_id=:user").param("user",user).query(Long.class).single();
    }
    private Ceremony store(Session session,String purpose,String request,JsonNode publicKey) {
        UUID id=UUID.randomUUID();
        jdbc.sql("DELETE FROM identity.passkey_challenges WHERE user_id=:user AND (expires_at<clock_timestamp() OR consumed_at IS NOT NULL OR session_id=:session)")
                .param("user",session.userId).param("session",session.id).update();
        jdbc.sql("INSERT INTO identity.passkey_challenges(id,user_id,session_id,purpose,request_json,expires_at) VALUES (:id,:user,:session,:purpose,:request,clock_timestamp()+interval '2 minutes')")
                .param("id",id).param("user",session.userId).param("session",session.id).param("purpose",purpose).param("request",request).update();
        return new Ceremony(id,publicKey);
    }
    private String consume(Session session,UUID id,String purpose) {
        if(id==null) throw denied("Invalid passkey challenge");
        return jdbc.sql("""
                UPDATE identity.passkey_challenges SET consumed_at=clock_timestamp()
                WHERE id=:id AND user_id=:user AND session_id=:session AND purpose=:purpose
                AND consumed_at IS NULL AND expires_at>clock_timestamp() RETURNING request_json
                """).param("id",id).param("user",session.userId).param("session",session.id).param("purpose",purpose)
                .query(String.class).optional().orElseThrow(()->denied("Expired or used passkey challenge"));
    }
    private void quota(Session session) { if(!quotas.consume("passkey:"+session.userId,20,60)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Passkey attempt limit reached"); }
    private void available() { if(relyingParty==null) throw unavailable(); }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Passkey service is not configured"); }
    private ResponseStatusException denied(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN,message); }
    private void audit(Session session,String action) {
        jdbc.sql("INSERT INTO identity.rbac_audit_log(event_type,actor_user_id,correlation_id,created_at) VALUES (:action,:user,:session,now())")
                .param("action",action).param("user",session.userId).param("session",session.familyId).update();
    }
    private record Session(String id,long userId,String familyId,long credentialVersion) { }
    public record Ceremony(UUID challengeId,JsonNode publicKey) { }
}
