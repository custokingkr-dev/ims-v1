package com.custoking.ims.studentservice.api.compat;

import com.custoking.ims.studentservice.persistence.StudentReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudentWorkspaceCompatibilityControllerTest {

    @Test
    void updateReviewItemDelegates() {
        StudentReadRepository repo = mock(StudentReadRepository.class);
        when(repo.updateReviewItem("RV-9", Map.of("status", "APPROVED"))).thenReturn(Map.of("ok", true));
        var controller = new StudentWorkspaceCompatibilityController(repo, "tok");

        assertThat(controller.updateReviewItem("tok", "RV-9", Map.of("status", "APPROVED")))
                .containsEntry("ok", true);
    }

    @Test
    void updateReviewItemRejectsMissingToken() {
        StudentReadRepository repo = mock(StudentReadRepository.class);
        var controller = new StudentWorkspaceCompatibilityController(repo, "tok");

        assertThatThrownBy(() -> controller.updateReviewItem(null, "RV-9", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid student service token");
    }

    @Test
    void updateReviewItemMapsIllegalArgumentTo400() {
        StudentReadRepository repo = mock(StudentReadRepository.class);
        when(repo.updateReviewItem("RV-9", Map.of())).thenThrow(new IllegalArgumentException("schoolId is required"));
        var controller = new StudentWorkspaceCompatibilityController(repo, "tok");

        assertThatThrownBy(() -> controller.updateReviewItem("tok", "RV-9", Map.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("schoolId is required");
    }
}
