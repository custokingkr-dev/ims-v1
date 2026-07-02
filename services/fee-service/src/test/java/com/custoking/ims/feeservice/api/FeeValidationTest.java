package com.custoking.ims.feeservice.api;

import com.custoking.ims.feeservice.persistence.FeeReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeeValidationTest {

    private static final String VALID_TOKEN = "fee-token";

    FeeReadRepository fees;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        fees = mock(FeeReadRepository.class);
        FeeReadController controller = new FeeReadController(fees, VALID_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ValidationExceptionHandler())
                .build();
    }

    // ─── POST /bands ─────────────────────────────────────────────────────────

    @Test
    void createBand_missingName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/bands")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(fees);
    }

    @Test
    void createBand_blankName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/bands")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(fees);
    }

    @Test
    void createBand_missingSchedules_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/bands")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"General\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.schedules").exists());
        verifyNoInteractions(fees);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createBand_valid_callsRepoWithNameKey() throws Exception {
        when(fees.createBand(anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(post("/api/v1/fees/bands")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"General\",\"classFrom\":1,\"classTo\":10,\"schedules\":[\"Annual\",\"Monthly\"],\"discount\":5.0}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).createBand(captor.capture());
        assertEquals("General", captor.getValue().get("name"));
        assertEquals(1, captor.getValue().get("classFrom"));
        assertEquals(10, captor.getValue().get("classTo"));
        assertEquals(5.0, captor.getValue().get("discount"));
    }

    // ─── POST /items ─────────────────────────────────────────────────────────

    @Test
    void createItem_missingBandId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/items")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"Tuition\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.bandId").exists());
        verifyNoInteractions(fees);
    }

    @Test
    void createItem_missingName_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/items")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"bandId\":\"band-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        verifyNoInteractions(fees);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createItem_valid_callsRepoWithBandIdAndNameKeys() throws Exception {
        when(fees.createItem(anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(post("/api/v1/fees/items")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"bandId\":\"band-1\",\"name\":\"Tuition\",\"frequency\":\"Monthly\",\"amount\":500}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).createItem(captor.capture());
        assertEquals("band-1", captor.getValue().get("bandId"));
        assertEquals("Tuition", captor.getValue().get("name"));
        assertEquals("Monthly", captor.getValue().get("frequency"));
        assertEquals(500L, captor.getValue().get("amount"));
    }

    // ─── POST /assignments ───────────────────────────────────────────────────

    @Test
    void assignFeePlan_missingStudentId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"bandId\":\"band-1\",\"schedule\":\"Annual\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.studentId").exists());
        verifyNoInteractions(fees);
    }

    @Test
    void assignFeePlan_missingBandId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"schedule\":\"Annual\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.bandId").exists());
        verifyNoInteractions(fees);
    }

    @Test
    void assignFeePlan_missingSchedule_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.schedule").exists());
        verifyNoInteractions(fees);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignFeePlan_valid_callsRepoWithRequiredKeys() throws Exception {
        when(fees.assignFeePlan(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\",\"schedule\":\"Annual\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).assignFeePlan(captor.capture());
        assertEquals(1001L, captor.getValue().get("studentId"));
        assertEquals("band-1", captor.getValue().get("bandId"));
        assertEquals("Annual", captor.getValue().get("schedule"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignFeePlan_withBandDiscount_putsBandDiscountKey() throws Exception {
        when(fees.assignFeePlan(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\",\"schedule\":\"Annual\",\"bandDiscount\":10.0}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).assignFeePlan(captor.capture());
        assertTrue(captor.getValue().containsKey("bandDiscount"), "bandDiscount key must be present when sent");
        assertEquals(10.0, captor.getValue().get("bandDiscount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignFeePlan_withoutBandDiscount_doesNotPutBandDiscountKey() throws Exception {
        when(fees.assignFeePlan(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\",\"schedule\":\"Annual\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).assignFeePlan(captor.capture());
        assertFalse(captor.getValue().containsKey("bandDiscount"), "bandDiscount key must NOT be present when not sent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignFeePlan_withActorId_putsActorIdKey() throws Exception {
        when(fees.assignFeePlan(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\",\"schedule\":\"Annual\",\"actorId\":42}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).assignFeePlan(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must be present when sent");
        assertEquals(42L, captor.getValue().get("actorId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignFeePlan_withoutActorId_doesNotPutActorIdKey() throws Exception {
        when(fees.assignFeePlan(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\",\"schedule\":\"Annual\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).assignFeePlan(captor.capture());
        assertFalse(captor.getValue().containsKey("actorId"), "actorId key must NOT be present when not sent");
    }

    // ─── POST /payments ──────────────────────────────────────────────────────

    @Test
    void recordPayment_missingStudentId_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/payments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"amount\":5000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.studentId").exists());
        verifyNoInteractions(fees);
    }

    @Test
    void recordPayment_missingAmount_returns400WithFieldError() throws Exception {
        mvc.perform(post("/api/v1/fees/payments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
        verifyNoInteractions(fees);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPayment_valid_callsRepoWithStudentIdAndAmount() throws Exception {
        when(fees.recordPayment(anyMap())).thenReturn(Map.of("receiptNumber", "RCPT-123"));
        mvc.perform(post("/api/v1/fees/payments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"amount\":5000,\"mode\":\"Cash\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).recordPayment(captor.capture());
        assertEquals(1001L, captor.getValue().get("studentId"));
        assertEquals(5000L, captor.getValue().get("amount"));
        assertEquals("Cash", captor.getValue().get("mode"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPayment_withActorId_putsActorIdKey() throws Exception {
        when(fees.recordPayment(anyMap())).thenReturn(Map.of("receiptNumber", "RCPT-123"));
        mvc.perform(post("/api/v1/fees/payments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"amount\":5000,\"actorId\":42}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).recordPayment(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must be present when sent");
        assertEquals(42L, captor.getValue().get("actorId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPayment_withoutActorId_doesNotPutActorIdKey() throws Exception {
        when(fees.recordPayment(anyMap())).thenReturn(Map.of("receiptNumber", "RCPT-123"));
        mvc.perform(post("/api/v1/fees/payments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"amount\":5000}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).recordPayment(captor.capture());
        assertFalse(captor.getValue().containsKey("actorId"), "actorId key must NOT be present when not sent");
    }
}
