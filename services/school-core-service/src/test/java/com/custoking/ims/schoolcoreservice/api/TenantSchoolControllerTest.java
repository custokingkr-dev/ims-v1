package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository.ModuleEntitlementRow;
import com.custoking.ims.schoolcoreservice.persistence.SchoolEntity;
import com.custoking.ims.schoolcoreservice.persistence.SchoolRepository;
import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository.SuperadminSchoolStatsRow;
import com.custoking.ims.schoolcoreservice.persistence.ZoneCommandRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneEntity;
import com.custoking.ims.schoolcoreservice.persistence.ZoneRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSchoolControllerTest {

    private final SchoolRepository schools = mock(SchoolRepository.class);
    private final ZoneRepository zones = mock(ZoneRepository.class);
    private final ModuleEntitlementReadRepository modules = mock(ModuleEntitlementReadRepository.class);
    private final SchoolStructureReadRepository structure = mock(SchoolStructureReadRepository.class);
    private final ZoneCommandRepository zoneCommands = mock(ZoneCommandRepository.class);
    private final TenantSchoolController controller = new TenantSchoolController(
            schools,
            zones,
            modules,
            structure,
            zoneCommands,
            "tenant-token");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void schoolsRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.schools("wrong-token"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(schools, never()).findAllByOrderByNameAsc();
    }

    @Test
    void schoolsMapsEntitiesToReadResponses() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        SchoolEntity school = school(4L, "Delhi Public School", "DPS");
        when(schools.findAllByOrderByNameAsc()).thenReturn(List.of(school));

        List<TenantSchoolController.SchoolResponse> response = controller.schools("tenant-token");

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().id()).isEqualTo(4L);
        assertThat(response.getFirst().name()).isEqualTo("Delhi Public School");
        assertThat(response.getFirst().shortCode()).isEqualTo("DPS");
    }

    @Test
    void schoolReturnsNotFoundForMissingSchool() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(schools.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.school("tenant-token", 404L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("school not found");
                });
    }

    @Test
    void createSchoolMapsValidationFailureToBadRequest() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("shortCode", "DPS");
        when(structure.createSchool(request)).thenThrow(new IllegalArgumentException("name is required"));

        assertThatThrownBy(() -> controller.createSchool("tenant-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("name is required");
                });
    }

    @Test
    void updateSchoolMapsNotFoundMessageToNotFound() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("name", "Updated School");
        when(structure.updateSchool(404L, request)).thenThrow(new IllegalArgumentException("School not found"));

        assertThatThrownBy(() -> controller.updateSchool("tenant-token", 404L, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("School not found");
                });
    }

    @Test
    void upsertSchoolModuleParsesRequestAndDelegates() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of(
                "enabled", true,
                "plan", "premium",
                "startDate", "2026-04-01",
                "endDate", "2027-03-31",
                "notes", "annual",
                "actorId", "9");
        ModuleEntitlementRow result = new ModuleEntitlementRow(
                55L,
                4L,
                "REPORTS",
                true,
                "premium",
                LocalDate.parse("2026-04-01"),
                LocalDate.parse("2027-03-31"),
                "annual",
                null,
                null,
                9L);
        when(modules.upsert(
                4L,
                "reports",
                true,
                "premium",
                LocalDate.parse("2026-04-01"),
                LocalDate.parse("2027-03-31"),
                "annual",
                9L)).thenReturn(result);

        Object response = controller.upsertSchoolModule("tenant-token", 4L, "reports", request);

        assertThat(response).isSameAs(result);
        verify(modules).upsert(
                4L,
                "reports",
                true,
                "premium",
                LocalDate.parse("2026-04-01"),
                LocalDate.parse("2027-03-31"),
                "annual",
                9L);
    }

    @Test
    void upsertSchoolModuleRejectsInvalidDateBeforeRepositoryAccess() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("startDate", "01-04-2026");

        assertThatThrownBy(() -> controller.upsertSchoolModule("tenant-token", 4L, "reports", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("Invalid date for field startDate: 01-04-2026");
                });

        verify(modules, never()).upsert(4L, "reports", true, null, null, null, null, null);
    }

    @Test
    void schoolAdminFallsBackWhenNoAdminStatsExist() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(structure.schoolStats()).thenReturn(List.of());

        Object response = controller.schoolAdmin("tenant-token", 4L);

        assertThat(response).isEqualTo(Map.of("schoolId", 4L, "email", ""));
    }

    @Test
    void schoolAdminUsesMatchingStatsRow() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(structure.schoolStats()).thenReturn(List.of(new SuperadminSchoolStatsRow(
                4L,
                "Delhi Public School",
                "DPS",
                "Delhi",
                true,
                "admin@school.test",
                0L,
                0L,
                "Jun 2026")));

        Object response = controller.schoolAdmin("tenant-token", 4L);

        assertThat(response).isEqualTo(Map.of(
                "schoolId", 4L,
                "schoolName", "Delhi Public School",
                "email", "admin@school.test"));
    }

    @Test
    void zoneReturnsNotFoundForMissingZone() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        when(zones.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.zone("tenant-token", 404L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("zone not found");
                });
    }

    @Test
    void zonesMapsEntitiesToReadResponses() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        ZoneEntity zone = zone(7L, "North Zone", "NORTH");
        when(zones.findAllByOrderByNameAsc()).thenReturn(List.of(zone));

        List<TenantSchoolController.ZoneResponse> response = controller.zones("tenant-token");

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().id()).isEqualTo(7L);
        assertThat(response.getFirst().name()).isEqualTo("North Zone");
        assertThat(response.getFirst().code()).isEqualTo("NORTH");
    }

    @Test
    void assignZoneAdminDelegatesToZoneCommandRepository() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("userId", "44", "assignedBy", "9");

        controller.assignZoneAdmin("tenant-token", 7L, request);

        verify(zoneCommands).assignZoneAdmin(7L, 44L, 9L);
    }

    @Test
    void retireZoneAdminsDelegatesToZoneCommandRepository() {
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("userIds", List.of("44", 45));

        controller.retireZoneAdmins("tenant-token", 7L, request);

        verify(zoneCommands).retireZoneAdmins(7L, List.of(44L, 45L));
    }

    private SchoolEntity school(Long id, String name, String shortCode) {
        SchoolEntity school = mock(SchoolEntity.class);
        when(school.getId()).thenReturn(id);
        when(school.getName()).thenReturn(name);
        when(school.getShortCode()).thenReturn(shortCode);
        when(school.getCity()).thenReturn("Delhi");
        when(school.getState()).thenReturn("Delhi");
        when(school.getContactEmail()).thenReturn("admin@school.test");
        when(school.getContactPhone()).thenReturn("9876543210");
        when(school.isActive()).thenReturn(true);
        when(school.getConfiguredClassCount()).thenReturn(12);
        when(school.getConfiguredSectionCount()).thenReturn(2);
        when(school.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-06-01T00:00:00Z"));
        return school;
    }

    private ZoneEntity zone(Long id, String name, String code) {
        ZoneEntity zone = mock(ZoneEntity.class);
        when(zone.getId()).thenReturn(id);
        when(zone.getName()).thenReturn(name);
        when(zone.getCode()).thenReturn(code);
        when(zone.getCity()).thenReturn("Delhi");
        when(zone.getState()).thenReturn("Delhi");
        when(zone.getDescription()).thenReturn("North schools");
        when(zone.isActive()).thenReturn(true);
        when(zone.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-06-01T00:00:00Z"));
        when(zone.getUpdatedAt()).thenReturn(OffsetDateTime.parse("2026-06-02T00:00:00Z"));
        when(zone.getCreatedBy()).thenReturn(9L);
        return zone;
    }
}
