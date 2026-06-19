package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.entity.CatalogOrderEntity;
import com.custoking.ims.entity.FirefightingRequestEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.CatalogOrderRepository;
import com.custoking.ims.repo.FirefightingRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VendorPaymentService")
class VendorPaymentServiceTest {

    @Mock CatalogOrderRepository catalogOrderRepository;
    @Mock FirefightingRequestRepository firefightingRequestRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks VendorPaymentService service;

    private static final Long SCHOOL_ID = 10L;
    private static final Long OTHER_SCHOOL_ID = 99L;

    private AuthUser actor() {
        return AuthUser.identity(1L, "Admin", "admin@school.com", "ADMIN", SCHOOL_ID, "School");
    }

    private SchoolEntity school(Long id) {
        SchoolEntity s = new SchoolEntity();
        s.setId(id);
        return s;
    }

    @Test
    @DisplayName("getSummary returns aggregated counts and totals for both source types")
    void getSummary_returnsAggregatedTotals() {
        when(catalogOrderRepository.countPendingVendorDues(SCHOOL_ID)).thenReturn(3L);
        when(catalogOrderRepository.sumPendingVendorDues(SCHOOL_ID)).thenReturn(120000L);
        when(firefightingRequestRepository.countPendingVendorDues(SCHOOL_ID)).thenReturn(1L);
        when(firefightingRequestRepository.sumPendingVendorDues(SCHOOL_ID)).thenReturn(50000L);

        var result = service.getSummary(SCHOOL_ID);

        assertThat(result.catalogOrderCount()).isEqualTo(3);
        assertThat(result.catalogOrderTotalPaise()).isEqualTo(120000L);
        assertThat(result.firefightingCount()).isEqualTo(1);
        assertThat(result.firefightingTotalPaise()).isEqualTo(50000L);
        assertThat(result.totalDuesPaise()).isEqualTo(170000L);
    }

    @Test
    @DisplayName("getSummary returns zeros for null schoolId without querying DB")
    void getSummary_nullSchoolId_returnsZeros() {
        var result = service.getSummary(null);

        assertThat(result.totalDuesPaise()).isZero();
        verifyNoInteractions(catalogOrderRepository, firefightingRequestRepository);
    }

    @Test
    @DisplayName("markCatalogOrderPaid records payment and writes audit log")
    void markCatalogOrderPaid_success_recordsPaymentAndAudit() {
        var order = new CatalogOrderEntity();
        order.setId("ORD-001");
        order.setSchool(school(SCHOOL_ID));
        order.setTotalAmount(60000L);
        when(catalogOrderRepository.findById("ORD-001")).thenReturn(Optional.of(order));
        when(catalogOrderRepository.save(any())).thenReturn(order);

        service.markCatalogOrderPaid(SCHOOL_ID, "ORD-001", "Paid via NEFT", actor());

        assertThat(order.getVendorPaidAt()).isNotNull();
        assertThat(order.getVendorPaidBy()).isEqualTo(1L);
        assertThat(order.getVendorPaymentNotes()).isEqualTo("Paid via NEFT");
        verify(auditLogService).recordEvent(eq("VENDOR_PAYMENT_RECORDED"), eq(1L), eq(SCHOOL_ID),
                eq("CATALOG_ORDER"), eq("ORD-001"), eq("unpaid"), eq("paid"));
    }

    @Test
    @DisplayName("markCatalogOrderPaid throws 403 for order belonging to different school")
    void markCatalogOrderPaid_crossSchool_throws403() {
        var order = new CatalogOrderEntity();
        order.setId("ORD-002");
        order.setSchool(school(OTHER_SCHOOL_ID));
        when(catalogOrderRepository.findById("ORD-002")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.markCatalogOrderPaid(SCHOOL_ID, "ORD-002", null, actor()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cross-school");
    }

    @Test
    @DisplayName("markFirefightingPaid throws 409 if request already marked vendor-paid")
    void markFirefightingPaid_alreadyPaid_throws409() {
        var req = new FirefightingRequestEntity();
        req.setCode("FF-001");
        req.setSchool(school(SCHOOL_ID));
        req.setVendorPaidAt(java.time.OffsetDateTime.now());
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.markFirefightingPaid(SCHOOL_ID, "FF-001", null, actor()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already marked");
    }
}
