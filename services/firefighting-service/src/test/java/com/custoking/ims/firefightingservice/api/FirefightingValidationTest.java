package com.custoking.ims.firefightingservice.api;

import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository;
import com.custoking.ims.firefightingservice.security.TenantContext;
import com.custoking.ims.firefightingservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FirefightingValidationTest {

    private static final String VALID_TOKEN = "ff-token";

    FirefightingReadRepository repo;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        repo = mock(FirefightingReadRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new FirefightingReadController(repo, VALID_TOKEN))
                .setControllerAdvice(new ValidationExceptionHandler())
                .addFilters(new TenantContextFilter())
                .build();
    }

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    // ── POST /requests ───────────────────────────────────────────────────────

    @Test
    void createRequest_missingTitle_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").exists());
        verifyNoInteractions(repo);
    }

    @Test
    void createRequest_blankTitle_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType("application/json")
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").exists());
        verifyNoInteractions(repo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRequest_valid_callsRepoWithExactKeys() throws Exception {
        when(repo.createRequest(anyMap())).thenReturn(Map.of("code", "FF-001", "status", "DRAFT"));
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"Replace extinguishers\",\"category\":\"Health\",\"urgency\":\"HIGH\"," +
                                "\"estimatedBudget\":50000,\"schoolId\":10,\"actorId\":9,\"actorEmail\":\"admin@school.com\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).createRequest(captor.capture());
        assertEquals("Replace extinguishers", captor.getValue().get("title"));
        assertEquals("Health", captor.getValue().get("category"));
        assertEquals("HIGH", captor.getValue().get("urgency"));
        assertEquals(50000L, captor.getValue().get("estimatedBudget"));
        assertEquals(Long.valueOf(10L), captor.getValue().get("schoolId"));
        assertEquals(Long.valueOf(9L), captor.getValue().get("actorId"));
        assertEquals("admin@school.com", captor.getValue().get("actorEmail"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRequest_nonSuperadmin_stampsAuthenticatedSchoolId() throws Exception {
        when(repo.createRequest(anyMap())).thenReturn(Map.of("code", "FF-002", "status", "DRAFT"));
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType("application/json")
                        .content("{\"title\":\"Leaking roof\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).createRequest(captor.capture());
        // TenantScope must have overridden any missing schoolId with the authenticated school
        assertEquals(Long.valueOf(10L), captor.getValue().get("schoolId"));
        assertEquals("Leaking roof", captor.getValue().get("title"));
    }

    @Test
    void createRequest_crossTenantSchoolId_returns403() throws Exception {
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType("application/json")
                        .content("{\"title\":\"Leaking roof\",\"schoolId\":99}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRequest_descriptionAndSummaryAliases_bothPassedToRepo() throws Exception {
        when(repo.createRequest(anyMap())).thenReturn(Map.of("code", "FF-003", "status", "DRAFT"));
        mvc.perform(post("/api/v1/ff/requests")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .header("X-Authenticated-Role", "SUPERADMIN")
                        .contentType("application/json")
                        .content("{\"title\":\"Replace lights\",\"description\":\"Lights are broken\",\"summary\":\"Lights summary\",\"schoolId\":10}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).createRequest(captor.capture());
        // Both alias keys must be present so repo's firstPresent(request, "description", "summary") can pick either
        assertEquals("Lights are broken", captor.getValue().get("description"));
        assertEquals("Lights summary", captor.getValue().get("summary"));
    }

    // ── POST /requests/{code}/quotations ─────────────────────────────────────

    @Test
    void addQuotation_missingVendorName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/ff/requests/FF-001/quotations")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"amount\":120000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.vendorName").exists());
        verifyNoInteractions(repo);
    }

    @Test
    void addQuotation_blankVendorName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/ff/requests/FF-001/quotations")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"vendorName\":\"\",\"amount\":120000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.vendorName").exists());
        verifyNoInteractions(repo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addQuotation_valid_callsRepoWithExactKeys() throws Exception {
        when(repo.addQuotation(anyString(), anyMap())).thenReturn(Map.of("id", "q-1"));
        mvc.perform(post("/api/v1/ff/requests/FF-001/quotations")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"vendorName\":\"ABC Traders\",\"amount\":95000,\"deliveryTimeline\":\"3 days\"," +
                                "\"notes\":\"Includes GST\",\"documentUrl\":\"https://example.com/q.pdf\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).addQuotation(eq("FF-001"), captor.capture());
        assertEquals("ABC Traders", captor.getValue().get("vendorName"));
        assertEquals(95000L, captor.getValue().get("amount"));
        assertEquals("3 days", captor.getValue().get("deliveryTimeline"));
        assertEquals("Includes GST", captor.getValue().get("notes"));
        assertEquals("https://example.com/q.pdf", captor.getValue().get("documentUrl"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void addQuotation_omittedOptionalFields_notPresentInRepoMap() throws Exception {
        when(repo.addQuotation(anyString(), anyMap())).thenReturn(Map.of("id", "q-2"));
        mvc.perform(post("/api/v1/ff/requests/FF-001/quotations")
                        .header("X-Firefighting-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"vendorName\":\"XYZ Supplies\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).addQuotation(eq("FF-001"), captor.capture());
        assertEquals("XYZ Supplies", captor.getValue().get("vendorName"));
        // Optional fields omitted from JSON must not be put into the repo map
        assertEquals(false, captor.getValue().containsKey("amount"));
        assertEquals(false, captor.getValue().containsKey("deliveryTimeline"));
        assertEquals(false, captor.getValue().containsKey("notes"));
        assertEquals(false, captor.getValue().containsKey("documentUrl"));
    }
}
