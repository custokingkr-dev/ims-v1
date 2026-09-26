package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.api.dto.QuotationDocumentResponse;
import com.custoking.ims.operationsservice.application.QuotationDocumentService;
import com.custoking.ims.operationsservice.infrastructure.QuotationDocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QuotationDocumentControllerTest {
    private static final String PATH = "/api/v1/ff/requests/FF-A/quotations/q-a/document";
    private static final String TOKEN_HEADER = "X-Firefighting-Service-Token";
    private QuotationDocumentService service;
    private MockMvc mvc;
    private final byte[] bytes = new byte[]{1, 2, 3};
    private final QuotationDocumentResponse metadata = new QuotationDocumentResponse("doc-a", "vendor.png", "image/png", 3,
            OffsetDateTime.parse("2026-09-26T10:00:00Z"));

    @BeforeEach void setup() {
        service = mock(QuotationDocumentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new QuotationDocumentController(service, "private-token")).build();
    }

    @Test void everyOperationRejectsMissingOrWrongInternalTokenBeforeServiceAccess() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH).header(TOKEN_HEADER, "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(multipart(PATH).file(new MockMultipartFile("file", "vendor.png", "image/png", bytes)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ff/quotation-documents/capabilities"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void multipartRequiresExpectedFieldAndRejectsEmptyOrOversizedFiles() throws Exception {
        mvc.perform(multipart(PATH).header(TOKEN_HEADER, "private-token")
                        .file(new MockMultipartFile("another", bytes))).andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH).file(new MockMultipartFile("file", new byte[0]))
                        .header(TOKEN_HEADER, "private-token")).andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH).file(new MockMultipartFile("file", new byte[(int) QuotationDocumentStorage.MAX_BYTES + 1]))
                        .header(TOKEN_HEADER, "private-token")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("5 MB")));
        verifyNoInteractions(service);
    }

    @Test void uploadReturnsOnlyConfirmedMetadataAndPreservesActionableFailureStatus() throws Exception {
        when(service.upload("FF-A", "q-a", bytes, "vendor.png", "image/png")).thenReturn(metadata);
        mvc.perform(multipart(PATH).file(new MockMultipartFile("file", "vendor.png", "image/png", bytes))
                        .header(TOKEN_HEADER, "private-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("doc-a"))
                .andExpect(jsonPath("$.filename").value("vendor.png"))
                .andExpect(jsonPath("$.objectKey").doesNotExist()).andExpect(jsonPath("$.bucket").doesNotExist())
                .andExpect(jsonPath("$.url").doesNotExist());
        when(service.upload("FF-A", "q-a", bytes, "vendor.png", "image/png"))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "The request is no longer a draft"));
        mvc.perform(multipart(PATH).file(new MockMultipartFile("file", "vendor.png", "image/png", bytes))
                        .header(TOKEN_HEADER, "private-token"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("The request is no longer a draft"));
    }

    @Test void downloadIsAnAuthenticatedNonCacheableAttachmentAndDoesNotMaskScopeDenials() throws Exception {
        when(service.download("FF-A", "q-a")).thenReturn(new QuotationDocumentService.Download(metadata, bytes));
        mvc.perform(get(PATH).header(TOKEN_HEADER, "private-token"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string("Content-Disposition", containsString("attachment;")))
                .andExpect(header().string("Content-Disposition", containsString("vendor.png")));
        when(service.download("FF-A", "q-a")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation not found"));
        mvc.perform(get(PATH).header(TOKEN_HEADER, "private-token")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Quotation not found"));
    }

    @Test void removalAndUnavailableCapabilitiesRemainTruthful() throws Exception {
        mvc.perform(delete(PATH).header(TOKEN_HEADER, "private-token")).andExpect(status().isNoContent());
        verify(service).remove("FF-A", "q-a");
        when(service.capabilities()).thenReturn(Map.of("available", false, "canUpload", false,
                "unavailableReason", "Private quotation storage is not configured"));
        mvc.perform(get("/api/v1/ff/quotation-documents/capabilities").header(TOKEN_HEADER, "private-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.canUpload").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("Private quotation storage is not configured"));
    }
}
