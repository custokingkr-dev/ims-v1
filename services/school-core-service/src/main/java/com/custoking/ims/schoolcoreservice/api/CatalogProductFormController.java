package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.dto.CatalogDefinitionRequests.*;
import com.custoking.ims.schoolcoreservice.persistence.ProductCatalogRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/supply/product-catalog")
public class CatalogProductFormController {
    private final ProductCatalogRepository catalog;
    private final ModuleEntitlementGuard moduleGuard;
    private final ObjectMapper mapper;
    private final String token;

    public CatalogProductFormController(ProductCatalogRepository catalog, ModuleEntitlementGuard moduleGuard,
                                         ObjectMapper mapper, @Value("${catalog.read-token:}") String token) {
        this.catalog = catalog;
        this.moduleGuard = moduleGuard;
        this.mapper = mapper;
        this.token = token == null ? "" : token.trim();
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken,
                                         @RequestParam(defaultValue = "false") boolean includeInactive,
                                         @RequestHeader(value = "If-None-Match", required = false) String etag) {
        read(serviceToken, includeInactive);
        return cached(catalog.categories(includeInactive), etag);
    }

    @GetMapping("/forms/{categoryCode}")
    public ResponseEntity<?> form(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken,
                                   @PathVariable String categoryCode, @RequestParam(defaultValue = "false") boolean includeInactive,
                                   @RequestHeader(value = "If-None-Match", required = false) String etag) {
        read(serviceToken, includeInactive);
        return cached(catalog.form(categoryCode, includeInactive), etag);
    }

    @PostMapping("/categories")
    public Map<String, Object> createCategory(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @Valid @RequestBody Category body) {
        write(serviceToken); return catalog.create("categories", values(body));
    }
    @PatchMapping("/categories/{code}")
    public Map<String, Object> updateCategory(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @PathVariable String code, @Valid @RequestBody Category body) {
        write(serviceToken); return catalog.update("categories", code, values(body));
    }
    @PostMapping("/groups")
    public Map<String, Object> createGroup(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @Valid @RequestBody Group body) {
        write(serviceToken); return catalog.create("groups", values(body));
    }
    @PatchMapping("/groups/{id}")
    public Map<String, Object> updateGroup(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @PathVariable long id, @Valid @RequestBody Group body) {
        write(serviceToken); return catalog.update("groups", id, values(body));
    }
    @PostMapping("/options")
    public Map<String, Object> createOption(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @Valid @RequestBody Option body) {
        write(serviceToken); return catalog.create("options", values(body));
    }
    @PatchMapping("/options/{id}")
    public Map<String, Object> updateOption(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @PathVariable long id, @Valid @RequestBody Option body) {
        write(serviceToken); return catalog.update("options", id, values(body));
    }
    @PostMapping("/rules")
    public Map<String, Object> createRule(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @Valid @RequestBody Rule body) {
        write(serviceToken); return catalog.create("rules", values(body));
    }
    @PatchMapping("/rules/{id}")
    public Map<String, Object> updateRule(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken, @PathVariable long id, @Valid @RequestBody Rule body) {
        write(serviceToken); return catalog.update("rules", id, values(body));
    }
    @DeleteMapping("/{resource}/{id}")
    public Map<String, Object> delete(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String serviceToken,
                                       @PathVariable String resource, @PathVariable String id, @RequestParam(defaultValue = "false") boolean hard) {
        write(serviceToken);
        return catalog.delete(resource, "categories".equals(resource) ? id : Long.valueOf(id), hard);
    }

    private void read(String serviceToken, boolean admin) {
        requireToken(serviceToken, "order:read");
        if (admin) { TenantScope.requireSuperAdmin(); TenantScope.requirePermissionIfAuthenticated("catalog:manage"); }
        if (moduleGuard != null) moduleGuard.requireModuleEnabled(TenantContext.get().schoolId(), "ORDERS");
    }
    private void write(String serviceToken) {
        requireToken(serviceToken, "catalog:manage");
        TenantScope.requireSuperAdmin();
        if (moduleGuard != null) moduleGuard.requireModuleEnabled(TenantContext.get().schoolId(), "ORDERS");
    }
    private void requireToken(String serviceToken, String permission) {
        if (!StringUtils.hasText(token) || !token.equals(serviceToken)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid catalog service token");
        TenantScope.requirePermissionIfAuthenticated(permission);
    }
    private ResponseEntity<?> cached(Object body, String suppliedEtag) {
        String etag = "\"" + DigestUtils.md5DigestAsHex(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)) + "\"";
        if (etag.equals(suppliedEtag)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).header("Cache-Control", "private, max-age=60").build();
        return ResponseEntity.ok().eTag(etag).header("Cache-Control", "private, max-age=60").body(body);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> values(Object body) {
        var values = new LinkedHashMap<String, Object>(mapper.convertValue(body, Map.class));
        values.entrySet().removeIf(entry -> entry.getValue() == null);
        if (body instanceof Rule rule && "REQUIRE_ASSET".equals(rule.ruleType())) values.put("targetField", null);
        if (body instanceof Option option && "PENDING_SPEC".equals(option.specStatus()) && option.widthMm() == null && option.heightMm() == null) {
            values.put("widthMm", null);
            values.put("heightMm", null);
        }
        return values;
    }
}
