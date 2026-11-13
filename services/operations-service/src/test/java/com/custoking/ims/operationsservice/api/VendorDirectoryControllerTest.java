package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.VendorDirectoryService;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.CategoryChange;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorCategoryView;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorInput;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorView;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The vendor directory API (contract {@code vendorDirectoryClient.v1}): its workflow service token, how requests bind,
 * the status codes, and that every refusal reaches the screen as {@code {"code"?, "message"}}.
 */
class VendorDirectoryControllerTest {
    static final String TOKEN = "X-Workflow-Service-Token";
    static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-13T06:30:00Z");
    static final VendorView VENDOR = new VendorView(7, "Sharma Flex House", List.of("FLEX_PRINTERS"), "Ravi", "+91 98765 43210", null,
            null, "Gurugram", "https://chat.whatsapp.com/FlexHouseGroup01", null, false, true, AT, 1L);

    VendorDirectoryService directory;
    MockMvc mvc;

    @BeforeEach void setup() {
        directory = mock(VendorDirectoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new VendorDirectoryController(directory, " workflow-token ")).build();
    }

    @Test void everyEndpointNeedsTheWorkflowServiceToken() throws Exception {
        mvc.perform(get("/api/v1/workflows/vendor-categories")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid workflow service token"));
        mvc.perform(get("/api/v1/workflows/vendors").header(TOKEN, "other-token")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/workflows/vendors").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/workflows/vendors/7").header(TOKEN, "").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/workflows/vendor-categories/SASHES").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new VendorDirectoryController(directory, "  ")).build();
        unconfigured.perform(get("/api/v1/workflows/vendors").header(TOKEN, "")).andExpect(status().isUnauthorized());
        verifyNoInteractions(directory);
    }

    @Test void categoriesAreListedWithEveryContractProperty() throws Exception {
        when(directory.categories()).thenReturn(List.of(new VendorCategoryView("SASHES", "Sashes", 13, true, 2, AT, null)));
        mvc.perform(get("/api/v1/workflows/vendor-categories").header(TOKEN, "workflow-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", aMapWithSize(7)))
                .andExpect(jsonPath("$[0].code").value("SASHES"))
                .andExpect(jsonPath("$[0].sortOrder").value(13))
                .andExpect(jsonPath("$[0].vendorCount").value(2))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-11-13T06:30:00Z"))
                .andExpect(jsonPath("$[0]", hasKey("updatedBy")));
    }

    @Test void vendorsAreFilteredAsAskedAndCarryEveryField() throws Exception {
        when(directory.vendors("FLEX_PRINTERS", "sharma", true)).thenReturn(List.of(VENDOR));
        mvc.perform(get("/api/v1/workflows/vendors").header(TOKEN, "workflow-token")
                        .param("category", "FLEX_PRINTERS").param("q", "sharma").param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]", aMapWithSize(14)))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].categories[0]").value("FLEX_PRINTERS"))
                .andExpect(jsonPath("$[0].whatsappGroupUrl").value("https://chat.whatsapp.com/FlexHouseGroup01"))
                .andExpect(jsonPath("$[0].sample").value(false))
                .andExpect(jsonPath("$[0]", hasKey("altPhone")));
        mvc.perform(get("/api/v1/workflows/vendors").header(TOKEN, "workflow-token")).andExpect(status().isOk());
        verify(directory).vendors(null, null, false);
        mvc.perform(get("/api/v1/workflows/vendors").header(TOKEN, "workflow-token").param("includeInactive", "maybe"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid includeInactive"));
    }

    @Test void addingAVendorAnswersCreatedWithTheStoredVendor() throws Exception {
        when(directory.create(any())).thenReturn(VENDOR);
        mvc.perform(post("/api/v1/workflows/vendors").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"name\":\"Sharma Flex House\",\"categories\":[\"FLEX_PRINTERS\"],\"contactPerson\":\"Ravi\","
                                + "\"phone\":\"+91 98765 43210\",\"altPhone\":null,\"email\":null,\"city\":\"Gurugram\","
                                + "\"whatsappGroupUrl\":\"https://chat.whatsapp.com/FlexHouseGroup01\",\"notes\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));
        verify(directory).create(new VendorInput("Sharma Flex House", List.of("FLEX_PRINTERS"), "Ravi", "+91 98765 43210", null, null,
                "Gurugram", "https://chat.whatsapp.com/FlexHouseGroup01", null, null));
    }

    @Test void editingAVendorAndACategoryBindTheirBodies() throws Exception {
        when(directory.update(eq(7L), any())).thenReturn(VENDOR);
        mvc.perform(put("/api/v1/workflows/vendors/7").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"name\":\"Sharma Flex House\",\"categories\":[\"FLEX_PRINTERS\"],\"phone\":\"9876543210\",\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Sharma Flex House"));
        verify(directory).update(7L, new VendorInput("Sharma Flex House", List.of("FLEX_PRINTERS"), null, "9876543210", null, null, null,
                null, null, false));

        when(directory.updateCategory(eq("BATCH_MAKERS"), any())).thenReturn(new VendorCategoryView("BATCH_MAKERS", "Badge Makers", 9, true, 0, AT, 1L));
        mvc.perform(put("/api/v1/workflows/vendor-categories/BATCH_MAKERS").header(TOKEN, "workflow-token").contentType("application/json")
                        .content("{\"name\":\"Badge Makers\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Badge Makers"));
        verify(directory).updateCategory("BATCH_MAKERS", new CategoryChange("Badge Makers", null, null));

        mvc.perform(put("/api/v1/workflows/vendors/0").header(TOKEN, "workflow-token").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Vendor not found"));
        mvc.perform(put("/api/v1/workflows/vendors/abc").header(TOKEN, "workflow-token").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid id"));
        verify(directory, org.mockito.Mockito.never()).update(eq(0L), any());
    }

    @Test void refusalsCarryTheirCodeAndMessage() throws Exception {
        when(directory.create(any())).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY,
                "Only superadmin can maintain the vendor directory"));
        mvc.perform(post("/api/v1/workflows/vendors").header(TOKEN, "workflow-token").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUPERADMIN_ONLY"))
                .andExpect(jsonPath("$.message").value("Only superadmin can maintain the vendor directory"))
                .andExpect(jsonPath("$", aMapWithSize(2)));

        when(directory.update(anyLong(), any())).thenThrow(new WorkflowRuleViolation(WorkflowRuleViolation.INVALID_INVITE,
                "Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)"));
        mvc.perform(put("/api/v1/workflows/vendors/7").header(TOKEN, "workflow-token").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INVITE"));

        when(directory.vendors(any(), any(), eq(false))).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "A vendor with this name and phone already exists"));
        mvc.perform(get("/api/v1/workflows/vendors").header(TOKEN, "workflow-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A vendor with this name and phone already exists"));

        mvc.perform(post("/api/v1/workflows/vendors").header(TOKEN, "workflow-token").contentType("application/json").content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send the vendor as JSON: name, categories, phone and the optional fields"));
    }
}
