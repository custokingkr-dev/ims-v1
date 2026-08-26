package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.GuardianDataReviewRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardianDataReviewControllerTest {

    private final GuardianDataReviewRepository reviews = mock(GuardianDataReviewRepository.class);
    private final ModuleEntitlementGuard modules = mock(ModuleEntitlementGuard.class);
    private final GuardianDataReviewController controller =
            new GuardianDataReviewController(reviews, modules, "student-token");

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void schoolAdminSummaryIsLockedToAuthenticatedSchool() {
        TenantContext.set(new TenantContext(9L, "admin@school.test", "ADMIN", 12L, null,
                Set.of(), Set.of("student:read")));
        Map<String, Object> expected = Map.of("totalCases", 7L);
        when(reviews.summary(12L)).thenReturn(expected);

        assertThat(controller.summary("student-token", null)).isSameAs(expected);

        verify(modules).requireModuleEnabled(12L, "STUDENTS");
        verify(reviews).summary(12L);
    }

    @Test
    void operationsDecisionMustTargetAssignedSchool() {
        TenantContext.set(new TenantContext(9L, "ops@platform.test", "OPERATIONS", null, null,
                Set.of(12L), Set.of("student:read", "student:update")));

        assertThatThrownBy(() -> controller.decide("student-token", "request-1", 13L,
                "a".repeat(64), Map.of("caseSnapshotSha256", "b".repeat(64),
                        "decision", "ESCALATE")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(reviews, never()).decide(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void superadminCanRecordSnapshotBoundDecision() {
        TenantContext.set(new TenantContext(1L, "root@platform.test", "SUPERADMIN", null, null,
                Set.of(), Set.of("platform:admin")));
        String caseId = "a".repeat(64);
        Map<String, Object> request = Map.of(
                "caseSnapshotSha256", "b".repeat(64), "decision", "ACCEPT_NORMALIZED");
        Map<String, Object> expected = Map.of("reviewStatus", "DECIDED");
        when(reviews.decide(12L, caseId, request, "request-1")).thenReturn(expected);

        assertThat(controller.decide("student-token", "request-1", 12L, caseId, request))
                .isSameAs(expected);

        verify(modules).requireModuleEnabled(12L, "STUDENTS");
        verify(reviews).decide(12L, caseId, request, "request-1");
    }
}
