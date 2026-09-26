package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.api.dto.QuotationDocumentResponse;
import com.custoking.ims.operationsservice.application.QuotationDocumentService;
import com.custoking.ims.operationsservice.infrastructure.QuotationDocumentStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ff")
public class QuotationDocumentController {
    private final QuotationDocumentService documents;
    private final String readToken;
    public QuotationDocumentController(QuotationDocumentService documents, @Value("${firefighting.read-token:}") String readToken) {
        this.documents = documents;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/quotation-documents/capabilities")
    public Map<String, Object> capabilities(@RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token) {
        requireToken(token, "firefighting:read");
        return documents.capabilities();
    }

    @PostMapping(value = "/requests/{code}/quotations/{quotationId}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuotationDocumentResponse upload(@RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code, @PathVariable String quotationId, @RequestParam("file") MultipartFile file) throws IOException {
        requireToken(token, "firefighting:write");
        if (file.isEmpty() || file.getSize() > QuotationDocumentStorage.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a nonempty quotation file no larger than 5 MB");
        }
        return documents.upload(code, quotationId, file.getBytes(), file.getOriginalFilename(), file.getContentType());
    }

    @GetMapping("/requests/{code}/quotations/{quotationId}/document")
    public ResponseEntity<byte[]> download(@RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code, @PathVariable String quotationId) {
        requireToken(token, "firefighting:read");
        var result = documents.download(code, quotationId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.metadata().contentType()))
                .contentLength(result.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(result.metadata().filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(result.bytes());
    }

    @DeleteMapping("/requests/{code}/quotations/{quotationId}/document")
    public ResponseEntity<Void> remove(@RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code, @PathVariable String quotationId) {
        requireToken(token, "firefighting:write");
        documents.remove(code, quotationId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> documentError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",
                error.getReason() == null ? "Quotation file request failed" : error.getReason()));
    }

    private void requireToken(String token, String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing internal route scope");
        if (readToken.isBlank() || !readToken.equals(token)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid firefighting service token");
    }
}
