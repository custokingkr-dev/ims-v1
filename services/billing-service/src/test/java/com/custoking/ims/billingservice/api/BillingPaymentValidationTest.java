package com.custoking.ims.billingservice.api;
import com.custoking.ims.billingservice.api.compat.BillingPublicCompatibilityController;
import com.custoking.ims.billingservice.api.dto.CreateBillingPaymentRequest;
import com.custoking.ims.billingservice.application.BillingInvoiceService;
import com.custoking.ims.billingservice.security.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
class BillingPaymentValidationTest {
    BillingInvoiceService service; MockMvc mvc;
    @BeforeEach void setup(){service=mock(BillingInvoiceService.class);mvc=MockMvcBuilders.standaloneSetup(new BillingPublicCompatibilityController(service,"token")).setControllerAdvice(new ValidationExceptionHandler()).build();TenantContext.set(new TenantContext(1L,"sa@x","SUPERADMIN",null,null));}
    @AfterEach void clear(){TenantContext.clear();}
    @Test void invalidAndMissingAmountsKeysModesRejectedBeforeBusinessMutation() throws Exception {
        for(String json:java.util.List.of(
                "{\"invoiceId\":1,\"amount\":0,\"paymentMode\":\"UPI\",\"idempotencyKey\":\"valid-key\"}",
                "{\"invoiceId\":1,\"amount\":-1,\"paymentMode\":\"UPI\",\"idempotencyKey\":\"valid-key\"}",
                "{\"invoiceId\":1,\"amount\":1,\"paymentMode\":\"UPI\"}",
                "{\"invoiceId\":1,\"amount\":1,\"paymentMode\":\"UNKNOWN\",\"idempotencyKey\":\"valid-key\"}",
                "{\"invoiceId\":1,\"amount\":1.5,\"paymentMode\":\"UPI\",\"idempotencyKey\":\"valid-key\"}"))
            mvc.perform(post("/api/v1/billing-payments").header("X-Billing-Service-Token","token").contentType("application/json").content(json)).andExpect(status().isBadRequest());
        verify(service,never()).createBillingPayment(any(CreateBillingPaymentRequest.class));
    }
    @Test void wrongTokenAndNonSuperadminDeniedBeforeMutation() throws Exception {
        String valid="{\"invoiceId\":1,\"amount\":100,\"paymentMode\":\"UPI\",\"idempotencyKey\":\"valid-key\"}";
        mvc.perform(post("/api/v1/billing-payments").header("X-Billing-Service-Token","wrong").contentType("application/json").content(valid)).andExpect(status().isUnauthorized());
        for(String role:java.util.List.of("ADMIN","SCHOOL_ADMIN","OPERATIONS","PRINCIPAL")) {
            TenantContext.set(new TenantContext(2L,"restricted@x",role,10L,null));
            mvc.perform(post("/api/v1/billing-payments").header("X-Billing-Service-Token","token").contentType("application/json").content(valid)).andExpect(status().isForbidden());
        }
        verify(service,never()).createBillingPayment(any(CreateBillingPaymentRequest.class));
    }
}
