package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A school's order code in the school APIs (item order workflows design D12, §3.5): optional at onboarding, where a blank
 * one is derived and made unique; editable by superadmin afterwards; validated, upper-cased and unique (409 when taken);
 * returned with the school and published with school.upserted.v1.
 */
class SchoolOrderCodeIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;
    static SchoolStructureReadRepository schools;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("tenant_school").defaultSchema("tenant_school")
                .locations("classpath:db/migration/tenant_school").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        schools = new SchoolStructureReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
    }

    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM tenant_school.school_sections").update();
        jdbc.sql("DELETE FROM tenant_school.schools").update();
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();
        jdbc.sql("DELETE FROM tenant_school.school_classes").update();
        jdbc.sql("INSERT INTO tenant_school.school_classes (id, name, sort_order) VALUES ('c1', '1', 1)").update();
    }

    @Test
    void aBlankCodeIsDerivedFromTheNameAndCityAndMadeUnique() {
        var first = schools.createSchool(school("Delhi Public School", "DPS1", "Gurugram", null));
        var second = schools.createSchool(school("Delhi Public School", "DPS2", "Gurgaon", "  "));
        var third = schools.createSchool(school("The Academy", "ACAD", null, null));
        assertThat(first).containsEntry("orderCode", "DPS_GUR");
        assertThat(second).as("a collision appends 2").containsEntry("orderCode", "DPS2_GUR");
        assertThat(third).containsEntry("orderCode", "ACA_IN");
        assertThat(jdbc.sql("SELECT payload ->> 'orderCode' FROM tenant_school.outbox_events WHERE event_type = 'school.upserted.v1' ORDER BY id")
                .query(String.class).list()).containsExactly("DPS_GUR", "DPS2_GUR", "ACA_IN");
    }

    @Test
    void aTypedCodeIsUpperCasedValidatedAndUnique() {
        var created = schools.createSchool(school("Kendriya Vidyalaya", "KV1", "Delhi", " kvs_del "));
        assertThat(created).containsEntry("orderCode", "KVS_DEL");
        assertThatThrownBy(() -> schools.createSchool(school("Kendriya Vidyalaya", "KV2", "Delhi", "KVS_DEL")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("KVS_DEL");
                });
        assertThatThrownBy(() -> schools.createSchool(school("Kendriya Vidyalaya", "KV3", "Delhi", "KVS-DEL")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DPS_GUR");
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.schools").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void superadminChangesTheCodeAndAnOmittedOrBlankOneIsKept() {
        long id = ((Number) schools.createSchool(school("Delhi Public School", "DPS1", "Gurugram", null)).get("id")).longValue();
        schools.createSchool(school("Ryan International", "RIS", "Mumbai", null));

        assertThat(schools.updateSchool(id, Map.of("orderCode", "dps_ggn"))).containsEntry("orderCode", "DPS_GGN");
        assertThat(schools.updateSchool(id, Map.of("name", "Delhi Public School Sector 45"))).as("omitted keeps it")
                .containsEntry("orderCode", "DPS_GGN");
        assertThat(schools.updateSchool(id, Map.of("orderCode", " "))).as("blank keeps it").containsEntry("orderCode", "DPS_GGN");
        assertThat(schools.updateSchool(id, Map.of("orderCode", "DPS_GGN"))).as("its own code is not a conflict").containsEntry("orderCode", "DPS_GGN");
        assertThatThrownBy(() -> schools.updateSchool(id, Map.of("orderCode", "RI_MUM")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> schools.updateSchool(id, Map.of("orderCode", "TOOLONGINIT_DEL")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(schools.schoolStats()).extracting(SchoolStructureReadRepository.SuperadminSchoolStatsRow::orderCode)
                .containsExactlyInAnyOrder("DPS_GGN", "RI_MUM");
    }

    private static Map<String, Object> school(String name, String shortCode, String city, String orderCode) {
        Map<String, Object> request = new HashMap<>();
        request.put("name", name);
        request.put("shortCode", shortCode);
        request.put("city", city);
        request.put("classCount", 1);
        request.put("sectionCount", 1);
        if (orderCode != null) request.put("orderCode", orderCode);
        return request;
    }
}
