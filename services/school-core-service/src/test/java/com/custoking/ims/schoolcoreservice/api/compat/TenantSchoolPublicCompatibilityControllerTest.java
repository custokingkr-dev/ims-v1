package com.custoking.ims.schoolcoreservice.api.compat;

import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantSchoolPublicCompatibilityControllerTest {

    private final SchoolStructureReadRepository schools = mock(SchoolStructureReadRepository.class);
    private final TenantSchoolPublicCompatibilityController controller =
            new TenantSchoolPublicCompatibilityController(schools, "tok");

    /** MockMvc harness with the real TenantContextFilter for HTTP-header-driven scope tests. */
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(controller)
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void addsStaffUsingSchoolIdFromBody() {
        // Superadmin context; body schoolId 5 is resolved and passed through.
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("schoolId", 5, "name", "Asha");
        when(schools.addStaff(eq(5L), eq(request))).thenReturn(Map.of("id", 1));

        assertThat(controller.addStaffFromWorkspace("tok", request)).containsEntry("id", 1);
        verify(schools).addStaff(5L, request);
    }

    @Test
    void rejectsMissingSchoolId() {
        // Superadmin with no schoolId in body → resolveSchoolId(null) returns null → 400
        TenantContext.set(new TenantContext(1L, "sa@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("name", "Asha");

        assertThatThrownBy(() -> controller.addStaffFromWorkspace("tok", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(schools, never()).addStaff(null, request);
    }

    @Test
    void rejectsInvalidToken() {
        Map<String, Object> request = Map.of("schoolId", 5, "name", "Asha");

        assertThatThrownBy(() -> controller.addStaffFromWorkspace("bad", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(schools, never()).addStaff(5L, request);
    }

    // --- FIX 3: compat cross-tenant test via real TenantContextFilter ---

    @Test
    void addStaffFromWorkspace_crossTenantSchoolId_isForbidden() throws Exception {
        // ADMIN authenticated for school 10 sends schoolId=99 in body → TenantScope rejects → 403
        mvc.perform(post("/api/v1/workspace/staff")
                        .header("X-Tenant-School-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":99,\"name\":\"Asha\"}"))
                .andExpect(status().isForbidden());
        verify(schools, never()).addStaff(eq(99L), any());
    }
}
