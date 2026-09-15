package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/supply/orders", "/api/v1/catalog/orders"})
public class CatalogOrderFormController {
    private final CatalogOrderFormService forms;
    private final CatalogReadRepository orders;
    private final String token;

    public CatalogOrderFormController(CatalogOrderFormService forms, CatalogReadRepository orders,
                                      @Value("${catalog.read-token:}") String token) {
        this.forms = forms;
        this.orders = orders;
        this.token = token == null ? "" : token.trim();
    }

    @GetMapping("/{id}/form")
    public Map<String, Object> detail(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
                                      @PathVariable String id) {
        requireToken(token, "order:read");
        return detailResponse(id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
                                      @PathVariable String id, @RequestBody Map<String, Object> request) {
        requireToken(token, "order:update");
        forms.updateDraft(id, request);
        return detailResponse(id);
    }

    @PutMapping("/{id}/quote")
    public Map<String, Object> quote(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
                                     @PathVariable String id, @RequestBody Map<String, Object> request) {
        requireToken(token, "catalog:quote");
        TenantScope.requireSuperAdmin();
        forms.quote(id, request);
        return detailResponse(id);
    }

    @GetMapping("/{id}/assets")
    public List<Map<String, Object>> assets(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
                                           @PathVariable String id) {
        requireToken(token, "order:read");
        return forms.assetHistory(id);
    }

    @PostMapping(value = "/{id}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
                                      @PathVariable String id, @RequestParam String assetKind,
                                      @RequestParam MultipartFile file) throws IOException {
        requireToken(token, "order:update");
        if (file.getSize() > com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a file of 5 MB or smaller");
        }
        return forms.upload(id, assetKind, file.getBytes(), file.getOriginalFilename());
    }

    @DeleteMapping("/{id}/assets/{assetId}")
    public ResponseEntity<Void> remove(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
                                       @PathVariable String id, @PathVariable long assetId) {
        requireToken(token, "order:update");
        forms.removeAsset(id, assetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/assets/{assetId}/content")
    public ResponseEntity<byte[]> content(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
                                          @PathVariable String id, @PathVariable long assetId) {
        requireToken(token, "order:read");
        var content = forms.content(id, assetId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(content.filename()).build().toString())
                .body(content.bytes());
    }

    private Map<String, Object> detailResponse(String id) {
        Map<String, Object> result = forms.detail(id);
        result.put("order", orders.order(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")));
        return result;
    }

    private void requireToken(String supplied, String permission) {
        if (token.isBlank() || !token.equals(supplied)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid catalog service token");
        if (permission != null) TenantScope.requirePermissionIfAuthenticated(permission);
    }
}
