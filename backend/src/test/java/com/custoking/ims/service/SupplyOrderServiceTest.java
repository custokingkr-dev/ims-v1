package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.entity.AcademicYearEntity;
import com.custoking.ims.entity.CatalogOrderEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SupplyOrderService — covers the catalog order state machine:
 *   DRAFT → DESIGN_APPROVAL / PROCESSING / AWAITING_APPROVAL (via placeCatalogOrder)
 *   DESIGN_APPROVAL → DESIGN_APPROVED_PROCESSING (via markCatalogOrderDesignApproved)
 *   DESIGN_APPROVED_PROCESSING / PROCESSING → APPROVED / back (via superadmin approve/reject)
 *
 * Guard tests confirm that calling approve/reject on wrong statuses returns 400.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupplyOrderService")
class SupplyOrderServiceTest {

    @Mock CatalogOrderRepository catalogOrderRepository;
    @Mock SchoolRepository schoolRepository;
    @Mock AnnualPlanItemRepository annualPlanItemRepository;
    @Mock AcademicYearRepository academicYearRepository;
    @Mock StudentRepository studentRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks SupplyOrderService supplyOrderService;

    private SchoolEntity school;
    private AcademicYearEntity activeYear;

    @BeforeEach
    void setUp() {
        school = new SchoolEntity();
        school.setId(1L);
        school.setName("Test School");

        activeYear = new AcademicYearEntity();
        activeYear.setId("AY-2025");
        activeYear.setLabel("2025-26");
    }

    // ── placeCatalogOrder — state-machine routing ─────────────────────────────

    @Test
    @DisplayName("placeCatalogOrder: UNIFORMS → DESIGN_APPROVAL with design pending")
    void place_uniforms_goesToDesignApproval() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "DRAFT");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.placeCatalogOrder("CK-1001", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DESIGN_APPROVAL");
        assertThat(captor.getValue().getDesignStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getSuperadminApprovalStatus()).isEqualTo("NOT_SUBMITTED");
    }

    @Test
    @DisplayName("placeCatalogOrder: NOTEBOOKS → DESIGN_APPROVAL (same path as UNIFORMS)")
    void place_notebooks_goesToDesignApproval() {
        CatalogOrderEntity entity = order("CK-1002", "NOTEBOOKS", "DRAFT");
        when(catalogOrderRepository.findById("CK-1002")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.placeCatalogOrder("CK-1002", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DESIGN_APPROVAL");
    }

    @Test
    @DisplayName("placeCatalogOrder: STATIONERY → PROCESSING with superadmin approval pending")
    void place_stationery_goesToProcessing() {
        CatalogOrderEntity entity = order("CK-1003", "STATIONERY", "DRAFT");
        when(catalogOrderRepository.findById("CK-1003")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.placeCatalogOrder("CK-1003", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PROCESSING");
        assertThat(captor.getValue().getDesignStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(captor.getValue().getSuperadminApprovalStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("placeCatalogOrder: EVENTS → PROCESSING (same path as STATIONERY)")
    void place_events_goesToProcessing() {
        CatalogOrderEntity entity = order("CK-1004", "EVENTS", "DRAFT");
        when(catalogOrderRepository.findById("CK-1004")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.placeCatalogOrder("CK-1004", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("placeCatalogOrder: HEALTH → AWAITING_APPROVAL (default path)")
    void place_health_goesToAwaitingApproval() {
        CatalogOrderEntity entity = order("CK-1005", "HEALTH", "DRAFT");
        when(catalogOrderRepository.findById("CK-1005")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.placeCatalogOrder("CK-1005", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    @DisplayName("placeCatalogOrder: not found → 404")
    void place_notFound_throws404() {
        when(catalogOrderRepository.findById("CK-9999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> supplyOrderService.placeCatalogOrder("CK-9999", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── markCatalogOrderDesignApproved guards ─────────────────────────────────

    @Test
    @DisplayName("markCatalogOrderDesignApproved: DESIGN_APPROVAL → DESIGN_APPROVED_PROCESSING")
    void designApprove_fromDesignApproval_succeeds() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "DESIGN_APPROVAL");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.markCatalogOrderDesignApproved("CK-1001", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DESIGN_APPROVED_PROCESSING");
        assertThat(captor.getValue().getDesignStatus()).isEqualTo("APPROVED");
        assertThat(captor.getValue().getSuperadminApprovalStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("markCatalogOrderDesignApproved: wrong status → 400")
    void designApprove_wrongStatus_throws400() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "DRAFT");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() ->
                supplyOrderService.markCatalogOrderDesignApproved("CK-1001", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("markCatalogOrderDesignApproved: non-UNIFORMS/NOTEBOOKS category → 400")
    void designApprove_stationeryCategory_throws400() {
        CatalogOrderEntity entity = order("CK-1003", "STATIONERY", "DESIGN_APPROVAL");
        when(catalogOrderRepository.findById("CK-1003")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() ->
                supplyOrderService.markCatalogOrderDesignApproved("CK-1003", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── superadminApproveOrder guards ─────────────────────────────────────────

    @Test
    @DisplayName("superadminApproveOrder: DESIGN_APPROVED_PROCESSING → APPROVED")
    void superadminApprove_fromDesignApprovedProcessing_succeeds() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "DESIGN_APPROVED_PROCESSING");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.superadminApproveOrder("CK-1001", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(captor.getValue().getSuperadminApprovalStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("superadminApproveOrder: PROCESSING → APPROVED")
    void superadminApprove_fromProcessing_succeeds() {
        CatalogOrderEntity entity = order("CK-1003", "STATIONERY", "PROCESSING");
        when(catalogOrderRepository.findById("CK-1003")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.superadminApproveOrder("CK-1003", actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("superadminApproveOrder: DRAFT status → 400 (illegal transition)")
    void superadminApprove_fromDraft_throws400() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "DRAFT");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> supplyOrderService.superadminApproveOrder("CK-1001", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("superadminApproveOrder: APPROVED status → 400 (cannot re-approve)")
    void superadminApprove_fromApproved_throws400() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "APPROVED");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> supplyOrderService.superadminApproveOrder("CK-1001", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── superadminRejectOrder guards ──────────────────────────────────────────

    @Test
    @DisplayName("superadminRejectOrder: DESIGN_APPROVED_PROCESSING → DESIGN_APPROVAL for UNIFORMS")
    void superadminReject_uniforms_returnsToDesignApproval() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "DESIGN_APPROVED_PROCESSING");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.superadminRejectOrder("CK-1001",
                java.util.Map.of("reason", "Design needs revision"), actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DESIGN_APPROVAL");
        assertThat(captor.getValue().getSuperadminApprovalStatus()).isEqualTo("RETURNED");
    }

    @Test
    @DisplayName("superadminRejectOrder: PROCESSING → PROCESSING for STATIONERY")
    void superadminReject_stationery_returnsToProcessing() {
        CatalogOrderEntity entity = order("CK-1003", "STATIONERY", "PROCESSING");
        when(catalogOrderRepository.findById("CK-1003")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.superadminRejectOrder("CK-1003",
                java.util.Map.of("reason", "Needs more detail"), actor());

        ArgumentCaptor<CatalogOrderEntity> captor = ArgumentCaptor.forClass(CatalogOrderEntity.class);
        verify(catalogOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PROCESSING");
        assertThat(captor.getValue().getSuperadminApprovalStatus()).isEqualTo("RETURNED");
    }

    @Test
    @DisplayName("superadminRejectOrder: DRAFT status → 400 (illegal transition)")
    void superadminReject_fromDraft_throws400() {
        CatalogOrderEntity entity = order("CK-1001", "UNIFORMS", "DRAFT");
        when(catalogOrderRepository.findById("CK-1001")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> supplyOrderService.superadminRejectOrder("CK-1001",
                java.util.Map.of(), actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── placeCatalogOrder: audit trail ────────────────────────────────────────

    @Test
    @DisplayName("placeCatalogOrder: records audit status transition")
    void place_recordsAuditTransition() {
        CatalogOrderEntity entity = order("CK-1005", "HEALTH", "DRAFT");
        when(catalogOrderRepository.findById("CK-1005")).thenReturn(Optional.of(entity));
        when(catalogOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        supplyOrderService.placeCatalogOrder("CK-1005", actor());

        verify(auditLogService).statusTransition(
                eq("catalog_order"), eq("CK-1005"),
                eq("DRAFT"), eq("AWAITING_APPROVAL"), anyLong());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CatalogOrderEntity order(String id, String category, String status) {
        CatalogOrderEntity e = new CatalogOrderEntity();
        e.setId(id);
        e.setCategory(category);
        e.setStatus(status);
        e.setSchool(school);
        e.setSubtotal(50_000L);
        e.setGst(9_000L);
        e.setTotalAmount(59_000L);
        e.setOrderData("{}");
        e.setDesignStatus("NOT_REQUIRED");
        e.setSuperadminApprovalStatus("NOT_REQUIRED");
        return e;
    }

    private AuthUser actor() {
        return AuthUser.identity(1L, "Test Superadmin", "admin@custoking.com", "SUPERADMIN", null, null);
    }
}
