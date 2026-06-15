package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.VendorDueItem;
import com.custoking.ims.commandcenter.dto.VendorDuesListResponse;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.CatalogOrderRepository;
import com.custoking.ims.repo.FirefightingRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class VendorPaymentService {

    private final CatalogOrderRepository catalogOrderRepository;
    private final FirefightingRequestRepository firefightingRequestRepository;
    private final AuditLogService auditLogService;

    public VendorPaymentService(CatalogOrderRepository catalogOrderRepository,
                                FirefightingRequestRepository firefightingRequestRepository,
                                AuditLogService auditLogService) {
        this.catalogOrderRepository = catalogOrderRepository;
        this.firefightingRequestRepository = firefightingRequestRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public DashboardCommandCenterResponse.VendorDuesSection getSummary(Long schoolId) {
        if (schoolId == null) {
            return new DashboardCommandCenterResponse.VendorDuesSection(0, 0, 0, 0, 0);
        }
        long coCount = catalogOrderRepository.countPendingVendorDues(schoolId);
        Long coSum = catalogOrderRepository.sumPendingVendorDues(schoolId);
        long coTotal = coSum != null ? coSum : 0L;

        long ffCount = firefightingRequestRepository.countPendingVendorDues(schoolId);
        Long ffSum = firefightingRequestRepository.sumPendingVendorDues(schoolId);
        long ffTotal = ffSum != null ? ffSum : 0L;

        return new DashboardCommandCenterResponse.VendorDuesSection(
                coCount, coTotal, ffCount, ffTotal, coTotal + ffTotal);
    }

    @Transactional(readOnly = true)
    public VendorDuesListResponse getVendorDuesList(Long schoolId) {
        if (schoolId == null) {
            return new VendorDuesListResponse(0, 0, 0, 0, 0, List.of());
        }
        var orders = catalogOrderRepository.findPendingVendorDues(schoolId);
        var ffRequests = firefightingRequestRepository.findPendingVendorDues(schoolId);

        List<VendorDueItem> items = new ArrayList<>();
        for (var o : orders) {
            items.add(new VendorDueItem(
                    "CATALOG_ORDER",
                    o.getId(),
                    o.getCategory(),
                    o.getCategory(),
                    null,
                    o.getTotalAmount(),
                    o.getStatus(),
                    o.getCreatedAt()));
        }
        for (var r : ffRequests) {
            items.add(new VendorDueItem(
                    "FIREFIGHTING",
                    r.getCode(),
                    r.getTitle(),
                    r.getCategory(),
                    r.getWinnerVendor(),
                    r.getWinnerAmount() != null ? r.getWinnerAmount() : 0L,
                    r.getStatus(),
                    r.getCreatedAt()));
        }

        long coTotal = orders.stream().mapToLong(o -> o.getTotalAmount()).sum();
        long ffTotal = ffRequests.stream()
                .mapToLong(r -> r.getWinnerAmount() != null ? r.getWinnerAmount() : 0L).sum();

        return new VendorDuesListResponse(
                orders.size(), coTotal, ffRequests.size(), ffTotal, coTotal + ffTotal, items);
    }

    @Transactional
    public void markCatalogOrderPaid(Long schoolId, String orderId, String notes, AuthUser actor) {
        var order = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!Objects.equals(order.getSchool().getId(), schoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-school access denied");
        }
        if (order.getVendorPaidAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already marked as vendor-paid");
        }
        order.setVendorPaidAt(OffsetDateTime.now());
        order.setVendorPaidBy(actor.userId());
        order.setVendorPaymentNotes(notes);
        catalogOrderRepository.save(order);

        auditLogService.recordEvent(
                "VENDOR_PAYMENT_RECORDED", actor.userId(), schoolId,
                "CATALOG_ORDER", orderId, "unpaid", "paid");
    }

    @Transactional
    public void markFirefightingPaid(Long schoolId, String code, String notes, AuthUser actor) {
        var request = firefightingRequestRepository.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Firefighting request not found"));
        if (!Objects.equals(request.getSchool().getId(), schoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-school access denied");
        }
        if (request.getVendorPaidAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request already marked as vendor-paid");
        }
        request.setVendorPaidAt(OffsetDateTime.now());
        request.setVendorPaidBy(actor.userId());
        request.setVendorPaymentNotes(notes);
        firefightingRequestRepository.save(request);

        auditLogService.recordEvent(
                "VENDOR_PAYMENT_RECORDED", actor.userId(), schoolId,
                "FIREFIGHTING_REQUEST", code, "unpaid", "paid");
    }
}
