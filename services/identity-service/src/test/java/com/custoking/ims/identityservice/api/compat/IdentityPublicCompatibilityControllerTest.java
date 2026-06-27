package com.custoking.ims.identityservice.api.compat;

import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityPublicCompatibilityControllerTest {

    private final IdentityUserProvisioningRepository users = mock(IdentityUserProvisioningRepository.class);
    private final IdentityPublicCompatibilityController controller =
            new IdentityPublicCompatibilityController(users, "tok");

    @Test
    void schoolAdminDelegatesWithAdminRole() {
        when(users.provisionSchoolUser(eq(7L), eq("ADMIN"), eq(Map.of("email", "a@b.c"))))
                .thenReturn(Map.of("id", 1));
        assertThat(controller.createSchoolAdmin("tok", 7L, Map.of("email", "a@b.c")))
                .containsEntry("id", 1);
    }

    @Test
    void rejectsBadToken() {
        assertThatThrownBy(() -> controller.createSchoolAdmin("nope", 7L, Map.of()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
