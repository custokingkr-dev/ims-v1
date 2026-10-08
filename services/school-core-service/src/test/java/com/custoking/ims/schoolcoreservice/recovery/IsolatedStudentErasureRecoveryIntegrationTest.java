package com.custoking.ims.schoolcoreservice.recovery;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual four owner migration chains and source workflow; cloud journal/freeze are bounded fakes. */
class IsolatedStudentErasureRecoveryIntegrationTest {
    static PostgreSQLContainer<?> postgres;static JdbcClient owner,runtime;static TransactionTemplate tx;static DriverManagerDataSource runtimeSource;
    @BeforeAll static void setup(){
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),"Docker required");
        postgres=new PostgreSQLContainer<>("postgres:16").withDatabaseName("custoking_dev").withUsername("owner").withPassword("owner")
          .withCommand("sh","-c","openssl req -new -x509 -days 1 -nodes -out /tmp/recovery-cert.pem -keyout /tmp/recovery-key.pem -subj /CN=localhost >/dev/null 2>&1 && chown postgres:postgres /tmp/recovery-*.pem && chmod 600 /tmp/recovery-key.pem && exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/recovery-cert.pem -c ssl_key_file=/tmp/recovery-key.pem");
        try {
            postgres.start();owner=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),"owner","owner"));
            owner.sql("REVOKE TEMPORARY ON DATABASE custoking_dev FROM PUBLIC").update();
            owner.sql("CREATE ROLE app_rt NOLOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
            owner.sql("CREATE ROLE ims_dev_restore_executor LOGIN PASSWORD 'local-purpose-only' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
            for(String schema:List.of("tenant_school","student","fee","attendance"))Flyway.configure().dataSource(postgres.getJdbcUrl(),"owner","owner").schemas(schema).defaultSchema(schema).locations("classpath:db/migration/"+schema).load().migrate();
            for(String schema:List.of("tenant_school","student","fee","attendance")){
                owner.sql("GRANT USAGE ON SCHEMA "+schema+" TO ims_dev_restore_executor").update();
                owner.sql("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "+schema+" TO ims_dev_restore_executor").update();
                owner.sql("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA "+schema+" TO ims_dev_restore_executor").update();
            }
            owner.sql("REVOKE UPDATE,DELETE ON student.erasure_journal_receipts FROM ims_dev_restore_executor").update();owner.sql("REVOKE DELETE ON tenant_school.photo_cleanup_outbox FROM ims_dev_restore_executor").update();
            runtimeSource=new DriverManagerDataSource(postgres.getJdbcUrl()+"&sslmode=require","ims_dev_restore_executor","local-purpose-only");runtime=JdbcClient.create(runtimeSource);tx=new TransactionTemplate(new JdbcTransactionManager(runtimeSource));tx.setTimeout(45);
            owner.sql("INSERT INTO tenant_school.school_classes(id,name,sort_order) VALUES('c1','1',1) ON CONFLICT(id)DO NOTHING").update();owner.sql("INSERT INTO tenant_school.academic_years(id,label,active) VALUES('recovery-year','2026-27',true)").update();
        }catch(RuntimeException failure){postgres.stop();throw failure;}
    }
    @AfterAll static void cleanup(){if(postgres!=null)postgres.stop();}
    record Fixture(RecoveryFixture inputs,StudentReadRepository repository,long school,long student,String serverIp){}
    Fixture fixture()throws Exception{
        long school=owner.sql("INSERT INTO tenant_school.schools(name,short_code,city,state,active,configured_class_count,configured_section_count,created_at)VALUES('Synthetic recovery',:code,'Synthetic','Synthetic',true,3,2,now()) RETURNING id").param("code","R-"+UUID.randomUUID().toString().substring(0,8)).query(Long.class).single();
        new SchoolStructureReadRepository(owner,new OutboxWriter(owner,new ObjectMapper(),"tenant_school")).updateStructure(school,3,2);
        String section=owner.sql("SELECT id FROM tenant_school.school_sections WHERE school_id=:id AND school_class_id='c1' LIMIT 1").param("id",school).query(String.class).single();long id=owner.sql("SELECT nextval('student.seq_students')").query(Long.class).single();UUID incarnation=UUID.randomUUID();
        String folder=owner.sql("SELECT school_uid::text FROM tenant_school.schools WHERE id=:id").param("id",school).query(String.class).single();String photo="schools/"+folder+"/students/"+id+"/photos/"+"a".repeat(64)+".jpg";
        owner.sql("INSERT INTO student.students(id,school_id,admission_no,full_name,class_id,section_id,academic_year_id,erasure_incarnation,photo_url,created_at)VALUES(:id,:school,:admission,'Synthetic recovery','c1',:section,'recovery-year',:incarnation,:photo,now())").param("id",id).param("school",school).param("admission","REC-"+id).param("section",section).param("incarnation",incarnation).param("photo",photo).update();
        owner.sql("INSERT INTO fee.payment_records(id,student_id,school_id,amount,created_at)VALUES(:key,:id,:school,5,now())").param("key","REC-P-"+id).param("id",id).param("school",school).update();
        owner.sql("INSERT INTO student.student_enrollments(id,student_id,school_id,academic_year_id,class_id,section_id)VALUES(:key,:id,:school,'recovery-year','c1',:section)").param("key","REC-E-"+id).param("id",id).param("school",school).param("section",section).update();
        RecoveryFixture inputs=new RecoveryFixture(id,school,incarnation);
        String serverIp=owner.sql("SELECT inet_server_addr()::text").query(String.class).single();var repository=new StudentReadRepository(runtime,new StudentPhotoStorage("custoking-dev-recovery-fixture",5,512,5242880,""),new OutboxWriter(runtime,new ObjectMapper(),"tenant_school"));repository.setErasureJournal(inputs.journal());
        return new Fixture(inputs,repository,school,id,owner.sql("SELECT inet_server_addr()::text").query(String.class).single());
    }
    IsolatedStudentErasureRecovery executor(Fixture f){return new IsolatedStudentErasureRecovery(f.inputs.authority(),f.repository,f.inputs.journal(),f.inputs.store,runtime,tx,runtimeSource,runtimeSource.getUrl());}
    long count(String table,long student){return owner.sql("SELECT count(*)FROM "+table+" WHERE student_id=:id").param("id",student).query(Long.class).single();}
    long source(long id){return owner.sql("SELECT count(*)FROM student.students WHERE id=:id").param("id",id).query(Long.class).single();}
    @Test void actualPurposeTlsSourceTransactionCommitsReceiptOutboxPhotoAndDuplicateIsIdempotent()throws Exception{
        Fixture f=fixture();try(var e=executor(f)){assertThat(e.execute()).containsEntry("completedSourceTasks",1).containsEntry("restorationReady",false).containsEntry("deliveryResume",false);}
        assertThat(source(f.student)).isZero();assertThat(count("fee.payment_records",f.student)).isZero();assertThat(count("student.student_enrollments",f.student)).isZero();assertThat(count("student.erasure_journal_receipts",f.student)).isOne();assertThat(count("tenant_school.photo_cleanup_outbox",f.student)).isOne();
        try(var e=executor(f)){assertThat(e.execute()).containsEntry("completedSourceTasks",1);}assertThat(count("tenant_school.photo_cleanup_outbox",f.student)).isOne();assertThat(f.inputs.store.created).isFalse();
        assertThat(owner.sql("SELECT count(*)FROM tenant_school.outbox_events WHERE event_key=:key").param("key","StudentDeleted:"+f.student).query(Long.class).single()).isOne();
    }
    @Test void freezeChangedBeforeCommitRollsBackEntireOwnerWorkflow()throws Exception{
        Fixture f=fixture();f.inputs.store.mutateAt=2;try(var e=executor(f)){assertThatThrownBy(e::execute).isInstanceOf(IllegalStateException.class);}assertThat(source(f.student)).isOne();assertThat(count("fee.payment_records",f.student)).isOne();assertThat(count("student.student_enrollments",f.student)).isOne();assertThat(count("student.erasure_journal_receipts",f.student)).isZero();assertThat(count("tenant_school.photo_cleanup_outbox",f.student)).isZero();
        assertThat(owner.sql("SELECT count(*)FROM tenant_school.outbox_events WHERE event_key=:key").param("key","StudentDeleted:"+f.student).query(Long.class).single()).isZero();
    }
    @Test void exactGenerationMissingAndReusedIncarnationRefuseBeforeDelete()throws Exception{
        Fixture f=fixture();String key=f.inputs.store.objects.keySet().stream().filter(x->x.startsWith("intents/")).findFirst().orElseThrow();var object=f.inputs.store.objects.remove(key);
        try(var e=executor(f)){assertThatThrownBy(e::execute).isInstanceOf(Exception.class);}assertThat(source(f.student)).isOne();f.inputs.store.objects.put(key,object);
        owner.sql("UPDATE student.students SET school_id=school_id WHERE id=:id").param("id",f.student).update();
        // Bind the plan to a distinct incarnation; canonical journal read succeeds but source must refuse.
        RecoveryFixture different=new RecoveryFixture(f.student,f.school,UUID.randomUUID());var repository=new StudentReadRepository(runtime,new StudentPhotoStorage("custoking-dev-recovery-fixture",5,512,5242880,""),new OutboxWriter(runtime,new ObjectMapper(),"tenant_school"));repository.setErasureJournal(different.journal());
        try(var e=new IsolatedStudentErasureRecovery(different.authority(),repository,different.journal(),different.store,runtime,tx,runtimeSource,runtimeSource.getUrl())){assertThatThrownBy(e::execute).isInstanceOf(Exception.class);}assertThat(source(f.student)).isOne();assertThat(count("student.erasure_journal_receipts",f.student)).isZero();
    }
    @Test void leaseExpirationDuringSourceTransactionRollsBackChildrenAndTerminalWrites()throws Exception {
        Fixture f=fixture();var now=new java.util.concurrent.atomic.AtomicReference<>(RecoveryFixture.NOW);
        java.time.Clock clock=new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return now.get();}};
        var a=new RecoveryAuthorization(RecoveryFixture.JSON.writeValueAsBytes(f.inputs.policy),f.inputs.approvalBytes,f.inputs.planBytes,f.inputs.targetBytes,clock);
        f.inputs.store.onFreezeRead=()->{if(f.inputs.store.freezeReads==2)now.set(RecoveryFixture.NOW.plusSeconds(301));};
        try(var e=new IsolatedStudentErasureRecovery(a,f.repository,f.inputs.journal(),f.inputs.store,runtime,tx,runtimeSource,runtimeSource.getUrl())){assertThatThrownBy(e::execute).isInstanceOf(IllegalStateException.class);}
        assertThat(source(f.student)).isOne();assertThat(count("fee.payment_records",f.student)).isOne();assertThat(count("student.student_enrollments",f.student)).isOne();assertThat(count("student.erasure_journal_receipts",f.student)).isZero();assertThat(count("tenant_school.photo_cleanup_outbox",f.student)).isZero();
    }
    @Test void purposeOwnedTableAndPublicDdlPrivilegeCannotExecute()throws Exception {
        Fixture f=fixture();owner.sql("ALTER TABLE student.student_enrollments OWNER TO ims_dev_restore_executor").update();
        try(var e=executor(f)){assertThatThrownBy(e::execute).isInstanceOf(IllegalStateException.class);}finally{owner.sql("ALTER TABLE student.student_enrollments OWNER TO owner").update();owner.sql("GRANT SELECT,INSERT,UPDATE,DELETE ON student.student_enrollments TO ims_dev_restore_executor").update();}
        owner.sql("GRANT CREATE ON SCHEMA public TO ims_dev_restore_executor").update();
        try(var e=executor(f)){assertThatThrownBy(e::execute).isInstanceOf(IllegalStateException.class);}finally{owner.sql("REVOKE CREATE ON SCHEMA public FROM ims_dev_restore_executor").update();}
        assertThat(source(f.student)).isOne();assertThat(count("student.erasure_journal_receipts",f.student)).isZero();
    }
    @Test void temporaryDdlPrivilegeRefusesBeforeSourceOrTerminalMutation()throws Exception {
        Fixture f=fixture();
        // Even a direct purpose-role TEMP grant must fail; normal execution also removes the
        // otherwise inherited PUBLIC database TEMP privilege in this isolated fixture only.
        owner.sql("GRANT TEMPORARY ON DATABASE custoking_dev TO ims_dev_restore_executor").update();
        try(var e=executor(f)){
            assertThatThrownBy(e::execute).isInstanceOf(IllegalStateException.class);
        }finally{
            owner.sql("REVOKE TEMPORARY ON DATABASE custoking_dev FROM ims_dev_restore_executor").update();
        }
        assertThat(source(f.student)).isOne();
        assertThat(count("fee.payment_records",f.student)).isOne();
        assertThat(count("student.student_enrollments",f.student)).isOne();
        assertThat(count("student.erasure_journal_receipts",f.student)).isZero();
        assertThat(count("tenant_school.photo_cleanup_outbox",f.student)).isZero();
        assertThat(owner.sql("SELECT count(*)FROM tenant_school.outbox_events WHERE event_key=:key")
                .param("key","StudentDeleted:"+f.student).query(Long.class).single()).isZero();
    }
    @Test void mountedPemRawHashAndExistingVerifierDerFingerprintMustBothMatch()throws Exception {
        Fixture f=fixture();byte[] pem=postgres.execInContainer("cat","/tmp/recovery-cert.pem").getStdout().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var certificate=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(pem));f.inputs.target.put("cloneCaPemSha256",RecoveryAuthorization.sha(pem));((Map<String,Object>)f.inputs.target.get("verification")).put("cloneCaSha256",RecoveryAuthorization.sha(certificate.getEncoded()));f.inputs.refresh();
        var proof=RecoveryAuthorization.parse(f.inputs.targetBytes,32768);StudentErasureRecoveryMain.requireCa(pem,proof);assertThat(RecoveryAuthorization.sha(pem)).isNotEqualTo(RecoveryAuthorization.sha(certificate.getEncoded()));
        ((Map<String,Object>)f.inputs.target.get("verification")).put("cloneCaSha256",RecoveryAuthorization.sha(pem));f.inputs.refresh();assertThatThrownBy(()->StudentErasureRecoveryMain.requireCa(pem,RecoveryAuthorization.parse(f.inputs.targetBytes,32768))).isInstanceOf(IllegalStateException.class);
    }
    @Test void unsafePurposeRoleTlsOrTargetMismatchRefused()throws Exception{
        Fixture f=fixture();assertThatThrownBy(()->tx.execute(s->{StudentErasureRecoveryMain.requireTargetBinding(runtime,runtimeSource,"jdbc:postgresql://10.0.0.99:5432/custoking_dev");return null;})).isInstanceOf(IllegalStateException.class);
        var plainSource=new DriverManagerDataSource(runtimeSource.getUrl().replace("sslmode=require","sslmode=disable"),"ims_dev_restore_executor","local-purpose-only");var plainJdbc=JdbcClient.create(plainSource);var plainTx=new TransactionTemplate(new JdbcTransactionManager(plainSource));plainTx.setTimeout(10);
        assertThatThrownBy(()->plainTx.execute(status->{StudentErasureRecoveryMain.requireTargetBinding(plainJdbc,plainSource,plainSource.getUrl());return null;})).isInstanceOf(IllegalStateException.class);
        owner.sql("ALTER ROLE ims_dev_restore_executor BYPASSRLS").update();try(var e=executor(f)){assertThatThrownBy(e::execute).isInstanceOf(IllegalStateException.class);}finally{owner.sql("ALTER ROLE ims_dev_restore_executor NOBYPASSRLS").update();}assertThat(source(f.student)).isOne();
    }
    @Test void epochControlChangedAfterSourceWorkRollsBackEveryTerminalWrite()throws Exception {
        for(String change:List.of("generation","active","missing")){
            Fixture f=fixture();String key="control/"+RecoveryFixture.LINEAGE+"/current.json";
            var original=f.inputs.store.objects.get(key);
            f.inputs.store.onFreezeRead=()->{if(f.inputs.store.freezeReads==2){
                if(change.equals("missing"))f.inputs.store.objects.remove(key);
                else f.inputs.store.objects.put(key,new com.custoking.ims.schoolcoreservice.erasure.ErasureJournalStore.StoredObject(
                    change.equals("generation")?original.generation()+1:original.generation(),
                    change.equals("active")?new String(original.body(),java.nio.charset.StandardCharsets.UTF_8).replace("RECONCILING","ACTIVE").getBytes(java.nio.charset.StandardCharsets.UTF_8):original.body()));
            }};
            try(var e=executor(f)){assertThatThrownBy(e::execute).as(change).isInstanceOf(IllegalStateException.class);}
            assertThat(source(f.student)).as(change).isOne();assertThat(count("fee.payment_records",f.student)).isOne();assertThat(count("student.student_enrollments",f.student)).isOne();assertThat(count("student.erasure_journal_receipts",f.student)).isZero();assertThat(count("tenant_school.photo_cleanup_outbox",f.student)).isZero();
            assertThat(owner.sql("SELECT count(*)FROM tenant_school.outbox_events WHERE event_key=:key").param("key","StudentDeleted:"+f.student).query(Long.class).single()).isZero();
        }
    }
}
