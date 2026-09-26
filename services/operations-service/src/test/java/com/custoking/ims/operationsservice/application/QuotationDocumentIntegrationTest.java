package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.infrastructure.QuotationDocumentStorage;
import com.custoking.ims.operationsservice.outbox.OutboxWriter;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository;
import com.custoking.ims.operationsservice.security.TenantAwareDataSource;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuotationDocumentIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static HikariDataSource pool;
    static TenantAwareDataSource dataSource;
    static JdbcClient admin;
    static JdbcClient jdbc;
    QuotationDocumentService service;
    QuotationDocumentStorage storage;
    Map<String, byte[]> objects;
    byte[] png;

    @BeforeAll static void start() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner"); pg.start();
        admin = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        admin.sql("CREATE ROLE app_rt LOGIN PASSWORD 'test-password' NOSUPERUSER NOBYPASSRLS NOINHERIT").update();
        // Deliberately no default grants: the new document table must work after
        // a runtime import that stripped migration-owner default privileges.
        Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("firefighting").defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting").load().migrate();
        admin.sql("GRANT USAGE ON SCHEMA firefighting TO app_rt").update();
        admin.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON firefighting.firefighting_requests,firefighting.ff_quotations,firefighting.outbox_events TO app_rt").update();
        admin.sql("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA firefighting TO app_rt").update();
        pool = new HikariDataSource(); pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername("app_rt"); pool.setPassword("test-password");
        pool.setSchema("firefighting"); pool.setMaximumPoolSize(3);
        dataSource = new TenantAwareDataSource(pool); jdbc = JdbcClient.create(dataSource);
    }
    @AfterAll static void stop() { if (pool != null) pool.close(); if (pg != null) pg.stop(); }
    @AfterEach void clearTenant() { TenantContext.clear(); }
    @BeforeEach void setup() throws Exception {
        admin.sql("TRUNCATE firefighting.ff_quotations, firefighting.firefighting_requests, firefighting.quotation_documents CASCADE").update();
        admin.sql("INSERT INTO firefighting.firefighting_requests(code, school_id, estimated_budget, status) VALUES ('FF-A', 7, 100, 'DRAFT'), ('FF-B', 8, 100, 'DRAFT')").update();
        admin.sql("INSERT INTO firefighting.ff_quotations(id, request_id, school_id, amount, is_custoking, is_recommended) VALUES ('quote-a','FF-A',7,100,false,false), ('quote-b','FF-B',8,100,false,false)").update();
        var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", output); png = output.toByteArray();
        objects = new HashMap<>();
        storage = spy(new QuotationDocumentStorage(""));
        doNothing().when(storage).requireAvailable(); doReturn(true).when(storage).configured();
        doAnswer(invocation -> { objects.put(invocation.getArgument(0), ((QuotationDocumentStorage.ValidatedDocument) invocation.getArgument(1)).bytes()); return null; }).when(storage).write(anyString(), any());
        doAnswer(invocation -> objects.get(invocation.getArgument(0))).when(storage).read(anyString());
        doAnswer(invocation -> { objects.remove(invocation.getArgument(0)); return null; }).when(storage).delete(anyString());
        service = new QuotationDocumentService(jdbc, storage, new DataSourceTransactionManager(dataSource));
        tenant(7, Set.of("firefighting:read", "firefighting:update"));
    }
    void tenant(long school, Set<String> permissions) { TenantContext.set(new TenantContext(77L, "staff@school", "ADMIN", school, null, permissions)); }
    String key(String id) { return admin.sql("SELECT object_key FROM firefighting.quotation_documents WHERE id = :id").param("id", id).query(String.class).single(); }
    String status(String id) { return admin.sql("SELECT status FROM firefighting.quotation_documents WHERE id = :id").param("id", id).query(String.class).single(); }

    @Test void uploadIsPrivateScopedAndAppearsInSavedQuotationMetadata() {
        var result = service.upload("FF-A", "quote-a", png, "vendor.png", "image/png");
        assertThat(service.download("FF-A", "quote-a").bytes()).isEqualTo(png);
        var repository = new FirefightingReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "firefighting"), new ObjectMapper());
        assertThat(repository.quotations("FF-A").getFirst().document()).isEqualTo(result);
        String publicJson = new ObjectMapper().writeValueAsString(repository.quotations("FF-A"));
        assertThat(publicJson).contains("\"document\":", "\"filename\":\"vendor.png\"")
                .doesNotContain("documentId", "documentFilename", "objectKey", "checksumSha256");
        assertThat(repository.detail("FF-A").toString()).doesNotContain("objectKey", "schools/7/");
        clearInvocations(storage);
        tenant(8, Set.of("firefighting:read", "firefighting:update"));
        assertThatThrownBy(() -> service.download("FF-A", "quote-a")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> service.upload("FF-A", "quote-a", png, "x.png", "image/png")).hasMessageContaining("404");
        verifyNoInteractions(storage);
        assertThat(jdbc.sql("SELECT count(*) FROM firefighting.quotation_documents").query(Long.class).single()).isZero();
        TenantContext.clear();
        assertThatThrownBy(service::capabilities).hasMessageContaining("authenticated user");
        assertThat(jdbc.sql("SELECT count(*) FROM firefighting.quotation_documents").query(Long.class).single()).isZero();
    }

    @Test void readOnlyUsersCanDownloadButCannotReplaceAndSubmittedRequestsFreezeEvidence() {
        service.upload("FF-A", "quote-a", png, "vendor.png", "image/png");
        tenant(7, Set.of("firefighting:read"));
        assertThat(service.download("FF-A", "quote-a").bytes()).isEqualTo(png);
        assertThat(service.capabilities()).containsEntry("canUpload", false);
        assertThatThrownBy(() -> service.remove("FF-A", "quote-a")).hasMessageContaining("403");
        tenant(7, Set.of("firefighting:read", "firefighting:update"));
        admin.sql("UPDATE firefighting.firefighting_requests SET status='AWAITING_BURSAR' WHERE code='FF-A'").update();
        assertThatThrownBy(() -> service.upload("FF-A", "quote-a", png, "x.png", "image/png")).hasMessageContaining("409");
        assertThatThrownBy(() -> service.remove("FF-A", "quote-a")).hasMessageContaining("409");
        assertThat(service.download("FF-A", "quote-a").bytes()).isEqualTo(png);
    }

    @Test void runtimeCanCleanUpButCannotRewriteReservedObjectIdentityOrEraseTombstones() {
        var saved = service.upload("FF-A", "quote-a", png, "vendor.png", "image/png");
        for (String sql : new String[] {
                "UPDATE firefighting.quotation_documents SET object_key='forged'",
                "UPDATE firefighting.quotation_documents SET checksum_sha256='forged'",
                "DELETE FROM firefighting.quotation_documents",
                "TRUNCATE firefighting.quotation_documents CASCADE"}) {
            assertThatThrownBy(() -> jdbc.sql(sql).update())
                    .rootCause().isInstanceOfSatisfying(java.sql.SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        service.remove("FF-A", "quote-a");
        service.cleanup();
        assertThat(status(saved.id())).isEqualTo("DELETED");
        assertThat(admin.sql("SELECT count(*) FROM firefighting.quotation_documents").query(Long.class).single()).isEqualTo(1);
    }

    @Test void replacementAndQuotationDeletionRetireObjectsWithoutDeletingLiveEvidence() {
        var first = service.upload("FF-A", "quote-a", png, "first.png", "image/png");
        var second = service.upload("FF-A", "quote-a", png, "second.png", "image/png");
        assertThat(status(first.id())).isEqualTo("RETIRED");
        service.cleanup();
        assertThat(objects).containsKey(key(second.id())).doesNotContainKey(key(first.id()));
        assertThat(status(first.id())).isEqualTo("DELETED");
        admin.sql("DELETE FROM firefighting.ff_quotations WHERE id='quote-a'").update();
        assertThat(status(second.id())).isEqualTo("RETIRED");
        service.cleanup(); assertThat(objects).isEmpty();
        assertThat(TenantContext.get().schoolId()).isEqualTo(7L);
    }

    @Test void ambiguousUploadFailureKeepsOriginalAttachmentAndQueuesPossibleOrphan() {
        var original = service.upload("FF-A", "quote-a", png, "original.png", "image/png");
        doAnswer(invocation -> { objects.put(invocation.getArgument(0), png); throw new IllegalStateException("Storage response lost"); }).when(storage).write(anyString(), any());
        assertThatThrownBy(() -> service.upload("FF-A", "quote-a", png, "replacement.png", "image/png")).hasMessageContaining("response lost");
        assertThat(service.download("FF-A", "quote-a").metadata().id()).isEqualTo(original.id());
        assertThat(objects).hasSize(2);
        service.cleanup(); assertThat(objects).containsOnlyKeys(key(original.id()));
    }

    @Test void submissionDuringUploadPreventsAttachmentAfterApprovalAndStillCleansStorage() {
        doAnswer(invocation -> { objects.put(invocation.getArgument(0), png); admin.sql("UPDATE firefighting.firefighting_requests SET status='AWAITING_BURSAR' WHERE code='FF-A'").update(); return null; }).when(storage).write(anyString(), any());
        assertThatThrownBy(() -> service.upload("FF-A", "quote-a", png, "quote.png", "image/png")).hasMessageContaining("409");
        assertThatThrownBy(() -> service.download("FF-A", "quote-a")).hasMessageContaining("No quotation file");
        service.cleanup(); assertThat(objects).isEmpty();
    }

    @Test void pendingReservationsRecoverFromCrashesAndFailedCleanupRetries() {
        var saved = service.upload("FF-A", "quote-a", png, "quote.png", "image/png");
        service.remove("FF-A", "quote-a");
        admin.sql("UPDATE firefighting.quotation_documents SET status='PENDING', created_at=now()-interval '2 hours' WHERE id=:id").param("id", saved.id()).update();
        doThrow(new IllegalStateException("Storage unavailable")).when(storage).delete(anyString());
        service.cleanup(); assertThat(objects).hasSize(1);
        assertThat(admin.sql("SELECT cleanup_attempts FROM firefighting.quotation_documents WHERE id=:id").param("id", saved.id()).query(Integer.class).single()).isEqualTo(1);
        doAnswer(invocation -> { objects.remove(invocation.getArgument(0)); return null; }).when(storage).delete(anyString());
        admin.sql("UPDATE firefighting.quotation_documents SET next_cleanup_at=now() WHERE id=:id").param("id", saved.id()).update();
        service.cleanup(); assertThat(objects).isEmpty();
        assertThat(status(saved.id())).isEqualTo("DELETED");
    }

    @Test void requestTriggeredCleanupBoundsBothObjectCountAndStartTimeWithoutSleeping() {
        admin.sql("""
                WITH ids AS (SELECT gen_random_uuid()::text AS id FROM generate_series(1,25))
                INSERT INTO firefighting.quotation_documents
                  (id,school_id,request_code,quotation_id,object_key,filename,content_type,size_bytes,checksum_sha256,uploaded_by,status)
                SELECT id,7,'FF-A','quote-a','schools/7/firefighting/quotations/' || id || '.png',
                  'quote.png','image/png',1,repeat('0',64),77,'RETIRED' FROM ids
                """).update();
        assertThat(service.cleanup()).isEqualTo(20);
        assertThat(admin.sql("SELECT count(*) FROM firefighting.quotation_documents WHERE status='RETIRED'").query(Long.class).single()).isEqualTo(5);
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        var bounded = new QuotationDocumentService(jdbc, storage, new DataSourceTransactionManager(dataSource), nanos::get);
        doAnswer(invocation -> { nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(21)); return null; })
                .when(storage).delete(anyString());
        assertThat(bounded.cleanup()).isEqualTo(1);
        assertThat(admin.sql("SELECT count(*) FROM firefighting.quotation_documents WHERE status='RETIRED'").query(Long.class).single()).isEqualTo(4);
        assertThat(TenantContext.get().schoolId()).isEqualTo(7L);
    }
}
