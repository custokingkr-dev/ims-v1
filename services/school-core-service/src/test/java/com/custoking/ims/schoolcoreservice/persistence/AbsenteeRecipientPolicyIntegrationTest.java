package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class AbsenteeRecipientPolicyIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;
    static AbsenteeRecipientPolicyRepository repository;
    static TransactionTemplate transaction;
    static final LocalDate DATE=LocalDate.of(2026,10,7);
    static final String MESSAGE="Synthetic absence message";
    @BeforeAll static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),"Docker required");
        pg=new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");pg.start();
        for (String schema:List.of("tenant_school","student","attendance"))
            Flyway.configure().dataSource(pg.getJdbcUrl(),"owner","owner").schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/"+schema).load().migrate();
        owner=JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),"owner","owner"));
        owner.sql("CREATE ROLE absence_policy_test LOGIN PASSWORD 'test' NOBYPASSRLS").update();
        owner.sql("GRANT USAGE ON SCHEMA student,attendance TO absence_policy_test").update();
        owner.sql("GRANT SELECT ON ALL TABLES IN SCHEMA student,attendance TO absence_policy_test").update();
        var ds=new DriverManagerDataSource(pg.getJdbcUrl(),"absence_policy_test","test");
        repository=new AbsenteeRecipientPolicyRepository(JdbcClient.create(ds));
        transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        owner.sql("INSERT INTO tenant_school.academic_years(id,label,active) VALUES('ay','2026-27',true)").update();
        owner.sql("INSERT INTO tenant_school.schools(id,name,short_code,active,created_at) VALUES(10,'Synthetic A','A',true,now()),(20,'Synthetic B','B',true,now())").update();
        owner.sql("INSERT INTO tenant_school.school_classes(id,name,sort_order) VALUES('c','Class 1',1)").update();
        owner.sql("INSERT INTO tenant_school.school_sections(id,name,active,school_class_id,school_id) VALUES('s','A',true,'c',10)").update();
        owner.sql("INSERT INTO student.students(id,admission_no,full_name,school_id,class_id,section_id,academic_year_id) VALUES(1,'A1','Synthetic A',10,'c','s','ay')").update();
        owner.sql("INSERT INTO student.guardians(id,school_id,full_name,phone,email,contact_verified_at) VALUES('g',10,'Synthetic Guardian','9999999999','test@example.invalid',now())").update();
        owner.sql("INSERT INTO student.student_guardians(id,school_id,student_id,guardian_id,relationship,is_primary) VALUES('link',10,1,'g','GUARDIAN',true)").update();
        owner.sql("INSERT INTO attendance.attendance_daily(id,attendance_date,total_enrolled,present_count,absent_count,locked,school_class_id,section_id,academic_year_id,school_id) VALUES('d',:date,1,0,1,false,'c','s','ay',10)").param("date",DATE).update();
        owner.sql("INSERT INTO attendance.attendance_student_records(id,attendance_daily_id,student_id,school_id,attendance_date,academic_year_id,class_id,section_id,status) VALUES('a','d',1,10,:date,'ay','c','s','ABSENT')").param("date",DATE).update();
        owner.sql("""
                INSERT INTO attendance.absentee_notifications(id,school_id,student_id,class_id,section_id,
                  academic_year_id,attendance_date,parent_contact,channel,message,guardian_id,consent_event_id,
                  consent_notice_version,policy_version,policy_decision,destination_sha256,policy_evaluated_at,policy_expires_at)
                VALUES('n',10,1,'c','s','ay',:date,'9999999999','SMS',:message,'g','grant','v1',
                  'guardian-communications.v2','ALLOW',:hash,now(),now()+interval '2 minutes')
                """).param("date",DATE).param("message",MESSAGE)
                .param("hash",GuardianCommunicationPolicy.destinationSha256("SMS","9999999999")).update();
    }
    @AfterAll static void close(){if(pg!=null)pg.stop();}
    @BeforeEach void reset() {
        owner.sql("DELETE FROM student.student_consent_events").update();
        owner.sql("INSERT INTO student.student_consent_events(id,school_id,student_id,guardian_id,purpose,status,notice_version,evidence_source,effective_at) VALUES('grant',10,1,'g','SCHOOL_COMMUNICATIONS','GRANTED','v1','SCHOOL_RECORD',now()-interval '1 minute')").update();
        owner.sql("UPDATE attendance.attendance_student_records SET status='ABSENT'").update();
        owner.sql("UPDATE attendance.absentee_notifications SET status='QUEUED'").update();
        owner.sql("UPDATE student.student_guardians SET receives_notifications=true").update();
    }
    Map<String,Object> resolve(long school,String hash) {
        return transaction.execute(tx->repository.resolve(school,1,"SMS","school-core:absentee:n","n",DATE,hash)).getFirst();
    }
    @Test void bindsOriginalRequestAndCurrentAbsenceThenUsesFreshOwnerPolicy() {
        var answer=resolve(10,AbsenteeRecipientPolicyRepository.sha256(MESSAGE));
        assertThat(answer).containsEntry("allowed",true).containsEntry("eventId","school-core:absentee:n");
        assertThat(((Map<?,?>)answer.get("policyEvidence")).get("notificationCategory")).isEqualTo("ABSENTEE_ALERT");
        owner.sql("UPDATE attendance.attendance_student_records SET status='PRESENT'").update();
        assertThat(resolve(10,AbsenteeRecipientPolicyRepository.sha256(MESSAGE))).containsEntry("allowed",false).doesNotContainKey("destination");
    }
    @Test void foreignScopeAlteredMessageAndTerminalSourceAreDenied() {
        assertThat(resolve(20,AbsenteeRecipientPolicyRepository.sha256(MESSAGE))).containsEntry("allowed",false);
        assertThat(resolve(10,AbsenteeRecipientPolicyRepository.sha256("altered message"))).containsEntry("allowed",false);
        owner.sql("UPDATE attendance.absentee_notifications SET status='DEAD_LETTER'").update();
        assertThat(resolve(10,AbsenteeRecipientPolicyRepository.sha256(MESSAGE))).containsEntry("allowed",false);
        assertThatThrownBy(()->transaction.execute(tx->repository.resolve(10,1,"SMS","other","n",DATE,AbsenteeRecipientPolicyRepository.sha256(MESSAGE))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void consentAndPreferenceChangesSuppressPreviouslyAdmittedAbsence() {
        owner.sql("INSERT INTO student.student_consent_events(id,school_id,student_id,guardian_id,purpose,status,notice_version,evidence_source) VALUES('withdrawn',10,1,'g','SCHOOL_COMMUNICATIONS','WITHDRAWN','v1','SCHOOL_RECORD')").update();
        assertThat(resolve(10,AbsenteeRecipientPolicyRepository.sha256(MESSAGE))).containsEntry("allowed",false).doesNotContainKey("policyEvidence");
        owner.sql("DELETE FROM student.student_consent_events WHERE id='withdrawn'").update();
        owner.sql("UPDATE student.student_guardians SET receives_notifications=false").update();
        assertThat(resolve(10,AbsenteeRecipientPolicyRepository.sha256(MESSAGE))).containsEntry("allowed",false);
    }
}
