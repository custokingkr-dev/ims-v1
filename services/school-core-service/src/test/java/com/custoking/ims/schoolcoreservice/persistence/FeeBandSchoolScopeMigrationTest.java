package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class FeeBandSchoolScopeMigrationTest {

    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void up() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
    }

    @AfterAll
    static void down() { if (PG != null) PG.stop(); }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("fee").defaultSchema("fee")
                .locations("classpath:db/migration/fee")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    @Test
    void copyPerSchool_splitsSharedBands_repointsAssignments_dropsUnassigned() throws Exception {
        flyway("7").clean();          // fresh
        flyway("7").migrate();        // schema up to V7 (global bands, no school_id on bands/items)

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // shared band B1 (assigned in schools 10 and 20), single-school band B2 (school 10),
            // unassigned band B3.
            st.execute("INSERT INTO fee.fee_bands(id,name,class_from,class_to,discount,academic_year_id) VALUES " +
                    "('B1','Shared',1,5,0.0,'AY'),('B2','Single',6,8,0.0,'AY'),('B3','Unused',9,10,0.0,'AY')");
            st.execute("INSERT INTO fee.fee_items(id,name,frequency,amount,band_id) VALUES " +
                    "('I1','Tuition','Annual',1000,'B1'),('I2','Lab','Annual',200,'B2'),('I3','X','Annual',5,'B3')");
            st.execute("INSERT INTO fee.fee_assignments" +
                    "(id,band_discount,manual_discount,surcharge,net_payable,paid_amount,student_id,band_id,academic_year_id,version,school_id) VALUES " +
                    "('A1',0,0,0,1000,0,101,'B1','AY',0,10)," +
                    "('A2',0,0,0,1000,0,201,'B1','AY',0,20)," +
                    "('A3',0,0,0,200,0,102,'B2','AY',0,10)");
        }

        flyway("8").migrate();        // run V8 copy-per-school

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // B1 -> 2 school-owned copies (schools 10 & 20); B2 -> 1 (school 10); B3 gone.
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_bands")).isEqualTo(3);         // 2 + 1
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_bands WHERE school_id IS NULL")).isEqualTo(0);
            assertThat(scalar(st, "SELECT count(DISTINCT school_id) FROM fee.fee_bands WHERE name='Shared'")).isEqualTo(2);
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_bands WHERE name='Unused'")).isEqualTo(0);
            // items copied per band (Shared x2, Single x1); orphan I3 gone.
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_items")).isEqualTo(3);
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_items WHERE school_id IS NULL")).isEqualTo(0);
            // every assignment now points at a band owned by its own school.
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_assignments a " +
                    "JOIN fee.fee_bands b ON b.id=a.band_id WHERE b.school_id <> a.school_id")).isEqualTo(0);
            assertThat(scalar(st, "SELECT count(*) FROM fee.fee_assignments")).isEqualTo(3);
        }
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
}
