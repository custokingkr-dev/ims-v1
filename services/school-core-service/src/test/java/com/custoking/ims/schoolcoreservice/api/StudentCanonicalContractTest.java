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
            .setControllerAdvice(new ValidationExceptionHandler()).addFilters(new TenantContextFilter()).build();
    @AfterEach void cleanup() { TenantContext.clear(); }
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder req, String permission) {
        return actor(req, permission, "tok");
    }
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder req, String permission, String token) {
        return req.header("X-Student-Service-Token", token).header("X-Authenticated-Role", "ADMIN")
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

    @Test void reviewMutationsUseTheOwningSchoolRatherThanBrowserAttribution() throws Exception {
        when(repo.schoolIdForReviewItem("item-1")).thenReturn(10L);
        when(repo.updateReviewItem(eq("item-1"), anyMap())).thenReturn(Map.of("itemId", "item-1"));
        when(repo.verifyFullName(eq("item-1"), anyMap())).thenReturn(Map.of("itemId", "item-1"));
        for (String suffix : List.of("", "/full-name-verification")) {
            mvc.perform(actor(post("/api/v1/students/reviews/items/item-1" + suffix)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"schoolId\":99,\"confirmed\":true}"), "student:update"))
                    .andExpect(status().isOk());
        }
        verify(repo).updateReviewItem(eq("item-1"), argThat(body -> Long.valueOf(10).equals(body.get("schoolId"))));
        verify(repo).verifyFullName(eq("item-1"), argThat(body -> Long.valueOf(10).equals(body.get("schoolId"))));
    }

    @Test void otherSchoolReviewItemsAndPromotionBatchesCannotBeMutated() throws Exception {
        when(repo.schoolIdForReviewItem("foreign")).thenReturn(99L);
        when(repo.promotionBatchDetail("foreign")).thenReturn(Map.of("id", "foreign", "schoolId", 99L));
        for (String suffix : List.of("", "/full-name-verification")) {
            mvc.perform(actor(post("/api/v1/students/reviews/items/foreign" + suffix)
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), "student:update"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(actor(post("/api/v1/students/promotion-batches/foreign/apply"), "student:update"))
                .andExpect(status().isForbidden());
        mvc.perform(actor(patch("/api/v1/students/promotion-batches/foreign/items/i")
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PROMOTE\"}"), "student:update"))
                .andExpect(status().isForbidden());
        verify(repo, never()).updateReviewItem(any(), anyMap());
        verify(repo, never()).verifyFullName(any(), anyMap());
        verify(repo, never()).applyPromotionBatch(any());
        verify(repo, never()).updatePromotionBatchItem(any(), any(), anyMap());
    }

    @Test void requiredReviewSchoolAndImportDenialsDoNotReachPersistence() throws Exception {
        for (String kind : List.of("id-card", "full-name", "profile", "photo")) {
            mvc.perform(actor(get("/api/v1/students/reviews/" + kind + "/status"), "student:read"))
                    .andExpect(status().isBadRequest());
            mvc.perform(actor(get("/api/v1/students/reviews/" + kind + "/status?schoolId=99"), "student:read"))
                    .andExpect(status().isForbidden());
            mvc.perform(actor(post("/api/v1/students/reviews/" + kind + "/initiate")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), "student:read"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(actor(post("/api/v1/students/imports/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileToken\":\"original\",\"schoolId\":99}"), "student:import"))
                .andExpect(status().isForbidden());
        mvc.perform(actor(post("/api/v1/students/imports/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileToken\":\" \"}"), "student:import"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repo);
    }

    @Test void erasureConfirmationDecodesUnicodeAndReservedCharactersExactlyOnce() throws Exception {
        String confirmation = "A+/% \u0928\u093e\u092e";
        when(repo.schoolIdForStudent(7L)).thenReturn(10L);
        when(repo.deleteStudent(7L, confirmation)).thenReturn(Map.of("id", 7, "deleted", true, "permanent", true));
        String encoded = java.net.URLEncoder.encode(confirmation, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String response = mvc.perform(actor(delete("/api/v1/students/7")
                .header("X-Student-Delete-Confirmation", encoded), "student:delete"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.custoking.ims.schoolcoreservice.contracts.StudentLifecycleContractAssertions.assertSerializedResponse("StudentDeletion", response);
        verify(repo).deleteStudent(7L, confirmation);
    }

    @Test void directoryQueriesKeepNamesSearchPriorityAndPrivateHeaders() throws Exception {
        Map<String, Object> directory = Map.of("items", List.of(), "page", 2, "size", 37, "totalItems", 0,
                "totalPages", 0, "filteredCount", 0, "filteredSections", 0, "search", "N%_John",
                "filters", Map.of("classes", List.of("Class 1"), "sections", List.of("A"), "feeStatuses", List.of("Paid")));
        when(repo.workspaceStudents(10L, "Class 1", "A", "Paid", "N%_John", 2, 37)).thenReturn(directory);
        for (String path : List.of("/api/v1/students", "/api/v1/students/workspace")) {
            var request = get(path).param("schoolId", "10").param("feeStatus", "Paid").param("q", " N%_John ")
                    .param("search", "ignored").param("page", "2").param("size", "37")
                    .param(path.endsWith("workspace") ? "className" : "class", "Class 1")
                    .param(path.endsWith("workspace") ? "sectionName" : "section", "A");
            String body = mvc.perform(actor(request, "student:read")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(header().string("Vary", "Authorization")).andReturn().getResponse().getContentAsString();
            com.custoking.ims.schoolcoreservice.contracts.StudentLifecycleContractAssertions.assertEndpointResponse(path, "get", body);
        }
        verify(repo, times(2)).workspaceStudents(10L, "Class 1", "A", "Paid", "N%_John", 2, 37);
    }

    @Test void allTenReadContractsRejectMissingPermissionAndInvalidPeerTokensBeforeQueries() throws Exception {
        for (String path : List.of("", "/workspace?schoolId=10", "/7", "/7/workspace", "/7/history",
                "/review-campaigns", "/review-items")) {
            mvc.perform(actor(get("/api/v1/students" + path), "student:update"))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "private, no-store"));
            mvc.perform(actor(get("/api/v1/students" + path), "student:read", "wrong"))
                    .andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/imports/batches", "/imports/rows", "/imports/usage")) {
            mvc.perform(actor(get("/api/v1/students" + path), "student:read")).andExpect(status().isForbidden());
            mvc.perform(actor(get("/api/v1/students" + path), "student:import", "wrong"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(repo);
    }

    @Test void recordDetailHistoryAndScopedDirectoryReadsCannotCrossSchools() throws Exception {
        when(repo.schoolIdForStudent(7L)).thenReturn(99L);
        for (String path : List.of("/7", "/7/workspace", "/7/history", "?schoolId=99", "/workspace?schoolId=99",
                "/review-campaigns?schoolId=99", "/review-items?schoolId=99")) {
            mvc.perform(actor(get("/api/v1/students" + path), "student:read")).andExpect(status().isForbidden());
        }
        verify(repo, never()).find(anyLong());
        verify(repo, never()).workspaceStudentDetail(anyLong());
        verify(repo, never()).studentHistory(anyLong());
        verifyNoMoreInteractions(ignoreStubs(repo));
    }

    @Test void evidenceListsUseAuthenticatedScopeAndSerializeNullableRecordDates() throws Exception {
        when(repo.importBatches(10L, 37)).thenReturn(List.of(new StudentReadRepository.ImportBatchRow(
                "b1", "preview-1", null, 0, 0, 0, 0, "PREVIEWED", 0, 0, 0, null,
                java.time.OffsetDateTime.parse("2026-10-12T00:00:00Z"), null)));
        when(repo.importRows(10L, "b1", "Valid", 37)).thenReturn(List.of(new StudentReadRepository.ImportRow(
                "r1", 2, "Student", "1", "A", "A1", null, "Valid", null, null, null, "b1")));
        when(repo.importUsageDaily(10L, 7, 37)).thenReturn(List.of(new StudentReadRepository.ImportUsageDailyRow(
                10L, java.time.LocalDate.of(2026, 10, 12), 1L, 0L, 1L, 0L, 0L, 0L, 0L)));
        when(repo.reviewCampaigns(10L, "ID_CARD_DETAILS", "ACTIVE", 37)).thenReturn(List.of());
        when(repo.reviewItems("c1", 10L, "PENDING", 37)).thenReturn(List.of());
        for (String path : List.of("/imports/batches", "/imports/rows", "/imports/usage", "/review-campaigns", "/review-items")) {
            String body = mvc.perform(actor(get("/api/v1/students" + path).param("schoolId", path.startsWith("/imports") ? "99" : "10")
                    .param("limit", "37").param("batchId", "b1").param("days", "7").param("campaignId", "c1")
                    .param("reviewType", "ID_CARD_DETAILS").param("status", path.equals("/imports/rows") ? "Valid" : path.equals("/review-items") ? "PENDING" : "ACTIVE"),
                    path.startsWith("/imports") ? "student:import" : "student:read"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                    .andReturn().getResponse().getContentAsString();
            com.custoking.ims.schoolcoreservice.contracts.StudentLifecycleContractAssertions.assertEndpointResponse("/api/v1/students" + path, "get", body);
        }
        verify(repo).importBatches(10L, 37);
        verify(repo).importRows(10L, "b1", "Valid", 37);
        verify(repo).importUsageDaily(10L, 7, 37);
    }
}
