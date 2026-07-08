package com.custoking.ims.platformservice.api.compat;

import com.custoking.ims.platformservice.persistence.ReportingApprovalRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportingApprovalsCompatibilityControllerTest {

    private final ReportingApprovalRepository approvals = mock(ReportingApprovalRepository.class);
    private final ReportingApprovalsCompatibilityController controller =
            new ReportingApprovalsCompatibilityController(approvals, "reporting-token");

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void approvalsRejectsInvalidTokenBeforeDelegating() {
        TenantContext.set(new TenantContext(1L, "super@custoking.com", "SUPERADMIN", null, null));

        assertThatThrownBy(() -> controller.approvals("wrong-token", 100))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(approvals, never()).approvals(100);
    }

    @Test
    void approvalsRejectsNonSuperAdminCallerWithForbidden() {
        TenantContext.set(new TenantContext(1L, "admin@custoking.com", "ADMIN", 10L, null));

        assertThatThrownBy(() -> controller.approvals("reporting-token", 100))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(approvals, never()).approvals(100);
    }

    @Test
    void approvalsDelegatesLimit() {
        TenantContext.set(new TenantContext(1L, "super@custoking.com", "SUPERADMIN", null, null));
        List<Map<String, Object>> rows = List.of(Map.of("id", "catalog:CK-1"));
        when(approvals.approvals(25)).thenReturn(rows);

        assertThat(controller.approvals("reporting-token", 25)).isSameAs(rows);
        verify(approvals).approvals(25);
    }

    @Test
    void decideRejectsNonSuperAdminCallerWithForbidden() {
        TenantContext.set(new TenantContext(1L, "admin@custoking.com", "ADMIN", 10L, null));

        assertThatThrownBy(() -> controller.decide("reporting-token", "catalog:CK-1", "approve", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(approvals, never()).decide("catalog:CK-1", "approve", Map.of());
    }

    @Test
    void decideDelegatesTypedApprovalIdAndAction() {
        TenantContext.set(new TenantContext(1L, "super@custoking.com", "SUPERADMIN", null, null));
        Map<String, Object> request = Map.of("decisionNote", "Reviewed");
        Map<String, Object> result = Map.of("id", "catalog:CK-1", "status", "APPROVED");
        when(approvals.decide("catalog:CK-1", "approve", request)).thenReturn(result);

        assertThat(controller.decide("reporting-token", "catalog:CK-1", "approve", request)).isSameAs(result);
        verify(approvals).decide("catalog:CK-1", "approve", request);
    }

    @Test
    void decideMapsMissingApprovalToNotFound() {
        TenantContext.set(new TenantContext(1L, "super@custoking.com", "SUPERADMIN", null, null));
        when(approvals.decide("catalog:missing", "approve", Map.of()))
                .thenThrow(new IllegalArgumentException("Approval not found"));

        assertThatThrownBy(() -> controller.decide("reporting-token", "catalog:missing", "approve", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("Approval not found");
                });
    }
}
