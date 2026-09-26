package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.SharedQuotaRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class AuthAbuseProtectionTest {
    @Test void schoolHintCannotChargeAnotherSchoolsQuotaWithoutCurrentAccess() {
        SharedQuotaRepository repository = mock(SharedQuotaRepository.class);
        when(repository.consume(anyString(), anyInt(), anyInt())).thenReturn(true);
        var limiter = new AuthAbuseProtection(repository, "test-only-secret-value", 300, 900, 180);
        var principal = new IdentityAuthService.AuthResponse("token", 1L, "Test", "test@example.invalid", "ADMIN", 7L, "School", null, null, List.of(), List.of(), List.of());
        limiter.authenticated(principal, "POST", "/api/v1/students/imports/confirm", 999L);
        verify(repository).consume("import:user:1", 10, 60);
        verify(repository).consume("import:school:7", 30, 60);
        verifyNoMoreInteractions(repository);
    }
    @Test void anOperatorsAssignedTargetSharesTheSameSchoolImportBudget() {
        SharedQuotaRepository repository = mock(SharedQuotaRepository.class);
        when(repository.consume(anyString(), anyInt(), anyInt())).thenReturn(true);
        var limiter = new AuthAbuseProtection(repository, "test-only-secret-value", 300, 900, 180);
        var principal = new IdentityAuthService.AuthResponse("token", 1L, "Test", "test@example.invalid", "OPERATIONS", 7L, "School", null, null, List.of(), List.of(), List.of(9L));
        limiter.authenticated(principal, "POST", "/api/v1/students/imports/confirm", 9L);
        verify(repository).consume("import:user:1", 10, 60);
        verify(repository).consume("import:school:9", 30, 60);
        verifyNoMoreInteractions(repository);
    }
}
