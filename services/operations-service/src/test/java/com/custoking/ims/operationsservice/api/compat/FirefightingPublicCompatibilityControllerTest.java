package com.custoking.ims.operationsservice.api.compat;

import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirefightingPublicCompatibilityControllerTest {

    private final FirefightingReadRepository repo = mock(FirefightingReadRepository.class);
    private final FirefightingPublicCompatibilityController controller =
            new FirefightingPublicCompatibilityController(repo, "tok");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void workspaceCreateDelegatesToCreateRequest() {
        TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("title", "Extinguisher", "schoolId", 4L);
        when(repo.createRequest(request)).thenReturn(Map.of("code", "FF-1"));

        assertThat(controller.createFromWorkspace("tok", request)).containsEntry("code", "FF-1");
        verify(repo).createRequest(request);
    }

    @Test
    void workspaceCreateRejectsInvalidToken() {
        Map<String, Object> request = Map.of("title", "Extinguisher");

        assertThatThrownBy(() -> controller.createFromWorkspace("bad", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(repo, never()).createRequest(request);
    }

    @Test
    void workspaceCreateMapsValidationFailureToBadRequest() {
        TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("title", "Extinguisher");
        when(repo.createRequest(any())).thenThrow(new IllegalArgumentException("School not found"));

        assertThatThrownBy(() -> controller.createFromWorkspace("tok", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("School not found");
                });
    }

    @Test
    void markVendorPaidDelegatesToRepository() {
        TenantContext.set(new TenantContext(1L, "s@x", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("schoolId", 4L, "notes", "paid offline");
        when(repo.markVendorPaid(eq("FF-1"), any())).thenReturn(Map.of("code", "FF-1", "vendorPaid", true));

        assertThat(controller.markVendorPaid("tok", "FF-1", request)).containsEntry("vendorPaid", true);
        // applyResolvedSchool copies body to mutable map; SUPERADMIN retains requested schoolId=4
        verify(repo).markVendorPaid(eq("FF-1"), argThat(m -> Long.valueOf(4L).equals(m.get("schoolId"))));
    }
}
