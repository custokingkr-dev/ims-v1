package com.custoking.ims.workflowservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTenantKeyMigrationTest {

    // ── Part 1: full migration (V1→V4) — shared container ───────────────────
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("workflow")
                .defaultSchema("workflow")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    private boolean isNotNull(String table, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns " +
                     "WHERE table_schema='workflow' AND table_name=? AND column_name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column " + column + " not found in table " + table);
                return "NO".equals(rs.getString(1));
            }
        }
    }

    @Test
    void workflowInstances_schoolId_isNotNull() throws Exception {
        assertTrue(isNotNull("workflow_instances", "school_id"),
                "workflow_instances.school_id must be NOT NULL after V3");
    }

    @Test
    void workflowActions_schoolId_isNotNull() throws Exception {
        assertTrue(isNotNull("workflow_actions", "school_id"),
                "workflow_actions.school_id must be NOT NULL after V4");
    }

    @Test
    void idx_wfInstances_schoolEntity_exists() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_indexes " +
                     "WHERE schemaname='workflow' AND indexname='idx_wf_instances_school_entity'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Index idx_wf_instances_school_entity must exist after V3");
            }
        }
    }

    @Test
    void idx_wfActions_schoolInstance_exists() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pg_indexes " +
                     "WHERE schemaname='workflow' AND indexname='idx_wf_actions_school_instance'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Index idx_wf_actions_school_instance must exist after V3");
            }
        }
    }

    // ── Part 2: backfill logic (fresh container, target V3) ──────────────────
    @Test
    void backfill_actionInheritsSchoolId_fromParentInstance() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");

        try (PostgreSQLContainer<?> pg2 =
                new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner")) {
            pg2.start();

            // Apply only up to V3 — workflow_actions.school_id is nullable at this point
            Flyway.configure()
                    .dataSource(pg2.getJdbcUrl(), "owner", "owner")
                    .schemas("workflow")
                    .defaultSchema("workflow")
                    .locations("classpath:db/migration")
                    .target("3")
                    .load()
                    .migrate();

            try (Connection c = DriverManager.getConnection(pg2.getJdbcUrl(), "owner", "owner")) {
                // INSERT a workflow_instances row with school_id=10
                long instanceId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO workflow.workflow_instances" +
                        "(definition_id, entity_type, entity_id, school_id) VALUES ('SUPPLY_ORDER_DEFAULT', 'ORDER', 'ORD-1', 10)" +
                        " RETURNING id")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Instance insert must return id");
                        instanceId = rs.getLong(1);
                    }
                }

                // INSERT a workflow_actions row with school_id NULL (simulates pre-backfill state)
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO workflow.workflow_actions" +
                        "(instance_id, step_order, action) VALUES (?, 0, 'SUBMIT')")) {
                    ps.setLong(1, instanceId);
                    ps.executeUpdate();
                }

                // Verify the action's school_id is NULL before backfill
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM workflow.workflow_actions WHERE instance_id=?");) {
                    ps.setLong(1, instanceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Action must exist");
                        assertNull(rs.getObject(1), "school_id should be NULL before backfill");
                    }
                }

                // Run the V3 backfill UPDATE statement verbatim
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE workflow.workflow_actions a " +
                        "SET school_id = i.school_id " +
                        "FROM workflow.workflow_instances i " +
                        "WHERE i.id = a.instance_id AND a.school_id IS NULL")) {
                    ps.executeUpdate();
                }

                // Assert the action's school_id is now 10
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT school_id FROM workflow.workflow_actions WHERE instance_id=?")) {
                    ps.setLong(1, instanceId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), "Action must exist after backfill");
                        assertEquals(10L, rs.getLong(1),
                                "workflow_actions.school_id should be 10 after backfill from parent instance");
                    }
                }
            }
        }
    }
}
