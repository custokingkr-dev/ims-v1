package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BroadcastRecipientPolicyIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;
    static BroadcastRecipientPolicyRepository repository;
    static TransactionTemplate transaction;
    @BeforeAll static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner"); pg.start();
        for (String schema : List.of("tenant_school","student")) Flyway.configure().dataSource(pg.getJdbcUrl(),"owner","owner")
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration/"+schema).load().migrate();
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),"owner","owner"));
        owner.sql("CREATE ROLE broadcast_policy_test LOGIN PASSWORD 'test' NOBYPASSRLS").update();
        owner.sql("GRANT USAGE ON SCHEMA student TO broadcast_policy_test").update();
        owner.sql("GRANT SELECT ON ALL TABLES IN SCHEMA student TO broadcast_policy_test").update();
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(),"broadcast_policy_test","test");
        repository = new BroadcastRecipientPolicyRepository(JdbcClient.create(ds)); transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        owner.sql("INSERT INTO tenant_school.academic_years(id,label,active) VALUES ('ay','2026-27',true)").update();
        owner.sql("INSERT INTO tenant_school.schools(id,name,short_code,active,created_at) VALUES (10,'School A','A',true,now()),(20,'School B','B',true,now())").update();
        owner.sql("INSERT INTO tenant_school.school_classes(id,name,sort_order) VALUES ('c','Class 1',1)").update();
        owner.sql("INSERT INTO tenant_school.school_sections(id,name,active,school_class_id,school_id) VALUES ('s','A',true,'c',10),('s2','A',true,'c',20)").update();
        owner.sql("INSERT INTO student.students(id,admission_no,full_name,school_id,class_id,section_id,academic_year_id) VALUES (1,'A1','Synthetic A',10,'c','s','ay'),(2,'B1','Synthetic B',20,'c','s2','ay')").update();
        owner.sql("INSERT INTO student.guardians(id,school_id,full_name,phone,email,contact_verified_at) VALUES ('g',10,'Synthetic Guardian','9999999999','test@example.invalid',now())").update();
        owner.sql("INSERT INTO student.student_guardians(id,school_id,student_id,guardian_id,relationship,is_primary) VALUES ('link',10,1,'g','GUARDIAN',true)").update();
    }
    @AfterAll static void close() { if (pg != null) pg.stop(); }
    @BeforeEach void seedConsent() {
        owner.sql("DELETE FROM student.student_consent_events").update();
        owner.sql("UPDATE student.student_guardians SET receives_notifications = true").update();
        owner.sql("INSERT INTO student.student_consent_events(id,school_id,student_id,guardian_id,purpose,status,notice_version,evidence_source,effective_at) VALUES ('grant',10,1,'g','SCHOOL_COMMUNICATIONS','GRANTED','v1','SCHOOL_RECORD',now()-interval '1 minute')").update();
    }
    List<Map<String,Object>> resolve(long school, UUID broadcast, List<Long> ids) {
        return transaction.execute(tx -> repository.resolve(school,broadcast,List.of("SMS"),ids));
    }
    @Test void currentRevocationOverridesApprovedEvidenceAndFreshReadsKeepStableRequestId() {
        UUID id = UUID.randomUUID(); var allowed = resolve(10,id,null);
        assertThat(allowed).hasSize(1); assertThat(allowed.getFirst()).containsEntry("allowed",true).containsEntry("eventId","broadcast:"+id+":1:SMS");
        assertThat(((Map<?,?>)allowed.getFirst().get("policyEvidence")).get("expiresAt")).isNotNull();
        owner.sql("INSERT INTO student.student_consent_events(id,school_id,student_id,guardian_id,purpose,status,notice_version,evidence_source) VALUES ('withdrawn',10,1,'g','SCHOOL_COMMUNICATIONS','WITHDRAWN','v1','SCHOOL_RECORD')").update();
        var revoked = resolve(10,id,List.of(1L)).getFirst();
        assertThat(revoked).containsEntry("allowed",false).containsEntry("reason","SCHOOL_COMMUNICATIONS_NOT_GRANTED").containsEntry("eventId",allowed.getFirst().get("eventId"));
        assertThat(revoked).doesNotContainKeys("destination","policyEvidence");
    }
    @Test void recipientSelectionCannotCrossSchoolBoundaryAndPreferenceRevalidates() {
        UUID id = UUID.randomUUID();
        assertThat(resolve(20,id,List.of(1L)).getFirst()).containsEntry("allowed",false).containsEntry("reason","STUDENT_NOT_FOUND");
        assertThat(resolve(10,id,null)).extracting(row -> row.get("studentId")).containsExactly(1L);
        owner.sql("UPDATE student.student_guardians SET receives_notifications = false WHERE student_id = 1").update();
        assertThat(resolve(10,id,List.of(1L)).getFirst()).containsEntry("reason","NOTIFICATION_PREFERENCE_DISABLED").doesNotContainKey("destination");
    }
}
