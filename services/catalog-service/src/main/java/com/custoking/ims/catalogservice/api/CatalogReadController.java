package com.custoking.ims.catalogservice.api;

import com.custoking.ims.catalogservice.api.dto.CreateAnnualPlanItemRequest;
import com.custoking.ims.catalogservice.api.dto.CreateCatalogOrderRequest;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.AnnualPlanEntryRow;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.AnnualPlanItemRow;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.CatalogItemRow;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.CatalogOrderRow;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.PendingCatalogOrderRow;
import com.custoking.ims.catalogservice.persistence.CatalogReadRepository.SupplyOrderRow;
import com.custoking.ims.catalogservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogReadController {

    private final CatalogReadRepository catalog;
    private final String readToken;

    public CatalogReadController(
            CatalogReadRepository catalog,
            @Value("${catalog.read-token:}") String readToken) {
        this.catalog = catalog;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/items")
    public List<CatalogItemRow> items(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token) {
        requireToken(token, "catalog:read");
        return catalog.items();
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token) {
        requireToken(token, "catalog:read");
        return catalog.categories();
    }

    @GetMapping("/orders")
    public List<CatalogOrderRow> orders(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "catalog:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return catalog.orders(scope, status, limit);
    }

    @GetMapping("/orders/pending-approval")
    public List<PendingCatalogOrderRow> pendingApprovalOrders(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "catalog:read");
        TenantScope.requireSuperAdmin();
        return catalog.pendingApprovalOrders(limit);
    }

    @GetMapping("/orders/{id}")
    public CatalogOrderRow order(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:read");
        return catalog.order(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog order not found"));
    }

    @PostMapping("/orders")
    public CatalogOrderRow createOrder(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @Valid @RequestBody CreateCatalogOrderRequest dto) {
        requireToken(token, "catalog:write");
        Map<String, Object> body = new HashMap<>();
        body.put("schoolId", dto.schoolId());
        body.put("category", dto.category());
        if (dto.subtotal() != null) body.put("subtotal", dto.subtotal());
        if (dto.gst() != null) body.put("gst", dto.gst());
        if (dto.totalAmount() != null) body.put("totalAmount", dto.totalAmount());
        if (dto.status() != null) body.put("status", dto.status());
        if (dto.notes() != null) body.put("notes", dto.notes());
        if (dto.actorId() != null) body.put("actorId", dto.actorId());
        if (dto.requiredByDate() != null) body.put("requiredByDate", dto.requiredByDate());
        applyResolvedSchool(body);
        return runCommand(() -> catalog.createOrder(body));
    }

    @PostMapping("/orders/{id}/place")
    public CatalogOrderRow placeOrder(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "catalog:write");
        return runCommand(() -> catalog.placeOrder(id, request == null ? null : longValue(request.get("actorId"))));
    }

    @PatchMapping("/orders/{id}/status")
    public CatalogOrderRow updateOrderStatus(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "catalog:write");
        return runCommand(() -> catalog.updateOrderStatus(id, String.valueOf(request.getOrDefault("status", ""))));
    }

    @PostMapping("/orders/{id}/design-approved")
    public CatalogOrderRow markDesignApproved(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:write");
        return runCommand(() -> catalog.markDesignApproved(id));
    }

    @PostMapping("/orders/{id}/superadmin-approve")
    public CatalogOrderRow superadminApprove(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id) {
        requireToken(token, "catalog:write");
        TenantScope.requireSuperAdmin();
        return runCommand(() -> catalog.approveBySuperadmin(id));
    }

    @PostMapping("/orders/{id}/superadmin-reject")
    public CatalogOrderRow superadminReject(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "catalog:write");
        TenantScope.requireSuperAdmin();
        String reason = request == null ? null : String.valueOf(request.getOrDefault("reason", ""));
        return runCommand(() -> catalog.returnBySuperadmin(id, reason));
    }

    @PostMapping("/orders/{id}/vendor-paid")
    public CatalogOrderRow markVendorPaid(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "catalog:write");
        applyResolvedSchool(request);
        Long schoolId = longValue(request.get("schoolId"));
        Long actorId = longValue(request.get("actorId"));
        String notes = String.valueOf(request.getOrDefault("notes", ""));
        return runCommand(() -> catalog.markVendorPaid(id, schoolId, actorId, notes));
    }

    @GetMapping("/orders/stats")
    public Object orderStats(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "catalog:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return catalog.orderStats(scope);
    }

    @GetMapping("/supply-orders")
    public List<SupplyOrderRow> supplyOrders(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "catalog:read");
        return catalog.supplyOrders(limit);
    }

    @GetMapping("/annual-plan/items")
    public List<AnnualPlanItemRow> annualPlanItems(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "catalog:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return catalog.annualPlanItems(scope, academicYearId, limit);
    }

    @GetMapping("/annual-plan")
    public Map<String, Object> annualPlan(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam Long schoolId) {
        requireToken(token, "catalog:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return catalog.annualPlan(scope);
    }

    @PostMapping("/annual-plan/items")
    public AnnualPlanItemRow saveAnnualPlanItem(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token,
            @RequestParam Long schoolId,
            @Valid @RequestBody CreateAnnualPlanItemRequest dto) {
        requireToken(token, "catalog:write");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> body = new HashMap<>();
        body.put("category", dto.category());
        if (dto.id() != null) body.put("id", dto.id());
        if (dto.termName() != null) body.put("termName", dto.termName());
        if (dto.description() != null) body.put("description", dto.description());
        if (dto.quantity() != null) body.put("quantity", dto.quantity());
        if (dto.estimatedAmount() != null) body.put("estimatedAmount", dto.estimatedAmount());
        if (dto.status() != null) body.put("status", dto.status());
        return runCommand(() -> catalog.saveAnnualPlanItem(scope, body));
    }

    @PostMapping("/annual-plan/confirm")
    public Map<String, Object> confirmAnnualPlan(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token) {
        requireToken(token, "catalog:write");
        return Map.of("ok", true, "message", "Annual plan confirmed and Custoking notified");
    }

    @GetMapping("/annual-plan/entries")
    public List<AnnualPlanEntryRow> annualPlanEntries(
            @RequestHeader(value = "X-Catalog-Service-Token", required = false) String token) {
        requireToken(token, "catalog:read");
        return catalog.annualPlanEntries();
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
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid catalog service token");
        }
    }

    private <T> T runCommand(Command<T> command) {
        try {
            return command.run();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private interface Command<T> {
        T run();
    }
}
