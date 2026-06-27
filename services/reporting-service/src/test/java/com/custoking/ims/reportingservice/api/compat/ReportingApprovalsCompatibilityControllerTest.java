package com.custoking.ims.reportingservice.api.compat;

import com.custoking.ims.reportingservice.persistence.ReportingApprovalRepository;
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

    @Test
    void approvalsRejectsInvalidTokenBeforeDelegating() {
        assertThatThrownBy(() -> controller.approvals("wrong-token", 100))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(approvals, never()).approvals(100);
    }

    @Test
    void approvalsDelegatesLimit() {
        List<Map<String, Object>> rows = List.of(Map.of("id", "catalog:CK-1"));
        when(approvals.approvals(25)).thenReturn(rows);

        assertThat(controller.approvals("reporting-token", 25)).isSameAs(rows);
    }

    @Test
    void decideDelegatesTypedApprovalIdAndAction() {
        Map<String, Object> request = Map.of("decisionNote", "Reviewed");
        Map<String, Object> result = Map.of("id", "catalog:CK-1", "status", "APPROVED");
        when(approvals.decide("catalog:CK-1", "approve", request)).thenReturn(result);

        assertThat(controller.decide("reporting-token", "catalog:CK-1", "approve", request)).isSameAs(result);
    }

    @Test
    void decideMapsMissingApprovalToNotFound() {
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
