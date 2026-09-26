package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentCanonicalContractTest {
    private final StudentReadRepository repo = mock(StudentReadRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StudentReadController(repo, mock(ImageUrlFetcher.class), "tok"))
            .addFilters(new TenantContextFilter()).build();
    @AfterEach void cleanup() { TenantContext.clear(); }
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder req, String permission) {
        return req.header("X-Student-Service-Token", "tok").header("X-Authenticated-Role", "ADMIN")
            .header("X-Authenticated-User-Id", "77").header("X-Authenticated-School-Id", "10")
            .header("X-Authenticated-Permissions", permission);
    }
    @Test void rosterKeepsIdBasedFiltersArrayAndLimit() throws Exception {
        when(repo.list(10L, "c1", "s1", 37)).thenReturn(List.of());
        mvc.perform(actor(get("/api/v1/students/roster?classId=c1&sectionId=s1&limit=37"), "student:read"))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(repo).list(10L, "c1", "s1", 37);
        mvc.perform(actor(get("/api/v1/students/roster?classId=c1&sectionId=s1&schoolId=99"), "student:read"))
            .andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/v1/students/roster?classId=c1&sectionId=s1"), "student:update"))
            .andExpect(status().isForbidden());
        verify(repo, never()).list(eq(99L), any(), any(), anyInt());
    }
    @Test void updateResolvesSchoolAndPreservesFullProfilePayload() throws Exception {
        when(repo.updateStudent(eq(7L), anyMap())).thenReturn(Map.of("id", 7));
        mvc.perform(actor(put("/api/v1/students/7").contentType(MediaType.APPLICATION_JSON)
            .content("{\"fullName\":\"Student\",\"admissionNumber\":\"A7\",\"classId\":\"c1\",\"sectionId\":\"s1\",\"phone\":\"9999999999\",\"address\":\"Village\"}"), "student:update"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(7));
        verify(repo).updateStudent(eq(7L), argThat(body -> Long.valueOf(10).equals(body.get("schoolId"))
            && "Village".equals(body.get("address")) && "s1".equals(body.get("sectionId"))));
    }
    @Test void updateRejectsReadOnlyAndForgedScopeBeforeMutation() throws Exception {
        mvc.perform(actor(put("/api/v1/students/7").contentType(MediaType.APPLICATION_JSON).content("{}"), "student:read"))
            .andExpect(status().isForbidden());
        mvc.perform(actor(put("/api/v1/students/7").contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":99}"), "student:update"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(repo);
    }
    @Test void repositoryScopeAndProfileErrorsRemain403And400() throws Exception {
        when(repo.updateStudent(eq(7L), anyMap())).thenThrow(new SecurityException("Other school"));
        mvc.perform(actor(put("/api/v1/students/7").contentType(MediaType.APPLICATION_JSON).content("{}"), "student:update"))
            .andExpect(status().isForbidden());
        when(repo.updateStudent(eq(7L), anyMap())).thenThrow(new IllegalArgumentException("Class is required"));
        mvc.perform(actor(put("/api/v1/students/7").contentType(MediaType.APPLICATION_JSON).content("{}"), "student:update"))
            .andExpect(status().isBadRequest());
    }
}
