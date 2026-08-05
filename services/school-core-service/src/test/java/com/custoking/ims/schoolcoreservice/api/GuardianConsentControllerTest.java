package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.GuardianConsentRepository;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardianConsentControllerTest {

    private final GuardianConsentRepository guardians = mock(GuardianConsentRepository.class);
    private final StudentReadRepository students = mock(StudentReadRepository.class);
    private final ModuleEntitlementGuard modules = mock(ModuleEntitlementGuard.class);
    private final GuardianConsentController controller =
            new GuardianConsentController(guardians, students, modules, "student-token");

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void overviewScopesStudentToAuthenticatedSchool() {
        TenantContext.set(new TenantContext(1L, "admin@school.test", "ADMIN", 10L, null,
                Set.of(), Set.of("student:read")));
        when(students.schoolIdForStudent(42L)).thenReturn(10L);
        Map<String, Object> expected = Map.of("guardians", List.of());
        when(guardians.overview(42L)).thenReturn(expected);

        assertThat(controller.overview("student-token", 42L)).isSameAs(expected);

        verify(modules).requireModuleEnabled(10L, "STUDENTS");
        verify(guardians).overview(42L);
    }

    @Test
    void guardianMutationRequiresStudentUpdatePermission() {
        TenantContext.set(new TenantContext(1L, "reader@school.test", "ADMIN", 10L, null,
                Set.of(), Set.of("student:read")));

        assertThatThrownBy(() -> controller.addGuardian("student-token", 42L,
                Map.of("fullName", "Ravi Kumar", "relationship", "FATHER")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(guardians, never()).addGuardian(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(students, never()).schoolIdForStudent(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void consentForwardsIdempotencyKey() {
        TenantContext.set(new TenantContext(1L, "admin@school.test", "ADMIN", 10L, null,
                Set.of(), Set.of("student:update")));
        when(students.schoolIdForStudent(42L)).thenReturn(10L);
        Map<String, Object> request = Map.of(
                "purpose", "STUDENT_PHOTO", "status", "GRANTED", "noticeVersion", "notice-v1");
        Map<String, Object> expected = Map.of("consents", List.of());
        when(guardians.recordConsent(42L, request, "request-123")).thenReturn(expected);

        assertThat(controller.recordConsent("student-token", "request-123", 42L, request)).isSameAs(expected);
        verify(guardians).recordConsent(42L, request, "request-123");
    }
}
