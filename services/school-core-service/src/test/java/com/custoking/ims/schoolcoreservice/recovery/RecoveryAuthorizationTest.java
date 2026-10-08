package com.custoking.ims.schoolcoreservice.recovery;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class RecoveryAuthorizationTest {
    @Test void validExactApprovalAndSeparateFreezeContract()throws Exception {var f=new RecoveryFixture(1,2,UUID.randomUUID());var a=f.authority();a.verifyFreeze(f.freezeBytes);assertThat(a.plan.path("tasks").get(0).path("generation").asText()).isEqualTo("9007199254740993");}
    @Test void wrongSignerAndPlanBodyReject()throws Exception {var f=new RecoveryFixture(1,2,UUID.randomUUID());f.approvalBytes=RecoveryFixture.sign(f.approval,f.freezeKeys.getPrivate());assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);f.refresh();f.planBytes[0]=' ';assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);}
    @Test void sourcePrivateAddressAndWrongPurposeRefused()throws Exception {var f=new RecoveryFixture(1,2,UUID.randomUUID());f.approval.put("privateIp","10.92.0.3");f.refresh();assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);f.approval.put("privateIp","10.92.0.44");f.approval.put("purpose","RESUME");f.refresh();assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);}
    @Test void shortExpiredAndStaleAttestationsRefused()throws Exception {var f=new RecoveryFixture(1,2,UUID.randomUUID());for(long seconds:List.of(-1L,44L)){f.approval.put("expiresAt",RecoveryFixture.NOW.plusSeconds(seconds).toString());f.refresh();assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);}f.approval.put("expiresAt",RecoveryFixture.NOW.plusSeconds(300).toString());f.approval.put("targetProofCapturedAt",RecoveryFixture.NOW.minusSeconds(121).toString());f.target.put("capturedAtUtc",f.approval.get("targetProofCapturedAt"));f.refresh();assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);}
    @Test void missingGatewayPauseAndInFlightWorkRefused()throws Exception {var f=new RecoveryFixture(1,2,UUID.randomUUID());var admissions=new TreeMap<String,Object>();for(String n:RecoveryAuthorization.ADMISSIONS)admissions.put(n,Map.of("writesPaused",true,"deliveryPaused",true,"inFlight",0));admissions.remove("api-gateway");f.freeze.put("admissions",admissions);f.refresh();var a=f.authority();assertThatThrownBy(()->a.verifyFreeze(f.freezeBytes)).isInstanceOf(IllegalStateException.class);admissions.put("api-gateway",Map.of("writesPaused",false,"deliveryPaused",true,"inFlight",1));f.refresh();var b=f.authority();assertThatThrownBy(()->b.verifyFreeze(f.freezeBytes)).isInstanceOf(IllegalStateException.class);}
    @Test void coerciveSchemaFenceAndBooleansRefused()throws Exception {
        for(Object value:List.of("1",new java.math.BigInteger("4294967297"),1.0)) {
            var f=new RecoveryFixture(1,2,UUID.randomUUID());f.plan.put("schemaVersion",value);f.refresh();assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);
        }
        for(Object value:List.of("12",new java.math.BigInteger("4294967308"),12.0)) {
            var f=new RecoveryFixture(1,2,UUID.randomUUID());((Map<String,Object>)f.target.get("verification")).put("fenceCount",value);f.refresh();assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);
        }
        for(String field:List.of("restorationReady","deliveryResume","sourceLineageVerified")) {
            var f=new RecoveryFixture(1,2,UUID.randomUUID());((Map<String,Object>)f.target.get("verification")).put(field,"false");f.refresh();assertThatThrownBy(f::authority).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void oversizedInFlightAndAliasedPrimaryIpv4Refused()throws Exception {
        var f=new RecoveryFixture(1,2,UUID.randomUUID());((Map<String,Object>)f.freeze.get("admissions")).put("api-gateway",Map.of("writesPaused",true,"deliveryPaused",true,"inFlight",new java.math.BigInteger("18446744073709551616")));f.refresh();var a=f.authority();assertThatThrownBy(()->a.verifyFreeze(f.freezeBytes)).isInstanceOf(IllegalStateException.class);
        for(String ip:List.of("010.092.000.003","10.92.0.003","10.092.0.44")){var other=new RecoveryFixture(1,2,UUID.randomUUID());other.approval.put("privateIp",ip);other.target.put("privateIp",ip);other.refresh();assertThatThrownBy(other::authority).isInstanceOf(IllegalStateException.class);}
    }
    @Test void ordinaryRuntimeAndCliOverridesCannotEnterBootstrap() {
        assertThatThrownBy(()->StudentErasureRecoveryMain.requireBootstrap(new String[]{"--policy=caller"},Map.of("APP_RECOVERY_BOOTSTRAP","REVIEWED_DEV_ISOLATED"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->StudentErasureRecoveryMain.requireBootstrap(new String[0],Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->StudentErasureRecoveryMain.requireBootstrap(new String[0],Map.of("APP_RECOVERY_BOOTSTRAP","REVIEWED_DEV_ISOLATED","K_SERVICE","school"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void callerWritableLocalPolicyCannotBecomeProtectedTrustMount()throws Exception {
        java.nio.file.Path file=java.nio.file.Files.createTempFile("recovery-untrusted-policy-",".json");
        try{java.nio.file.Files.writeString(file,"{\"project\":\"custoking-dev\"}");assertThatThrownBy(()->StudentErasureRecoveryMain.protectedRead(file,1024)).isInstanceOf(Exception.class);}finally{java.nio.file.Files.deleteIfExists(file);}
    }
    @Test void passwordDecodingPreservesSpacesAndRejectsInvalidUtf8OrTerminators() {
        assertThat(StudentErasureRecoveryMain.decodePassword(" pass with spaces ".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(" pass with spaces ");
        for(byte[] bad:List.of(new byte[]{(byte)0xc3,0x28},"password\n".getBytes(),"password\r".getBytes(),new byte[]{0}))assertThatThrownBy(()->StudentErasureRecoveryMain.decodePassword(bad)).isInstanceOf(IllegalStateException.class);
    }
    @Test void worldReadableCredentialOrUnisolatedParentRefused() {
        var privateFile=java.nio.file.attribute.PosixFilePermissions.fromString("r--r-----");var isolated=java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---");
        StudentErasureRecoveryMain.requireCredentialPermissions(privateFile,isolated);
        assertThatThrownBy(()->StudentErasureRecoveryMain.requireCredentialPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"),isolated)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->StudentErasureRecoveryMain.requireCredentialPermissions(privateFile,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void duplicateJsonAndUnsupportedProtectedPolicyEnvironmentRefused(){assertThatThrownBy(()->RecoveryAuthorization.parse("{\"x\":1,\"x\":2}".getBytes(),1024)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->StudentErasureRecoveryMain.protectedRead(java.nio.file.Path.of("does-not-exist"),1024)).isInstanceOf(Exception.class);}
}
