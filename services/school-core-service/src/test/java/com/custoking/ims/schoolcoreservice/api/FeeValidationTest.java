package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.FeeReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
        TenantContext.set(new TenantContext(
                1L,
                "admin@school.test",
                "ADMIN",
                10L,
                null,
                Set.of(),
                Set.of(
                        "fee:read",
                        "fee_structure:read",
                        "payment:read",
                        "fee_structure:manage",
                        "fee:assign",
                        "fee:collect",
                        "payment:create",
                        "notification:send")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
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
    void assignFeePlan_clientSuppliedActorId_isIgnoredInFavorOfTenantContext() throws Exception {
        when(fees.assignFeePlan(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\",\"schedule\":\"Annual\",\"actorId\":42}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).assignFeePlan(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must always be present");
        assertEquals(1L, captor.getValue().get("actorId"), "actorId must come from TenantContext, not the client body");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignFeePlan_withoutActorId_stampsActorIdFromTenantContext() throws Exception {
        when(fees.assignFeePlan(anyMap())).thenReturn(Map.of("ok", true));
        mvc.perform(post("/api/v1/fees/assignments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"bandId\":\"band-1\",\"schedule\":\"Annual\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).assignFeePlan(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must always be present");
        assertEquals(1L, captor.getValue().get("actorId"));
    }

    // ─── PUT /bands/{id} ─────────────────────────────────────────────────────

    @Test
    void updateBand_negativeDiscount_returns400WithFieldError() throws Exception {
        mvc.perform(put("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"discount\":-5.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.discount").exists());
        verifyNoInteractions(fees);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateBand_zeroClassFrom_isAcceptedAndForwarded() throws Exception {
        // classFrom has no lower-bound constraint (the repo has no lower bound either;
        // classFrom=0 is a legitimate pre-KG band). Confirm it is accepted, not 400'd.
        when(fees.updateBand(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(put("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"classFrom\":0}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).updateBand(anyString(), captor.capture());
        assertEquals(0, captor.getValue().get("classFrom"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateBand_onlyNameSent_omitsContainKeyGatedFields() throws Exception {
        when(fees.updateBand(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(put("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"Updated Name\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).updateBand(anyString(), captor.capture());
        assertEquals("Updated Name", captor.getValue().get("name"));
        assertFalse(captor.getValue().containsKey("classFrom"), "classFrom must be absent when not sent");
        assertFalse(captor.getValue().containsKey("classTo"), "classTo must be absent when not sent");
        assertFalse(captor.getValue().containsKey("discount"), "discount must be absent when not sent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateBand_withDiscount_putsDiscountKey() throws Exception {
        when(fees.updateBand(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(put("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"General\",\"classFrom\":1,\"classTo\":10,\"discount\":10.0}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).updateBand(anyString(), captor.capture());
        assertTrue(captor.getValue().containsKey("classFrom"), "classFrom key must be present when sent");
        assertEquals(1, captor.getValue().get("classFrom"));
        assertTrue(captor.getValue().containsKey("discount"), "discount key must be present when sent");
        assertEquals(10.0, captor.getValue().get("discount"));
    }

    // ─── PATCH /bands/{id} ───────────────────────────────────────────────────

    @Test
    void patchBand_negativeDiscount_returns400WithFieldError() throws Exception {
        mvc.perform(patch("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"discount\":-1.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.discount").exists());
        verifyNoInteractions(fees);
    }

    @Test
    void patchBand_negativeBandDiscount_returns400WithFieldError() throws Exception {
        mvc.perform(patch("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"bandDiscount\":-2.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.bandDiscount").exists());
        verifyNoInteractions(fees);
    }

    @Test
    @SuppressWarnings("unchecked")
    void patchBand_withDiscount_putsDiscountKey() throws Exception {
        when(fees.patchBand(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(patch("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"discount\":5.0}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).patchBand(anyString(), captor.capture());
        assertTrue(captor.getValue().containsKey("discount"), "discount key must be present when sent");
        assertEquals(5.0, captor.getValue().get("discount"));
        assertFalse(captor.getValue().containsKey("bandDiscount"), "bandDiscount must be absent when not sent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void patchBand_withBandDiscount_putsBandDiscountKey() throws Exception {
        when(fees.patchBand(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(patch("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"bandDiscount\":7.5}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).patchBand(anyString(), captor.capture());
        assertTrue(captor.getValue().containsKey("bandDiscount"), "bandDiscount key must be present when sent");
        assertEquals(7.5, captor.getValue().get("bandDiscount"));
        assertFalse(captor.getValue().containsKey("discount"), "discount must be absent when only bandDiscount sent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void patchBand_withoutDiscountOrBandDiscount_neitherKeyPresent() throws Exception {
        when(fees.patchBand(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(patch("/api/v1/fees/bands/band-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"schedules\":[\"Annual\"]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).patchBand(anyString(), captor.capture());
        assertFalse(captor.getValue().containsKey("discount"), "discount must be absent when not sent");
        assertFalse(captor.getValue().containsKey("bandDiscount"), "bandDiscount must be absent when not sent");
        assertTrue(captor.getValue().containsKey("schedules"), "schedules key must be present when sent");
    }

    // ─── PUT /items/{id} ─────────────────────────────────────────────────────

    @Test
    void updateItem_negativeAmount_returns400WithFieldError() throws Exception {
        mvc.perform(put("/api/v1/fees/items/item-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"amount\":-100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
        verifyNoInteractions(fees);
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateItem_onlyFrequencySent_omitsNameAndAmountKeys() throws Exception {
        when(fees.updateItem(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(put("/api/v1/fees/items/item-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"frequency\":\"Quarterly\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).updateItem(anyString(), captor.capture());
        assertTrue(captor.getValue().containsKey("frequency"), "frequency must be present when sent");
        assertEquals("Quarterly", captor.getValue().get("frequency"));
        assertFalse(captor.getValue().containsKey("name"), "name must be absent when not sent");
        assertFalse(captor.getValue().containsKey("itemName"), "itemName must be absent when not sent");
        assertFalse(captor.getValue().containsKey("amount"), "amount must be absent when not sent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateItem_withItemName_putsItemNameKey() throws Exception {
        when(fees.updateItem(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(put("/api/v1/fees/items/item-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"itemName\":\"Tuition Fee\",\"amount\":50000}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).updateItem(anyString(), captor.capture());
        assertTrue(captor.getValue().containsKey("itemName"), "itemName key must be present when sent");
        assertEquals("Tuition Fee", captor.getValue().get("itemName"));
        assertTrue(captor.getValue().containsKey("amount"), "amount key must be present when sent");
        assertEquals(50000L, captor.getValue().get("amount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateItem_multiField_allSentKeysPresent() throws Exception {
        when(fees.updateItem(anyString(), anyMap())).thenReturn(Map.of("id", "band-1"));
        mvc.perform(put("/api/v1/fees/items/item-1")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"name\":\"Lab Fee\",\"frequency\":\"Monthly\",\"amount\":1000}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).updateItem(anyString(), captor.capture());
        assertTrue(captor.getValue().containsKey("name"));
        assertEquals("Lab Fee", captor.getValue().get("name"));
        assertTrue(captor.getValue().containsKey("frequency"));
        assertEquals("Monthly", captor.getValue().get("frequency"));
        assertTrue(captor.getValue().containsKey("amount"));
        assertEquals(1000L, captor.getValue().get("amount"));
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
    void recordPayment_clientSuppliedActorId_isIgnoredInFavorOfTenantContext() throws Exception {
        when(fees.recordPayment(anyMap())).thenReturn(Map.of("receiptNumber", "RCPT-123"));
        mvc.perform(post("/api/v1/fees/payments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"amount\":5000,\"actorId\":42}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).recordPayment(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must always be present");
        assertEquals(1L, captor.getValue().get("actorId"), "actorId must come from TenantContext, not the client body");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPayment_withoutActorId_stampsActorIdFromTenantContext() throws Exception {
        when(fees.recordPayment(anyMap())).thenReturn(Map.of("receiptNumber", "RCPT-123"));
        mvc.perform(post("/api/v1/fees/payments")
                        .header("X-Fee-Service-Token", VALID_TOKEN)
                        .contentType("application/json")
                        .content("{\"studentId\":1001,\"amount\":5000}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fees).recordPayment(captor.capture());
        assertTrue(captor.getValue().containsKey("actorId"), "actorId key must always be present");
        assertEquals(1L, captor.getValue().get("actorId"));
    }
}
