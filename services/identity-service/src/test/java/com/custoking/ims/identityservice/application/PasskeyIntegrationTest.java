package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.jwt-secret=integration-test-jwt-secret-AAAABBBBCCCC1234",
        "identity.tenant-school.base-url=http://localhost:19999","identity.tenant-school.token=it-token",
        "identity.introspection-token=integration-peer-token","identity.webauthn.rp-id=ims.test","identity.webauthn.origins=https://ims.test"})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker=true)
class PasskeyIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
        r.add("spring.flyway.url",PG::getJdbcUrl);r.add("spring.flyway.user",PG::getUsername);r.add("spring.flyway.password",PG::getPassword);
    }
    @Autowired com.custoking.ims.identityservice.api.AuthController controller;
    @Autowired PasskeyService passkeys;
    @Autowired IdentityAuthService auth;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private IdentityAuthService.LoginResult login;
    private long userId;
    private KeyPair key;
    private byte[] credentialId;
    @BeforeEach void setup() throws Exception {
        String email="passkey-"+UUID.randomUUID()+"@example.invalid";
        jdbc.update("INSERT INTO identity.app_users(full_name,email,password_hash,role,created_at) VALUES ('Passkey Test',?,?,'SUPERADMIN',now())",email,encoder.encode("confirmed-password"));
        login=auth.login(new IdentityAuthService.LoginRequest(email,"confirmed-password"));userId=login.authResponse().userId();
        TenantContext.set(new TenantContext(userId,email,"SUPERADMIN",null,null));
        KeyPairGenerator gen=KeyPairGenerator.getInstance("EC"); gen.initialize(new ECGenParameterSpec("secp256r1")); key=gen.generateKeyPair();
        credentialId=new byte[32];new SecureRandom().nextBytes(credentialId);
    }
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void verifiedAuthenticatorCreatesSessionBoundStepUpThatSurvivesRotationAndNotLogout() throws Exception {
        enroll(); assertThat(auth.introspect(login.authResponse().accessToken()).principal().stepUpExpiresAt()).isNull();
        var request=passkeys.assertionOptions(session());
        passkeys.assertionVerify(session(),request.challengeId(),assertion(request,"https://ims.test",true,1,false));
        assertThat(auth.introspect(login.authResponse().accessToken()).principal().stepUpExpiresAt()).isNotNull();
        var rotated=auth.refresh(login.refreshToken());
        assertThat(auth.introspect(rotated.authResponse().accessToken()).principal().stepUpExpiresAt()).isNotNull();
        auth.logout(rotated.refreshToken());
        assertThat(auth.introspect(rotated.authResponse().accessToken()).active()).isFalse();
        assertThatThrownBy(()->passkeys.assertionOptions(session())).hasMessageContaining("Authenticated session required");
    }
    @Test void wrongOriginChallengeAndMissingUserVerificationCannotAuthorize() throws Exception {
        enroll();
        for(int attack=0;attack<3;attack++) {
            var request=passkeys.assertionOptions(session());
            var response=assertion(request,attack==0?"https://phishing.test":"https://ims.test",attack!=2,attack+1,attack==1);
            assertThatThrownBy(()->passkeys.assertionVerify(session(),request.challengeId(),response)).hasMessageContaining("verification rejected");
            assertThat(auth.introspect(login.authResponse().accessToken()).principal().stepUpExpiresAt()).isNull();
            assertThatThrownBy(()->passkeys.assertionVerify(session(),request.challengeId(),response)).hasMessageContaining("used passkey challenge");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.rbac_audit_log WHERE actor_user_id=? AND event_type='PASSKEY_ASSERTION_REJECTED'",Integer.class,userId)).isEqualTo(3);
    }
    @Test void challengeCannotMoveBetweenSessionsAndAddingFactorRequiresPriorFreshAssertion() throws Exception {
        var request=passkeys.registrationOptions(session(),"confirmed-password");
        var second=auth.login(new IdentityAuthService.LoginRequest(login.authResponse().email(),"confirmed-password"));
        assertThatThrownBy(()->passkeys.registrationVerify(second.authResponse().sessionId(),request.challengeId(),registration(request)))
                .hasMessageContaining("Expired or used passkey challenge");
        passkeys.registrationVerify(session(),request.challengeId(),registration(request));
        assertThatThrownBy(()->passkeys.registrationOptions(session(),"confirmed-password")).hasMessageContaining("Fresh passkey verification");
        assertThatThrownBy(()->passkeys.registrationOptions(session(),"wrong-password")).hasMessageContaining("Password confirmation failed");
    }
    @Test void replayedAuthenticatorCounterCannotExtendStepUp() throws Exception {
        enroll();var request=passkeys.assertionOptions(session());
        passkeys.assertionVerify(session(),request.challengeId(),assertion(request,"https://ims.test",true,1,false));
        var replay=passkeys.assertionOptions(session());
        assertThatThrownBy(()->passkeys.assertionVerify(session(),replay.challengeId(),assertion(replay,"https://ims.test",true,1,false)))
                .hasMessageContaining("verification rejected");
    }
    @Test void recoveryNeedsTwoIndependentVerifiedAdminsAndNeverGrantsRecoveredUserAuthority() throws Exception {
        enroll();verify(); var target=login;long targetId=userId;
        setup();
        assertThatThrownBy(()->passkeys.requestRecovery(session(),targetId,"Owner identity checked with two administrators"))
                .hasMessageContaining("fresh passkey verification");
        enroll();verify();
        assertThatThrownBy(()->passkeys.requestRecovery(session(),userId,"self recovery"))
                .hasMessageContaining("different target");
        var request=passkeys.requestRecovery(session(),targetId,"Owner identity checked with two administrators");
        UUID recoveryId=(UUID)request.get("recoveryId");
        assertThatThrownBy(()->passkeys.approveRecovery(session(),recoveryId)).hasMessageContaining("independent privileged approver");
        setup();enroll();verify();
        assertThat(passkeys.approveRecovery(session(),recoveryId)).containsEntry("requiresNewEnrollment",true);
        assertThat(auth.introspect(target.authResponse().accessToken()).active()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.passkey_credentials WHERE user_id=?",Integer.class,targetId)).isZero();
        assertThatThrownBy(()->passkeys.approveRecovery(session(),recoveryId)).hasMessageContaining("expired or completed");
        var recovered=auth.login(new IdentityAuthService.LoginRequest(target.authResponse().email(),"confirmed-password"));
        assertThat(recovered.authResponse().stepUpExpiresAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.passkey_recovery_requests WHERE id=? AND completed_at IS NOT NULL AND approved_by<>requested_by",Integer.class,recoveryId)).isEqualTo(1);
    }
    @Test void authoritativeIntrospectionAndDirectRbacRejectForgedExpiredOrRevokedStepUp() throws Exception {
        var request=new com.custoking.ims.identityservice.api.AuthController.IntrospectionRequest(login.authResponse().accessToken(),"POST","/api/v1/rbac/roles",null);
        assertThatThrownBy(()->controller.introspect("integration-peer-token",request)).isInstanceOf(com.custoking.ims.identityservice.security.StepUpRequiredException.class);
        assertThat(sensitiveFilterStatus(session())).isEqualTo(403);
        enroll(); assertThat(sensitiveFilterStatus(session())).isEqualTo(403);
        verify(); assertThat(controller.introspect("integration-peer-token",request).active()).isTrue();
        assertThat(sensitiveFilterStatus("forged-session")).isEqualTo(403);
        assertThat(sensitiveFilterStatus(session())).isEqualTo(200);
        jdbc.update("UPDATE identity.session_step_up SET expires_at=clock_timestamp()-interval '1 second' WHERE user_id=?",userId);
        assertThat(sensitiveFilterStatus(session())).isEqualTo(403);
        assertThatThrownBy(()->controller.introspect("integration-peer-token",request)).isInstanceOf(com.custoking.ims.identityservice.security.StepUpRequiredException.class);
        var assertion=passkeys.assertionOptions(session());passkeys.assertionVerify(session(),assertion.challengeId(),assertion(assertion,"https://ims.test",true,2,false));
        auth.logout(login.refreshToken());assertThat(sensitiveFilterStatus(session())).isEqualTo(403);
    }
    private int sensitiveFilterStatus(String sessionId) throws Exception {
        var filter=new com.custoking.ims.identityservice.security.TenantContextFilter(org.springframework.jdbc.core.simple.JdbcClient.create(jdbc.getDataSource()));
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/v1/rbac/roles");
        request.addHeader("X-Authenticated-User-Id",Long.toString(userId));request.addHeader("X-Authenticated-Role","SUPERADMIN");
        request.addHeader("X-Authenticated-Session-Id",sessionId);request.addHeader("X-Authenticated-Step-Up-Expires-At","2999-01-01T00:00:00Z");
        var response=new org.springframework.mock.web.MockHttpServletResponse();filter.doFilter(request,response,new org.springframework.mock.web.MockFilterChain());
        TenantContext.set(new TenantContext(userId,login.authResponse().email(),"SUPERADMIN",null,null));return response.getStatus();
    }
    private void verify() throws Exception {
        var request=passkeys.assertionOptions(session());passkeys.assertionVerify(session(),request.challengeId(),assertion(request,"https://ims.test",true,1,false));
    }
    private String session() { return login.authResponse().sessionId(); }
    private void enroll() throws Exception { var request=passkeys.registrationOptions(session(),"confirmed-password");passkeys.registrationVerify(session(),request.challengeId(),registration(request)); }
    private JsonNode registration(PasskeyService.Ceremony request) throws Exception {
        ECPublicKey pub=(ECPublicKey)key.getPublic();
        byte[] cose=concat(new byte[]{(byte)0xa5,1,2,3,0x26,0x20,1,0x21},cborBytes(unsigned(pub.getW().getAffineX().toByteArray())),new byte[]{0x22},cborBytes(unsigned(pub.getW().getAffineY().toByteArray())));
        byte[] authData=concat(hash("ims.test".getBytes(StandardCharsets.UTF_8)),new byte[]{0x45},new byte[4],new byte[16],new byte[]{0,32},credentialId,cose);
        byte[] attestation=concat(new byte[]{(byte)0xa3},cborText("fmt"),cborText("none"),cborText("attStmt"),new byte[]{(byte)0xa0},cborText("authData"),cborBytes(authData));
        return json.readTree(json.writeValueAsString(Map.of("id",b64(credentialId),"rawId",b64(credentialId),"type","public-key","clientExtensionResults",Map.of(),
                "response",Map.of("clientDataJSON",b64(clientData("webauthn.create",request.publicKey().get("challenge").asString(),"https://ims.test")),"attestationObject",b64(attestation)))));
    }
    private JsonNode assertion(PasskeyService.Ceremony request,String origin,boolean uv,int count,boolean wrongChallenge) throws Exception {
        byte[] data=clientData("webauthn.get",wrongChallenge?b64(new byte[32]):request.publicKey().get("challenge").asString(),origin);
        byte[] authData=concat(hash("ims.test".getBytes(StandardCharsets.UTF_8)),new byte[]{(byte)(uv?5:1)},ByteBuffer.allocate(4).putInt(count).array());
        Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(key.getPrivate());signature.update(concat(authData,hash(data)));
        return json.readTree(json.writeValueAsString(Map.of("id",b64(credentialId),"rawId",b64(credentialId),"type","public-key","clientExtensionResults",Map.of(),
                "response",Map.of("clientDataJSON",b64(data),"authenticatorData",b64(authData),"signature",b64(signature.sign()),"userHandle",b64(ByteBuffer.allocate(8).putLong(userId).array())))));
    }
    private byte[] clientData(String type,String challenge,String origin) throws Exception { return json.writeValueAsBytes(Map.of("type",type,"challenge",challenge,"origin",origin,"crossOrigin",false)); }
    private static byte[] hash(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] unsigned(byte[] b) { byte[] r=new byte[32];System.arraycopy(b,Math.max(0,b.length-32),r,Math.max(0,32-b.length),Math.min(32,b.length));return r; }
    private static byte[] cborText(String s) { return concat(new byte[]{(byte)(0x60+s.length())},s.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] cborBytes(byte[] b) { return concat(b.length<256?new byte[]{0x58,(byte)b.length}:new byte[]{0x59,(byte)(b.length>>8),(byte)b.length},b); }
    private static byte[] concat(byte[]... chunks) { var out=new ByteArrayOutputStream();for(byte[] c:chunks)out.writeBytes(c);return out.toByteArray(); }
}
