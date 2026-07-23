package com.custoking.ims.identityservice.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityUserProvisioningRepositoryTest {

    @Test
    void temporaryPasswordPolicyAcceptsStrongPassword() {
        assertThatCode(() -> IdentityUserProvisioningRepository.validateTemporaryPassword("SchoolAdmin!2026"))
                .doesNotThrowAnyException();
    }

    @Test
    void temporaryPasswordPolicyRejectsShortPassword() {
        assertBadRequest("Short1!");
    }

    @Test
    void temporaryPasswordPolicyRejectsCommonDefaultPassword() {
        assertBadRequest("Welcome@123");
    }

    @Test
    void temporaryPasswordPolicyRejectsLowComplexityPassword() {
        assertBadRequest("schooladmin2026");
    }

    private static void assertBadRequest(String password) {
        assertThatThrownBy(() -> IdentityUserProvisioningRepository.validateTemporaryPassword(password))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
