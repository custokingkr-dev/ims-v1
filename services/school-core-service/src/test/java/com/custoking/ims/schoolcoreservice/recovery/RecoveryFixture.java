package com.custoking.ims.schoolcoreservice.recovery;

import com.custoking.ims.schoolcoreservice.erasure.*;
import tools.jackson.databind.ObjectMapper;
import java.security.*;
import java.time.*;
import java.util.*;

final class RecoveryFixture {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String LINEAGE="68d855f1-2025-4a9e-b08a-34e999650a8d", EPOCH="f3ece72e-929a-4dd9-aa22-d509f76dfb55";
    static final Instant NOW=Instant.parse("2026-10-08T12:00:00Z");
    final KeyPair approvalKeys,freezeKeys;
    final Map<String,Object> policy=new LinkedHashMap<>(), approval=new LinkedHashMap<>(), freeze=new LinkedHashMap<>(),target=new LinkedHashMap<>(),plan=new LinkedHashMap<>();
    final FakeStore store=new FakeStore();
    byte[] approvalBytes,planBytes,targetBytes,freezeBytes,controlBytes;
    RecoveryFixture(long student,long school,UUID incarnation) throws Exception {
        approvalKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();freezeKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<String,Object> identity=new TreeMap<>(Map.of("schemaVersion",1,"project","custoking-dev","sourceInstance","custoking-db-dev","database","custoking_dev","sourceLineageId",LINEAGE,"restoreEpoch",EPOCH,"schoolId",school,"studentId",student,"studentIncarnation",incarnation.toString()));
        String intent=RecoveryAuthorization.sha(canonical(identity));UUID operation=UUID.nameUUIDFromBytes(("ims-student-erasure:"+intent).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String,Object> body=new TreeMap<>(identity);body.put("kind","student.erasure-intent.v1");body.put("intentId",intent);body.put("operationId",operation.toString());
        String object="intents/"+LINEAGE+"/"+EPOCH+"/"+intent+".json";byte[] intentBytes=canonical(body);store.objects.put(object,new ErasureJournalStore.StoredObject(9007199254740993L,intentBytes));
        Map<String,Object> task=new LinkedHashMap<>(Map.of("intentId",intent,"operationId",operation.toString(),"schoolId",school,"studentId",student,"studentIncarnation",incarnation.toString(),"restoreEpoch",EPOCH,"object",object,"generation","9007199254740993","sha256",RecoveryAuthorization.sha(intentBytes)));
        plan.putAll(Map.of("schemaVersion",1,"mode","OWNER_REPLAY_PREPARATION_ONLY","bundleSha256","a".repeat(64),"sourceLineageId",LINEAGE,"tasks",List.of(task),"restorationReady",false,"deliveryResume",false));
        Map<String,Object> control=new TreeMap<>();for(String k:List.of("schemaVersion","project","sourceInstance","database","sourceLineageId","restoreEpoch"))control.put(k,identity.get(k));control.put("state","RECONCILING");controlBytes=canonical(control);store.objects.put("control/"+LINEAGE+"/current.json",new ErasureJournalStore.StoredObject(7,controlBytes));
        policy.putAll(Map.of("schemaVersion",1,"project","custoking-dev","primaryIp","10.92.0.3","approvalPublicKey",Base64.getEncoder().encodeToString(approvalKeys.getPublic().getEncoded()),"freezePublicKey",Base64.getEncoder().encodeToString(freezeKeys.getPublic().getEncoded())));
        Map<String,Object> verification=new LinkedHashMap<>(Map.of("project","custoking-dev","target","custoking-dev-security-restore-20261008120000-aabbccdd","physicalCatalogConnectionVerified",true,"fenceCount",12,"restorationReady",false,"deliveryResume",false,"sourceLineageVerified",false,"cloneCaSha256","b".repeat(64),"selectedRuntimeAclAndRlsVerified",true));
        target.putAll(Map.of("project","custoking-dev","sourceInstance","custoking-db-dev","clone",verification.get("target"),"privateIp","10.92.0.44","primaryIp","10.92.0.3","capturedAtUtc",NOW.toString(),"cloneCaPemSha256","c".repeat(64),"verification",verification));
        approval.putAll(Map.of("schemaVersion",1,"purpose","ISOLATED_STUDENT_ERASURE_REPLAY","project","custoking-dev","database","custoking_dev","databaseRole",RecoveryAuthorization.PURPOSE_ROLE,"clone",verification.get("target"),"privateIp","10.92.0.44","lineage",LINEAGE,"epoch",EPOCH));
        approval.putAll(Map.of("epochGeneration","7","epochSha256",RecoveryAuthorization.sha(controlBytes),"photoBucket","custoking-dev-recovery-fixture","targetProofCapturedAt",NOW.toString(),"issuedAt",NOW.toString(),"expiresAt",NOW.plusSeconds(300).toString(),"freezeObject","control/"+LINEAGE+"/recovery-freeze.json","freezeGeneration","8"));
        Map<String,Object> admissions=new TreeMap<>();for(String name:RecoveryAuthorization.ADMISSIONS)admissions.put(name,Map.of("writesPaused",true,"deliveryPaused",true,"inFlight",0));
        freeze.putAll(Map.of("schemaVersion",1,"kind","recovery.freeze.operator-attestation.v1","project","custoking-dev","clone",verification.get("target"),"lineage",LINEAGE,"epoch",EPOCH,"issuedAt",NOW.toString(),"expiresAt",NOW.plusSeconds(300).toString(),"admissions",admissions));refresh();
    }
    void refresh() throws Exception {
        planBytes=JSON.writeValueAsBytes(plan);targetBytes=JSON.writeValueAsBytes(target);approval.put("planSha256",RecoveryAuthorization.sha(planBytes));approval.put("targetProofSha256",RecoveryAuthorization.sha(targetBytes));freeze.put("planSha256",approval.get("planSha256"));freezeBytes=sign(freeze,freezeKeys.getPrivate());approval.put("freezeSha256",RecoveryAuthorization.sha(freezeBytes));approvalBytes=sign(approval,approvalKeys.getPrivate());store.objects.put((String)approval.get("freezeObject"),new ErasureJournalStore.StoredObject(8,freezeBytes));
    }
    RecoveryAuthorization authority(){return new RecoveryAuthorization(JSON.writeValueAsBytes(policy),approvalBytes,planBytes,targetBytes,Clock.fixed(NOW,ZoneOffset.UTC));}
    StudentErasureJournal journal(){return new StudentErasureJournal(new ErasureJournalConfiguration(true,"custoking-dev",ErasureJournalConfiguration.BUCKET,LINEAGE,EPOCH,7,RecoveryAuthorization.sha(controlBytes)),store);}
    static byte[] sign(Map<String,Object> payload,PrivateKey key)throws Exception{byte[] raw=JSON.writeValueAsBytes(payload);Signature s=Signature.getInstance("Ed25519");s.initSign(key);s.update(raw);return JSON.writeValueAsBytes(Map.of("payloadBase64",Base64.getEncoder().encodeToString(raw),"signatureBase64",Base64.getEncoder().encodeToString(s.sign())));}
    static byte[] canonical(Map<String,Object> value){return (JSON.writeValueAsString(new TreeMap<>(value))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    static class FakeStore implements ErasureJournalStore {
        final Map<String,StoredObject> objects=new HashMap<>();int freezeReads;int mutateAt=-1;boolean created;Runnable onFreezeRead=()->{};
        @Override public StoredObject readLatest(String bucket,String object,int limit){StoredObject v=objects.get(object);if(object.endsWith("recovery-freeze.json")){freezeReads++;onFreezeRead.run();if(freezeReads==mutateAt)return new StoredObject(v.generation()+1,v.body());}if(v==null)throw new MissingObject();return v;}
        @Override public StoredObject readGeneration(String bucket,String object,long gen,int limit){StoredObject v=objects.get(object);if(v==null||v.generation()!=gen)throw new MissingObject();return v;}
        @Override public long createOnly(String b,String o,byte[] x){created=true;throw new IllegalStateException("Replay must never create journal intent");}
    }
}
