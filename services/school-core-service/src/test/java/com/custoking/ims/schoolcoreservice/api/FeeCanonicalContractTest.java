package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FeeCanonicalContractTest {
    FeeReadRepository fees;
    MockMvc mvc;

    @BeforeEach void setUp() {
        fees = mock(FeeReadRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new FeeReadController(fees, "token"))
                .setControllerAdvice(new ValidationExceptionHandler()).build();
        TenantContext.set(new TenantContext(17L, "admin@test", "ADMIN", 7L, null, Set.of(),
                Set.of("fee:read", "fee_structure:manage", "fee:assign", "fee:collect")));
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void paymentForwardsScopeAndOriginalSelectorsButNeverCallerActor() throws Exception {
        when(fees.recordPayment(anyMap())).thenReturn(Map.of("paymentId", "p1"));
        mvc.perform(post("/api/v1/fees/payments").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("""
                {"studentId":42,"amount":500,"schoolId":7,"actorId":999,"idempotencyKey":"stable-key",
                 "assignmentId":"old-assignment","academicYearId":"2024-25"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value("p1"));
        verify(fees).recordPayment(argThat(body -> body.get("schoolId").equals(7L)
                && body.get("actorId").equals(17L) && body.get("idempotencyKey").equals("stable-key")
                && body.get("assignmentId").equals("old-assignment") && body.get("academicYearId").equals("2024-25")));
        mvc.perform(post("/api/v1/fees/payments").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("""
                {"studentId":42,"amount":500,"schoolId":8,"idempotencyKey":"other-school"}
                """)).andExpect(status().isForbidden());
        verifyNoMoreInteractions(fees);
    }

    @Test void planAndItemSettingsAreNotDroppedByCanonicalDtos() throws Exception {
        mvc.perform(post("/api/v1/fees/bands").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("""
                {"name":"Annual","schedules":["Annual"],"gracePeriodDays":5,"lateFeeType":"DAILY",
                 "lateFeeAmount":12.5,"lateFeeIntervalDays":2}
                """)).andExpect(status().isOk());
        verify(fees).createBand(argThat(body -> body.get("gracePeriodDays").equals(5)
                && body.get("lateFeeType").equals("DAILY") && body.get("lateFeeAmount").equals(12.5)
                && body.get("lateFeeIntervalDays").equals(2)));
        mvc.perform(put("/api/v1/fees/bands/plan").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("{\"gracePeriodDays\":0,\"lateFeeType\":\"NONE\",\"lateFeeAmount\":0,\"lateFeeIntervalDays\":0}"))
                .andExpect(status().isOk());
        verify(fees).updateBand(eq("plan"), argThat(body -> body.get("gracePeriodDays").equals(0)
                && body.get("lateFeeAmount").equals(0.0) && !body.containsKey("name")));
        mvc.perform(post("/api/v1/fees/items").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("{\"bandId\":\"plan\",\"name\":\"Bus\",\"optional\":true,\"amount\":100.25}"))
                .andExpect(status().isOk());
        verify(fees).createItem(argThat(body -> Boolean.TRUE.equals(body.get("optional"))
                && body.get("amount").equals(new java.math.BigDecimal("100.25"))));
        mvc.perform(put("/api/v1/fees/items/bus").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("{\"optional\":false}"))
                .andExpect(status().isOk());
        verify(fees).updateItem(eq("bus"), argThat(body -> Boolean.FALSE.equals(body.get("optional")) && !body.containsKey("amount")));
    }

    @Test void assignmentRetainsOptionalItemsRuleAndYearWithTrustedActor() throws Exception {
        mvc.perform(post("/api/v1/fees/assignments").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("""
                {"studentId":42,"bandId":"plan","schedule":"Annual","optionalItemIds":["bus"],
                 "discountRuleId":9,"academicYearId":"2025-26","actorId":999}
                """)).andExpect(status().isOk());
        verify(fees).assignFeePlan(argThat(body -> body.get("optionalItemIds").equals(List.of("bus"))
                && body.get("discountRuleId").equals(9L) && body.get("academicYearId").equals("2025-26")
                && body.get("actorId").equals(17L)));
    }

    @Test void lifecycleRoutesDelegateToExistingValidatedOperations() throws Exception {
        mvc.perform(put("/api/v1/fees/bands/plan/installments").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("{\"installments\":[{\"label\":\"Annual\",\"sharePercent\":100}]}"))
                .andExpect(status().isOk());
        verify(fees).saveInstallments(eq("plan"), eq(List.of(Map.of("label", "Annual", "sharePercent", 100))));
        mvc.perform(post("/api/v1/fees/bands/plan/publish").header("X-Fee-Service-Token", "token")).andExpect(status().isOk());
        verify(fees).publishBand("plan", 17L);
        mvc.perform(post("/api/v1/fees/bands/plan/revision").header("X-Fee-Service-Token", "token")).andExpect(status().isOk());
        verify(fees).createBandRevision("plan");
        mvc.perform(get("/api/v1/fees/structure/health").header("X-Fee-Service-Token", "token").param("academicYearId", "2025-26")).andExpect(status().isOk());
        verify(fees).configurationHealth(7L, "2025-26");
        mvc.perform(get("/api/v1/fees/structure/discount-rules").header("X-Fee-Service-Token", "token")).andExpect(status().isOk());
        verify(fees).discountRules(7L, null);
        mvc.perform(post("/api/v1/fees/structure/discount-rules").header("X-Fee-Service-Token", "token")
                .contentType("application/json").content("{\"name\":\"Sibling\"}")).andExpect(status().isOk());
        verify(fees).saveDiscountRule(eq(7L), argThat(body -> body.get("name").equals("Sibling")));
    }

    @Test void lifecycleMutationsRejectReaderAndReceiptStaysAuthenticated() throws Exception {
        TenantContext.set(new TenantContext(17L, "reader@test", "ADMIN", 7L, null, Set.of(), Set.of("fee:read")));
        mvc.perform(post("/api/v1/fees/bands/plan/publish").header("X-Fee-Service-Token", "token")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/fees/payments/p1/receipt/pdf")).andExpect(status().isUnauthorized());
        verifyNoInteractions(fees);
        when(fees.receiptPdfByPaymentId("p1")).thenReturn(new byte[]{1, 2});
        mvc.perform(get("/api/v1/fees/payments/p1/receipt/pdf").header("X-Fee-Service-Token", "token"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(new byte[]{1, 2}));
    }
}
