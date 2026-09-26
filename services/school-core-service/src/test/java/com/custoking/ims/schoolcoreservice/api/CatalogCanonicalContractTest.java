package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.AnnualPlanConfirmationRepository;
import com.custoking.ims.schoolcoreservice.api.compat.CatalogPublicCompatibilityController;
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
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogCanonicalContractTest {
    private final CatalogReadRepository repo = mock(CatalogReadRepository.class);
    private final AnnualPlanConfirmationRepository plans = mock(AnnualPlanConfirmationRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CatalogReadController(repo, plans, null, "tok"),
            new CatalogPublicCompatibilityController(repo, plans, null, "tok"))
            .addFilters(new TenantContextFilter()).build();
    @AfterEach void cleanup() { TenantContext.clear(); }
    private MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder req, String role, String permissions) {
        return req.header("X-Catalog-Service-Token", "tok").header("X-Authenticated-Role", role)
            .header("X-Authenticated-User-Id", "77").header("X-Authenticated-School-Id", "10")
            .header("X-Authenticated-Operator-Schools", "10,20").header("X-Authenticated-Permissions", permissions);
    }
    @Test void paginatedOperationsReadPreservesFiltersMetadataAndAllSchoolScope() throws Exception {
        when(repo.ordersPage(null, "APPROVED", 2, 7)).thenReturn(Map.of("content", List.of(Map.of("id", "last")),
            "page", 2, "size", 7, "totalElements", 19, "totalPages", 3));
        mvc.perform(actor(get("/api/v1/catalog/orders/page?status=APPROVED&page=2&size=7"), "OPERATIONS", "order:read"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value("last"))
            .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.totalElements").value(19))
            .andExpect(jsonPath("$.totalPages").value(3));
        verify(repo).ordersPage(null, "APPROVED", 2, 7);
        when(repo.orderStats(null)).thenReturn(Map.of("totalOrders", 19));
        mvc.perform(actor(get("/api/v1/catalog/orders/summary"), "OPERATIONS", "order:read"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalOrders").value(19));
        verify(repo).orderStats(null);
    }
    @Test void pageScopesSchoolUsersAndRejectsMissingPermissionOrForgedSchool() throws Exception {
        when(repo.ordersPage(10L, null, 0, 20)).thenReturn(Map.of("content", List.of()));
        mvc.perform(actor(get("/api/v1/catalog/orders/page"), "ADMIN", "order:read")).andExpect(status().isOk());
        verify(repo).ordersPage(10L, null, 0, 20);
        mvc.perform(actor(get("/api/v1/catalog/orders/page?schoolId=99"), "ADMIN", "order:read")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/v1/catalog/orders/page"), "ADMIN", "student:read")).andExpect(status().isForbidden());
        verify(repo, never()).ordersPage(eq(99L), any(), anyInt(), anyInt());
    }
    @Test void statusStillRequiresSuperadminEvenWithUpdatePermission() throws Exception {
        var body = "{\"status\":\"DELIVERED\"}";
        mvc.perform(actor(patch("/api/v1/catalog/orders/o1/status").contentType(MediaType.APPLICATION_JSON).content(body), "ADMIN", "order:update"))
            .andExpect(status().isForbidden());
        verify(repo, never()).updateOrderStatus(anyString(), anyString());
        mvc.perform(actor(patch("/api/v1/catalog/orders/o1/status").contentType(MediaType.APPLICATION_JSON).content(body), "SUPERADMIN", "order:update"))
            .andExpect(status().isOk());
        verify(repo).updateOrderStatus("o1", "DELIVERED");
    }
    @Test void deliveryRequiresAssignedOperationsSchoolAndUsesAuthenticatedActor() throws Exception {
        when(repo.orderSchoolId("own")).thenReturn(Optional.of(20L));
        when(repo.orderSchoolId("other")).thenReturn(Optional.of(99L));
        mvc.perform(actor(post("/api/v1/catalog/orders/own/deliver").contentType(MediaType.APPLICATION_JSON).content("{\"actorId\":999}"), "OPERATIONS", "order:fulfill"))
            .andExpect(status().isOk());
        verify(repo).markDelivered("own", 77L);
        mvc.perform(actor(post("/api/v1/catalog/orders/other/deliver"), "OPERATIONS", "order:fulfill")).andExpect(status().isForbidden());
        mvc.perform(actor(post("/api/v1/catalog/orders/own/deliver"), "ADMIN", "order:update")).andExpect(status().isForbidden());
        verify(repo, never()).markDelivered(eq("other"), any());
    }
    @Test void createCannotInjectLifecycleStatusOrActor() throws Exception {
        for (String status : List.of("DELIVERED", "APPROVED")) {
            mvc.perform(actor(post("/api/v1/catalog/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"UNIFORMS\",\"status\":\"" + status + "\",\"actorId\":999}"), "ADMIN", "order:create"))
                .andExpect(status().isOk());
        }
        verify(repo, times(2)).createOrder(argThat(body -> "DRAFT".equals(body.get("status"))
            && Long.valueOf(77).equals(body.get("actorId")) && Long.valueOf(10).equals(body.get("schoolId"))));
    }
    @Test void annualConfirmationUsesReviewedFingerprintTrustedActorAndBothRoutesKeepGuards() throws Exception {
        when(plans.review(10L)).thenReturn(Map.of("fingerprint", "reviewed"));
        mvc.perform(actor(get("/api/v1/catalog/annual-plan/review"), "ADMIN", "plan:read"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fingerprint").value("reviewed"));
        when(plans.confirm(10L, 77L, "reviewed")).thenReturn(Map.of("confirmed", true, "notificationStatus", "NOT_SENT"));
        for (String path : List.of("/api/v1/catalog/annual-plan/confirm", "/api/v1/supply/annual-plan/confirm")) {
            mvc.perform(actor(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"reviewed\",\"actorId\":999}"), "ADMIN", "plan:manage"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notificationStatus").value("NOT_SENT"));
            mvc.perform(actor(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"), "ADMIN", "plan:read"))
                .andExpect(status().isForbidden());
            mvc.perform(actor(post(path + "?schoolId=99").contentType(MediaType.APPLICATION_JSON).content("{}"), "ADMIN", "plan:manage"))
                .andExpect(status().isForbidden());
        }
        verify(plans, times(2)).confirm(10L, 77L, "reviewed");
        verify(plans, never()).confirm(eq(99L), any(), any());
    }

}
