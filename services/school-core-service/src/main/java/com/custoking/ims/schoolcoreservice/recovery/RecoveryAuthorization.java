package com.custoking.ims.schoolcoreservice.recovery;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

/** New operator contract, not a claim that signed freeze assertions enforce platform-wide pause. */
final class RecoveryAuthorization {
    static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final String PURPOSE_ROLE = "ims_dev_restore_executor";
    static final Set<String> ADMISSIONS = Set.of("school-core-service", "identity-service", "billing-service", "operations-service", "platform-service", "api-gateway");
    final JsonNode approval, plan, policy;
    final PublicKey freezeKey;
    final Clock clock;
    final String approvalSha;
    RecoveryAuthorization(byte[] trustedPolicy, byte[] signedApproval, byte[] worklist, byte[] targetProof, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        policy = parse(trustedPolicy, 16384);
        fields(policy, "schemaVersion", "project", "primaryIp", "approvalPublicKey", "freezePublicKey");
        require(version(policy) && text(policy,"project").equals("custoking-dev"));
        PublicKey approvalKey=key(text(policy,"approvalPublicKey")); freezeKey=key(text(policy,"freezePublicKey"));
        require(!Arrays.equals(approvalKey.getEncoded(), freezeKey.getEncoded()));
        approval = signed(signedApproval, approvalKey); approvalSha=sha(signedApproval);
        fields(approval,"schemaVersion","purpose","project","database","databaseRole","clone","privateIp","lineage","epoch","epochGeneration","epochSha256","photoBucket","planSha256","targetProofSha256","targetProofCapturedAt","issuedAt","expiresAt","freezeObject","freezeGeneration","freezeSha256");
        require(version(approval) && text(approval,"purpose").equals("ISOLATED_STUDENT_ERASURE_REPLAY") && text(approval,"project").equals("custoking-dev") && text(approval,"database").equals("custoking_dev") && text(approval,"databaseRole").equals(PURPOSE_ROLE));
        require(text(approval,"clone").matches("custoking-dev-security-restore-[0-9]{14}-[a-f0-9]{8}"));
        privateIp(text(policy,"primaryIp"));privateIp(text(approval,"privateIp"));require(!text(approval,"privateIp").equals("10.92.0.3"));require(!text(policy,"primaryIp").equals(text(approval,"privateIp")));
        uuid(text(approval,"epoch"));require(text(approval,"lineage").matches("[a-z0-9][a-z0-9-]{7,63}"));
        generation(approval,"epochGeneration");generation(approval,"freezeGeneration");hash(text(approval,"epochSha256"));hash(text(approval,"freezeSha256"));
        require(text(approval,"freezeObject").equals("control/"+text(approval,"lineage")+"/recovery-freeze.json"));
        require(text(approval,"photoBucket").matches("custoking-dev-[a-z0-9-]{1,50}"));
        require(sha(worklist).equals(text(approval,"planSha256")) && sha(targetProof).equals(text(approval,"targetProofSha256")));
        JsonNode wrapper=parse(targetProof,32768);
        fields(wrapper,"project","sourceInstance","clone","privateIp","primaryIp","capturedAtUtc","cloneCaPemSha256","verification");
        require(text(wrapper,"project").equals("custoking-dev") && text(wrapper,"sourceInstance").equals("custoking-db-dev") && text(wrapper,"clone").equals(text(approval,"clone")) && text(wrapper,"privateIp").equals(text(approval,"privateIp")) && text(wrapper,"primaryIp").equals(text(policy,"primaryIp")) && text(wrapper,"capturedAtUtc").equals(text(approval,"targetProofCapturedAt")));
        hash(text(wrapper,"cloneCaPemSha256"));
        JsonNode target=wrapper.path("verification");
        fields(target,"project","target","physicalCatalogConnectionVerified","fenceCount","restorationReady","deliveryResume","sourceLineageVerified","cloneCaSha256","selectedRuntimeAclAndRlsVerified");
        require(target.path("project").asText().equals("custoking-dev") && target.path("target").asText().equals(text(approval,"clone")) && flag(target,"physicalCatalogConnectionVerified",true) && flag(target,"selectedRuntimeAclAndRlsVerified",true) && target.path("fenceCount").isIntegralNumber() && target.path("fenceCount").canConvertToInt() && target.path("fenceCount").asInt()==12);
        require(target.path("sourceLineageVerified").isBoolean());hash(text(target,"cloneCaSha256")); require(flag(target,"restorationReady",false) && flag(target,"deliveryResume",false));
        plan=parse(worklist, 2*1024*1024);fields(plan,"schemaVersion","mode","bundleSha256","sourceLineageId","tasks","restorationReady","deliveryResume");
        require(version(plan) && text(plan,"mode").equals("OWNER_REPLAY_PREPARATION_ONLY") && flag(plan,"restorationReady",false) && flag(plan,"deliveryResume",false) && text(plan,"sourceLineageId").equals(text(approval,"lineage")));hash(text(plan,"bundleSha256"));
        JsonNode tasks=plan.path("tasks");require(tasks.isArray() && tasks.size()>0 && tasks.size()<=100);
        Set<String> seen=new HashSet<>();
        for(JsonNode task:tasks){fields(task,"intentId","operationId","schoolId","studentId","studentIncarnation","restoreEpoch","object","generation","sha256");hash(text(task,"intentId"));hash(text(task,"sha256"));require(seen.add(text(task,"intentId")));uuid(text(task,"operationId"));uuid(text(task,"studentIncarnation"));uuid(text(task,"restoreEpoch"));positive(task,"studentId");positive(task,"schoolId");generation(task,"generation");require(text(task,"object").equals("intents/"+text(approval,"lineage")+"/"+text(task,"restoreEpoch")+"/"+text(task,"intentId")+".json"));}
        requireFresh(45);
    }
    public void requireFresh(int remainingSeconds) {
        Instant now=clock.instant(), issued=time(approval,"issuedAt"), expires=time(approval,"expiresAt"), observed=time(approval,"targetProofCapturedAt");
        require(!issued.isAfter(now) && !observed.isAfter(issued) && Duration.between(issued,now).compareTo(Duration.ofSeconds(120))<=0 && Duration.between(observed,now).compareTo(Duration.ofSeconds(120))<=0);
        require(expires.isAfter(now.plusSeconds(remainingSeconds)) && Duration.between(issued,expires).compareTo(Duration.ofSeconds(600))<=0);
    }
    public void verifyFreeze(byte[] body) {
        JsonNode freeze=signed(body,freezeKey);
        fields(freeze,"schemaVersion","kind","project","clone","lineage","epoch","planSha256","issuedAt","expiresAt","admissions");
        require(version(freeze) && text(freeze,"kind").equals("recovery.freeze.operator-attestation.v1") && text(freeze,"project").equals("custoking-dev"));
        for(String k:List.of("clone","lineage","epoch","planSha256"))require(text(freeze,k).equals(text(approval,k)));
        Instant now=clock.instant(),issued=time(freeze,"issuedAt"),expires=time(freeze,"expiresAt");require(!issued.isAfter(now) && Duration.between(issued,now).compareTo(Duration.ofSeconds(120))<=0 && expires.isAfter(now.plusSeconds(45)) && Duration.between(issued,expires).compareTo(Duration.ofSeconds(600))<=0);
        JsonNode admissions=freeze.path("admissions");fields(admissions,ADMISSIONS.toArray(String[]::new));
        for(JsonNode admission:admissions){fields(admission,"writesPaused","deliveryPaused","inFlight");require(admission.path("writesPaused").isBoolean() && admission.path("writesPaused").asBoolean() && admission.path("deliveryPaused").isBoolean() && admission.path("deliveryPaused").asBoolean() && admission.path("inFlight").isIntegralNumber() && admission.path("inFlight").canConvertToLong() && admission.path("inFlight").asLong()==0);}
    }
    static JsonNode signed(byte[] body,PublicKey key){
        JsonNode envelope=parse(body,32768);fields(envelope,"payloadBase64","signatureBase64");
        try {byte[] payload=Base64.getDecoder().decode(text(envelope,"payloadBase64"));byte[] signature=Base64.getDecoder().decode(text(envelope,"signatureBase64"));Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(payload);require(verifier.verify(signature));return parse(payload,16384);}catch(GeneralSecurityException|IllegalArgumentException e){throw rejected();}
    }
    static PublicKey key(String b64){try{return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));}catch(Exception e){throw rejected();}}
    static JsonNode parse(byte[] raw,int max){require(raw!=null&&raw.length>0&&raw.length<=max);try{return JSON.readTree(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString());}catch(Exception e){throw rejected();}}
    static void fields(JsonNode node,String... names){require(node.isObject());Set<String> actual=new HashSet<>();node.propertyNames().forEach(actual::add);require(actual.equals(Set.of(names)));}
    static boolean version(JsonNode n){return n.path("schemaVersion").isIntegralNumber() && n.path("schemaVersion").canConvertToInt() && n.path("schemaVersion").asInt()==1;}
    static boolean flag(JsonNode n,String field,boolean expected){return n.path(field).isBoolean() && n.path(field).asBoolean()==expected;}
    static String text(JsonNode n,String k){require(n.path(k).isString());return n.path(k).asText();}
    static long generation(JsonNode n,String k){String v=text(n,k);require(v.matches("[1-9][0-9]{0,18}"));try{return Long.parseLong(v);}catch(Exception e){throw rejected();}}
    static long positive(JsonNode n,String k){require(n.path(k).isIntegralNumber()&&n.path(k).canConvertToLong()&&n.path(k).asLong()>0);return n.path(k).asLong();}
    static Instant time(JsonNode n,String k){try{return Instant.parse(text(n,k));}catch(Exception e){throw rejected();}}
    static void hash(String x){require(x.matches("[a-f0-9]{64}"));}
    static void uuid(String x){try{require(UUID.fromString(x).toString().equals(x));}catch(Exception e){throw rejected();}}
    static void privateIp(String ip){require(ip.matches("(?:10\\.(?:[0-9]{1,3}\\.){2}[0-9]{1,3}|192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}|172\\.(?:1[6-9]|2[0-9]|3[01])\\.[0-9]{1,3}\\.[0-9]{1,3})"));for(String part:ip.split("\\."))require(Integer.parseInt(part)<=255 && Integer.toString(Integer.parseInt(part)).equals(part));}
    static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw rejected();}}
    static void require(boolean ok){if(!ok)throw rejected();}
    static IllegalStateException rejected(){return new IllegalStateException("ISOLATED_RECOVERY_REJECTED");}
}
