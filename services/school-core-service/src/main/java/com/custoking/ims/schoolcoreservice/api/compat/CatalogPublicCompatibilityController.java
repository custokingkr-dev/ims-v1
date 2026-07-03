package com.custoking.ims.schoolcoreservice.api.compat;

import com.custoking.ims.schoolcoreservice.persistence.CatalogReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
public class CatalogPublicCompatibilityController {

    private final CatalogReadRepository catalog;
    private final String readToken;

    public CatalogPublicCompatibilityController(
            CatalogReadRepository catalog,
            @Value("${catalog.read-token:}") String readToken) {
        this.catalog = catalog;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/api/v1/supply/catalog-categories")
    public Object categories(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token) {
        requireToken(token, "catalog:read");
        return catalog.categories();
    }

    @GetMapping({"/api/v1/supply/orders", "/api/v1/sa/orders"})
    public Object orders(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "catalog:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return catalog.orders(scope, status, limit);
    }

    @GetMapping({"/api/v1/supply/orders/stats", "/api/v1/sa/orders/stats"})
    public Object orderStats(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "catalog:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return catalog.orderStats(scope);
    }

    @GetMapping("/api/v1/supply/orders/pending-approval")
    public Object pendingApprovalOrders(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "catalog:read");
        TenantScope.requireSuperAdmin();
        return catalog.pendingApprovalOrders(limit);
    }

    @GetMapping("/api/v1/supply/orders/{id}")
    public Object order(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:read");
        return catalog.order(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog order not found"));
    }

    @PostMapping({"/api/v1/supply/orders", "/api/v1/sa/orders"})
    public Object createOrder(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "catalog:read");
        applyResolvedSchool(request);
        return command(() -> catalog.createOrder(request));
    }

    @PostMapping(value = "/api/v1/supply/orders/{id}/place", consumes = MediaType.ALL_VALUE)
    public Object placeOrder(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:read");
        return command(() -> catalog.placeOrder(id, null));
    }

    @PatchMapping({"/api/v1/supply/orders/{id}/status", "/api/v1/sa/orders/{id}/status"})
    public Object updateOrderStatus(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "catalog:read");
        return command(() -> catalog.updateOrderStatus(id, String.valueOf(request.getOrDefault("status", ""))));
    }

    @PostMapping("/api/v1/supply/orders/{id}/design-approved")
    public Object markDesignApproved(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:read");
        return command(() -> catalog.markDesignApproved(id));
    }

    @PostMapping("/api/v1/supply/orders/{id}/superadmin-approve")
    public Object superadminApprove(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:read");
        TenantScope.requireSuperAdmin();
        return command(() -> catalog.approveBySuperadmin(id));
    }

    @PostMapping("/api/v1/supply/orders/{id}/superadmin-reject")
    public Object superadminReject(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "catalog:read");
        TenantScope.requireSuperAdmin();
        String reason = request == null ? null : String.valueOf(request.getOrDefault("reason", ""));
        return command(() -> catalog.returnBySuperadmin(id, reason));
    }

    @GetMapping("/api/v1/supply/annual-plan")
    public Object annualPlan(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam Long schoolId) {
        requireToken(token, "catalog:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return catalog.annualPlan(scope);
    }

    @PostMapping("/api/v1/supply/annual-plan/items")
    public Object saveAnnualPlanItem(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "catalog:read");
        applyResolvedSchool(request);
        Long schoolId = longValue(request.get("schoolId"));
        if (schoolId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId is required");
        }
        return command(() -> catalog.saveAnnualPlanItem(schoolId, request));
    }

    @PostMapping("/api/v1/supply/annual-plan/confirm")
    public Object confirmAnnualPlan(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String token) {
        requireToken(token, "catalog:read");
        return Map.of("ok", true, "message", "Annual plan confirmed and Custoking notified");
    }

    @PostMapping("/api/v1/dashboard/vendor-dues/catalog-orders/{id}/mark-paid")
    public Map<String, Object> markCatalogVendorPaid(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "catalog:read");
        Map<String, Object> body = request == null ? new HashMap<>() : request;
        applyResolvedSchool(body);
        Long schoolId = longValue(body.get("schoolId"));
        Long actorId = longValue(body.get("actorId"));
        String notes = body.get("notes") == null ? null : String.valueOf(body.get("notes"));
        var row = catalog.markVendorPaid(id, schoolId, actorId, notes);
        return Map.of("order", row);
    }

    private void applyResolvedSchool(Map<String, Object> request) {
        Long requested;
        if (request.get("schoolId") == null) {
            requested = null;
        } else {
            try {
                requested = Long.valueOf(String.valueOf(request.get("schoolId")));
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid schoolId");
            }
        }
        request.put("schoolId", TenantScope.resolveSchoolId(requested));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid catalog service token");
        }
    }

    private Object command(Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private interface Command {
        Object run();
    }
}
