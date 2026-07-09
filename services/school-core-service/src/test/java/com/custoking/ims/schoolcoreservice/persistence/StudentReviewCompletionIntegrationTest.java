package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentReviewCompletionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static StudentReadRepository repo;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("student").defaultSchema("student")
                .locations("classpath:db/migration/student")
                .load().migrate();
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        // OutboxWriter writes to <schema>.outbox_events; point it at the student schema and
        // create a minimal matching table so completeCampaign's append has a target.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS student.outbox_events (
                        id BIGSERIAL PRIMARY KEY,
                        event_key TEXT, event_type TEXT, aggregate_type TEXT, aggregate_id TEXT,
                        school_id BIGINT, payload JSONB, status TEXT DEFAULT 'PENDING',
                        trace_parent TEXT, trace_state TEXT,
                        created_at TIMESTAMPTZ DEFAULT now())
                    """);
        } catch (Exception e) { throw new RuntimeException(e); }
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "student");
        repo = new StudentReadRepository(jdbc, null, outbox);
    }

    @AfterAll
    static void tearDown() { if (PG != null) PG.stop(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM student.student_review_items");
            st.execute("DELETE FROM student.student_review_campaigns");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM student.outbox_events");
        }
    }

    /** Seeds a student, a campaign, and N items with the given per-item statuses; returns campaignId. */
    private String seedCampaign(long schoolId, String status, String reviewType, String... itemStatuses) throws Exception {
        String campaignId = java.util.UUID.randomUUID().toString();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO student.student_review_campaigns (id, school_id, review_type, title, status, initiated_at, created_at, updated_at) VALUES ('"
                    + campaignId + "'," + schoolId + ",'" + reviewType + "','T','" + status + "', now(), now(), now())");
            int n = 0;
            for (String s : itemStatuses) {
                String admissionNo = "A" + campaignId.substring(0, 8) + n;
                st.execute("INSERT INTO student.students (admission_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES ('"
                        + admissionNo + "','Test'," + schoolId + ",'class-1','section-1','ay-1')");
                long studentId = jdbc.sql("SELECT id FROM student.students WHERE admission_no= :admissionNo")
                        .param("admissionNo", admissionNo).query(Long.class).single();
                String itemId = java.util.UUID.randomUUID().toString();
                st.execute("INSERT INTO student.student_review_items (id, campaign_id, student_id, school_id, status) VALUES ('"
                        + itemId + "','" + campaignId + "'," + studentId + "," + schoolId + ",'" + s + "')");
                n++;
            }
        }
        return campaignId;
    }

    @Test
    void completeSucceedsWhenAllItemsCompleted() throws Exception {
        String campaignId = seedCampaign(7L, "ACTIVE", "ID_CARD_DETAILS", "COMPLETED", "COMPLETED");
        Map<String, Object> dto = repo.completeCampaign(campaignId, 99L);
        assertThat(dto.get("campaignId")).isEqualTo(campaignId);
        String status = jdbc.sql("SELECT status FROM student.student_review_campaigns WHERE id = :id")
                .param("id", campaignId).query(String.class).single();
        assertThat(status).isEqualTo("COMPLETED");
        Long completedBy = jdbc.sql("SELECT completed_by FROM student.student_review_campaigns WHERE id = :id")
                .param("id", campaignId).query(Long.class).single();
        assertThat(completedBy).isEqualTo(99L);
        Long outboxRows = jdbc.sql("SELECT count(*) FROM student.outbox_events WHERE event_type = 'student-review-campaign.completed.v1' AND aggregate_id = :id")
                .param("id", campaignId).query(Long.class).single();
        assertThat(outboxRows).isEqualTo(1L);
    }

    @Test
    void completeBlockedWhenItemsUnresolved() throws Exception {
        String campaignId = seedCampaign(7L, "ACTIVE", "ID_CARD_DETAILS", "COMPLETED", "PENDING");
        assertThatThrownBy(() -> repo.completeCampaign(campaignId, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still need review");
        String status = jdbc.sql("SELECT status FROM student.student_review_campaigns WHERE id = :id")
                .param("id", campaignId).query(String.class).single();
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void completeBlockedWhenNotActive() throws Exception {
        String campaignId = seedCampaign(7L, "COMPLETED", "ID_CARD_DETAILS", "COMPLETED");
        assertThatThrownBy(() -> repo.completeCampaign(campaignId, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void itemEditRejectedOnCompletedCampaign() throws Exception {
        String campaignId = seedCampaign(7L, "COMPLETED", "ID_CARD_DETAILS", "COMPLETED");
        String itemId = jdbc.sql("SELECT id FROM student.student_review_items WHERE campaign_id = :id LIMIT 1")
                .param("id", campaignId).query(String.class).single();
        Map<String, Object> patch = new HashMap<>();
        patch.put("schoolId", 7L);
        patch.put("verifiedPhoto", true);
        assertThatThrownBy(() -> repo.updateReviewItem(itemId, patch))
                .isInstanceOf(CampaignCompletedException.class);
    }
}
