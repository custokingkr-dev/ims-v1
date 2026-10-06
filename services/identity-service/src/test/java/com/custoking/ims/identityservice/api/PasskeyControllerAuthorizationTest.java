package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.PasskeyService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class PasskeyControllerAuthorizationTest {
    @Test void forgedAuthenticatedSessionCannotReplaceTheRouteCapability() {
        var service=mock(PasskeyService.class);var controller=new PasskeyController(service,"private-identity-token");
        var denied=assertThrows(ResponseStatusException.class,()->controller.status("wrong-token","forged-session"));
        assertEquals(401,denied.getStatusCode().value());verifyNoInteractions(service);
    }
}
