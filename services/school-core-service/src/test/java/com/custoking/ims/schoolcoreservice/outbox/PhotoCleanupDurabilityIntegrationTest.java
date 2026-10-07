package com.custoking.ims.schoolcoreservice.outbox;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.google.cloud.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhotoCleanupDurabilityIntegrationTest {
    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;
    static JdbcTransactionManager manager;
    static PhotoCleanupQueue queue;
    final String key="schools/synthetic/students/42/photos/owned.jpg";
    @BeforeAll static void setup() throws Exception {
        Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),"Docker required");
        postgres=new PostgreSQLContainer<>("postgres:16").withUsername("postgres").withPassword("owner");postgres.start();
        var data=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=JdbcClient.create(data);manager=new JdbcTransactionManager(data);transaction=new TransactionTemplate(manager);queue=new PhotoCleanupQueue(jdbc);
        java.nio.file.Path repository=java.nio.file.Path.of("").toAbsolutePath();
        while(repository!=null && !java.nio.file.Files.exists(repository.resolve("deploy/local/initdb/00-app-rt.sql"))) repository=repository.getParent();
        if(repository==null)throw new IllegalStateException("Repository bootstrap sources unavailable");
        jdbc.sql(java.nio.file.Files.readString(repository.resolve("deploy/local/initdb/00-app-rt.sql"))).update();
        jdbc.sql("CREATE ROLE ims_school_core_rt NOLOGIN NOINHERIT; CREATE SCHEMA tenant_school; CREATE SCHEMA student; CREATE TABLE student.students(id bigint PRIMARY KEY,school_id bigint NOT NULL)").update();
        postgres.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(repository.resolve("scripts/create-app-rt-role.sql")),"/tmp/actual-runtime-bootstrap.sql");
        var bootstrap=postgres.execInContainer("psql","-U","postgres","-d",postgres.getDatabaseName(),"-v","owner=postgres","-v","app_rt_password=controlled-local-only","-f","/tmp/actual-runtime-bootstrap.sql");
        assertThat(bootstrap.getExitCode()).isZero();
        // Prove the real global/schema owner defaults are active before the restricted queue migration.
        jdbc.sql("CREATE TABLE tenant_school.default_acl_probe(id bigint)").update();
        assertThat(jdbc.sql("SELECT has_table_privilege('app_rt','tenant_school.default_acl_probe','DELETE')").query(Boolean.class).single()).isTrue();
        jdbc.sql("DROP TABLE tenant_school.default_acl_probe").update();
        // Model an additionally broad legacy owner default, so revocation must also remove TRUNCATE/REFERENCES/TRIGGER.
        jdbc.sql("ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA tenant_school GRANT ALL ON TABLES TO app_rt").update();
        try(var stream=PhotoCleanupDurabilityIntegrationTest.class.getResourceAsStream("/db/migration/tenant_school/V30__durable_photo_cleanup.sql")){
            jdbc.sql(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).update();
        }
        try(var stream=PhotoCleanupDurabilityIntegrationTest.class.getResourceAsStream("/db/migration/tenant_school/afterMigrate__dedicated_runtime_acl.sql")){
            String callback=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            jdbc.sql(callback).update();jdbc.sql(callback).update();
        }
    }
    @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
    @BeforeEach void clear(){jdbc.sql("TRUNCATE tenant_school.photo_cleanup_outbox,student.students").update();}
    StudentPhotoStorage photos(Storage storage) {
        var photos=new StudentPhotoStorage("owned-bucket",5,512,5242880,"signer@test");
        ReflectionTestUtils.setField(photos,"cleanupStorage",storage);return photos;
    }
    void enqueue(UUID operation){transaction.executeWithoutResult(status->queue.enqueue(operation,7,42,new StudentPhotoStorage.CleanupTarget("owned-bucket",key)));}
    void due(){jdbc.sql("UPDATE tenant_school.photo_cleanup_outbox SET next_attempt_at=now()-interval '1 second'").update();}
    String state(){return jdbc.sql("SELECT state FROM tenant_school.photo_cleanup_outbox").query(String.class).single();}
    @Test void rollbackCreatesNoIntentAndDuplicateCommittedIntentSurvivesRestart() {
        var operation=UUID.randomUUID();
        assertThatThrownBy(()->transaction.executeWithoutResult(status->{queue.enqueue(operation,7,42,new StudentPhotoStorage.CleanupTarget("owned-bucket",key));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.photo_cleanup_outbox").query(Long.class).single()).isZero();
        enqueue(operation);enqueue(operation);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.photo_cleanup_outbox").query(Long.class).single()).isEqualTo(1);
        var storage=mock(Storage.class); // exact object absent after a crash/previous successful delete
        var restarted=new PhotoCleanupWorker(new PhotoCleanupQueue(jdbc),photos(storage),manager);
        assertThat(restarted.drain()).isEqualTo(1);assertThat(state()).isEqualTo("DONE");
        verify(storage,never()).delete(any(BlobId.class),any(Storage.BlobSourceOption[].class));
    }
    @Test void failurePinsGenerationAndRetryNeverRefreshesOrDeletesReplacement() {
        enqueue(UUID.randomUUID());var storage=mock(Storage.class);var blob=mock(Blob.class);
        when(blob.getGeneration()).thenReturn(41L);when(storage.get(any(BlobId.class),any(Storage.BlobGetOption[].class))).thenReturn(blob);
        when(storage.delete(any(BlobId.class),any(Storage.BlobSourceOption[].class))).thenThrow(new StorageException(503,"controlled-secret-never-recorded")).thenReturn(false);
        var worker=new PhotoCleanupWorker(queue,photos(storage),manager);
        assertThat(worker.drain()).isZero();assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT object_generation FROM tenant_school.photo_cleanup_outbox").query(Long.class).single()).isEqualTo(41);
        assertThat(jdbc.sql("SELECT last_error FROM tenant_school.photo_cleanup_outbox").query(String.class).single()).isEqualTo("OBJECT_CLEANUP_RETRY");
        when(blob.getGeneration()).thenReturn(99L);due();assertThat(new PhotoCleanupWorker(new PhotoCleanupQueue(jdbc),photos(storage),manager).drain()).isEqualTo(1);
        verify(storage,times(1)).get(any(BlobId.class),any(Storage.BlobGetOption[].class));
        var ids=org.mockito.ArgumentCaptor.forClass(BlobId.class);verify(storage,times(2)).delete(ids.capture(),any(Storage.BlobSourceOption[].class));
        assertThat(ids.getAllValues()).allSatisfy(id->assertThat(id.getGeneration()).isEqualTo(41));assertThat(state()).isEqualTo("DONE");
        assertThat(worker.drain()).isZero();
    }
    @Test void expiredLeaseCannotPinOrCompleteAndReclaimedLeaseUsesNewToken() {
        enqueue(UUID.randomUUID());var first=transaction.execute(status->queue.claim()).orElseThrow();
        jdbc.sql("UPDATE tenant_school.photo_cleanup_outbox SET lease_until=now()-interval '1 second'").update();
        var second=transaction.execute(status->queue.claim()).orElseThrow();assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(Boolean.TRUE.equals(transaction.execute(status->queue.pin(first,41)))).isFalse();queue.complete(first);assertThat(state()).isEqualTo("LEASED");
        assertThat(Boolean.TRUE.equals(transaction.execute(status->queue.pin(second,42)))).isTrue();queue.complete(second);assertThat(state()).isEqualTo("DONE");
    }
    @Test void concurrentClaimDoesNotDuplicateAndRecreatedIdentityBlocksObjectAccess() throws Exception {
        enqueue(UUID.randomUUID());var claimed=transaction.execute(status->queue.claim()).orElseThrow();
        var unavailable=transaction.execute(status->queue.claim());assertThat(unavailable).isEmpty();
        jdbc.sql("UPDATE tenant_school.photo_cleanup_outbox SET lease_until=now()-interval '1 second'").update();
        jdbc.sql("INSERT INTO student.students VALUES(42,7)").update();var storage=mock(Storage.class);
        assertThat(new PhotoCleanupWorker(queue,photos(storage),manager).drain()).isZero();assertThat(state()).isEqualTo("BLOCKED");verifyNoInteractions(storage);
        queue.complete(claimed);assertThat(state()).isEqualTo("BLOCKED");
    }
    @Test @Timeout(10) void nativeSkipLockedClaimDoesNotWaitForAnotherUncommittedWorker() throws Exception {
        enqueue(UUID.randomUUID());
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->transaction.execute(status->{var work=queue.claim().orElseThrow();locked.countDown();
                try {release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}return work;}));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long started=System.nanoTime();var unavailable=transaction.execute(status->queue.claim());
            assertThat(unavailable).isEmpty();assertThat((System.nanoTime()-started)/1_000_000).isLessThan(1000);
            release.countDown();assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void forwardMigrationGrantsOnlyOwnedQueueCapabilitiesAndPreservesEvidence() {
        for(String role:List.of("app_rt","ims_school_core_rt")) {
            for(String privilege:List.of("SELECT","INSERT","UPDATE"))
                assertThat(jdbc.sql("SELECT has_table_privilege(:role,'tenant_school.photo_cleanup_outbox',:privilege)").param("role",role).param("privilege",privilege).query(Boolean.class).single()).isTrue();
            for(String privilege:List.of("DELETE","TRUNCATE","REFERENCES","TRIGGER"))
                assertThat(jdbc.sql("SELECT has_table_privilege(:role,'tenant_school.photo_cleanup_outbox',:privilege)").param("role",role).param("privilege",privilege).query(Boolean.class).single()).isFalse();
        }
        transaction.executeWithoutResult(status->{
            jdbc.sql("SET LOCAL ROLE ims_school_core_rt").update();
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ims_school_core_rt");
            queue.enqueue(UUID.randomUUID(),7,42,new StudentPhotoStorage.CleanupTarget("owned-bucket",key));
            var work=queue.claim().orElseThrow();assertThat(queue.pin(work,41)).isTrue();assertThat(queue.complete(work)).isTrue();
        });
        assertThat(state()).isEqualTo("DONE");
        for(String statement:List.of("DELETE FROM tenant_school.photo_cleanup_outbox","TRUNCATE tenant_school.photo_cleanup_outbox")) {
            assertThatThrownBy(()->transaction.executeWithoutResult(status->{jdbc.sql("SET LOCAL ROLE ims_school_core_rt").update();jdbc.sql(statement).update();}))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(state()).isEqualTo("DONE");
    }
    @Test @Timeout(60) void fullOwnerMigrationChainsPreserveRestrictedQueueAclAfterRealBootstrapAndRepeatedCallbacks() throws Exception {
        jdbc.sql("CREATE DATABASE photo_acl_full").update();
        var source=new DriverManagerDataSource(postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/photo_acl_full"),postgres.getUsername(),postgres.getPassword());
        var full=JdbcClient.create(source);
        java.nio.file.Path root=java.nio.file.Path.of("").toAbsolutePath();
        while(root!=null && !java.nio.file.Files.exists(root.resolve("deploy/local/initdb/00-app-rt.sql")))root=root.getParent();
        assertThat(root).isNotNull();
        full.sql(java.nio.file.Files.readString(root.resolve("deploy/local/initdb/00-app-rt.sql"))).update();
        full.sql("CREATE SCHEMA tenant_school; CREATE SCHEMA student").update();
        var bootstrap=postgres.execInContainer("psql","-U","postgres","-d","photo_acl_full","-v","owner=postgres","-v","app_rt_password=controlled-local-only","-f","/tmp/actual-runtime-bootstrap.sql");
        assertThat(bootstrap.getExitCode()).isZero();
        full.sql("ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA tenant_school GRANT ALL ON TABLES TO app_rt").update();
        for(String schema:List.of("tenant_school","student")) {
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/"+schema).load().migrate();
            try(var stream=getClass().getResourceAsStream("/db/migration/"+schema+"/afterMigrate__dedicated_runtime_acl.sql")){
                String callback=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                full.sql(callback).update();full.sql(callback).update();
            }
        }
        for(String role:List.of("app_rt","ims_school_core_rt")) {
            for(String privilege:List.of("SELECT","INSERT","UPDATE"))
                assertThat(full.sql("SELECT has_table_privilege(:role,'tenant_school.photo_cleanup_outbox',:privilege)").param("role",role).param("privilege",privilege).query(Boolean.class).single()).isTrue();
            for(String privilege:List.of("DELETE","TRUNCATE","REFERENCES","TRIGGER"))
                assertThat(full.sql("SELECT has_table_privilege(:role,'tenant_school.photo_cleanup_outbox',:privilege)").param("role",role).param("privilege",privilege).query(Boolean.class).single()).isFalse();
        }
        var fullQueue=new PhotoCleanupQueue(full);var fullTransaction=new TransactionTemplate(new JdbcTransactionManager(source));
        fullTransaction.executeWithoutResult(status->{
            full.sql("SET LOCAL ROLE ims_school_core_rt").update();
            fullQueue.enqueue(UUID.randomUUID(),7,42,new StudentPhotoStorage.CleanupTarget("owned-bucket",key));
            var work=fullQueue.claim().orElseThrow();assertThat(fullQueue.sourceIdentityReused(work)).isFalse();
            assertThat(fullQueue.pin(work,41)).isTrue();assertThat(fullQueue.complete(work)).isTrue();
        });
        assertThat(full.sql("SELECT state FROM tenant_school.photo_cleanup_outbox").query(String.class).single()).isEqualTo("DONE");
        for(String statement:List.of("DELETE FROM tenant_school.photo_cleanup_outbox","TRUNCATE tenant_school.photo_cleanup_outbox"))
            assertThatThrownBy(()->fullTransaction.executeWithoutResult(status->{full.sql("SET LOCAL ROLE ims_school_core_rt").update();full.sql(statement).update();})).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test @Timeout(15) void slowObjectClientIsCancelledWithoutHoldingDatabaseTransaction() {
        enqueue(UUID.randomUUID());var storage=mock(Storage.class);
        when(storage.get(any(BlobId.class),any(Storage.BlobGetOption[].class))).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Thread.sleep(10_000);return null;
        });
        long started=System.nanoTime();assertThat(new PhotoCleanupWorker(queue,photos(storage),manager).drain()).isZero();
        assertThat((System.nanoTime()-started)/1_000_000).isBetween(5500L,8500L);
        assertThat(state()).isEqualTo("PENDING");assertThat(jdbc.sql("SELECT last_error FROM tenant_school.photo_cleanup_outbox").query(String.class).single()).isEqualTo("OBJECT_CLEANUP_RETRY");
        due();assertThat(new PhotoCleanupWorker(queue,photos(mock(Storage.class)),manager).drain()).isEqualTo(1);
    }
    @Test void ownershipAndBucketGuardsRejectForeignObjectsAndConditionalConflictIsSuccess() {
        var storage=mock(Storage.class);var photos=photos(storage);
        assertThatThrownBy(()->photos.cleanupTarget("schools/other/students/42/photos/x.jpg","synthetic",42)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->photos.cleanupTarget("schools/synthetic/students/99/photos/x.jpg","synthetic",42)).isInstanceOf(IllegalArgumentException.class);
        assertThat(photos.cleanupTarget("https://external.test/x.jpg","synthetic",42)).isEmpty();
        assertThatThrownBy(()->photos.photoCleanupGeneration(new StudentPhotoStorage.CleanupTarget("other-bucket",key))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
        when(storage.delete(any(BlobId.class),any(Storage.BlobSourceOption[].class))).thenThrow(new StorageException(412,"replacement generation"));
        photos.deletePhotoGeneration(new StudentPhotoStorage.CleanupTarget("owned-bucket",key),41);
    }
}
