package com.custoking.ims.workflowservice.api;

import com.custoking.ims.workflowservice.api.dto.CreateInstanceRequest;
import com.custoking.ims.workflowservice.persistence.WorkflowReadRepository;
import com.custoking.ims.workflowservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowReadControllerTest {

    private final WorkflowReadRepository workflows = mock(WorkflowReadRepository.class);
    private final WorkflowReadController controller = new WorkflowReadController(workflows, "workflow-token");

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void definitionsRejectsInvalidTokenBeforeQuerying() {
        assertThatThrownBy(() -> controller.definitions("wrong-token", true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(workflows, never()).definitions(true);
    }

    @Test
    void instancesDelegatesFiltersWithValidToken() {
        TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));
        when(workflows.instances(4L, "PENDING", "ORDER", 25)).thenReturn(List.of());

        Object response = controller.instances("workflow-token", 4L, "PENDING", "ORDER", 25);

        assertThat(response).isEqualTo(List.of());
        verify(workflows).instances(4L, "PENDING", "ORDER", 25);
    }

    @Test
    void instanceReturnsNotFoundForMissingWorkflow() {
        when(workflows.instance(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.instance("workflow-token", 404L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(response.getReason()).isEqualTo("workflow instance not found");
                });
    }

    @Test
    void createOrGetInstanceMapsValidationToBadRequest() {
        TenantContext.set(new TenantContext(null, null, "SUPERADMIN", null, null));
        // Simulate repo rejecting a partial create (definitionId missing on new entity)
        CreateInstanceRequest req = new CreateInstanceRequest("ORDER", "order-99", null, null, null);
        when(workflows.createOrGetInstance(any())).thenThrow(new IllegalArgumentException("definitionId is required"));

        assertThatThrownBy(() -> controller.createOrGetInstance("workflow-token", req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(response.getReason()).isEqualTo("definitionId is required");
                });
    }

    @Test
    void approveLegacyDelegatesToCanonicalApproveRoute() {
        Map<String, Object> request = Map.of("actorId", 9L, "notes", "approved");
        Map<String, Object> result = Map.of("id", 1001L, "status", "APPROVED");
        when(workflows.approve(1001L, request)).thenReturn(result);

        Object response = controller.approveLegacy("workflow-token", 1001L, request);

        assertThat(response).isSameAs(result);
        verify(workflows).approve(1001L, request);
    }
}
