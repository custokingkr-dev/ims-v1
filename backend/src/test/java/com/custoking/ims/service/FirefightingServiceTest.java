package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.FirefightingRequestEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.FirefightingQuotationRepository;
import com.custoking.ims.repo.FirefightingRequestRepository;
import com.custoking.ims.repo.SchoolRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FirefightingService — covers status transitions, edit guards,
 * and school ownership enforcement.
 *
 * State transition guards are enforced via ResponseStatusException(CONFLICT)
 * for all approval methods. Tests use the correct source status so they pass
 * through the guards normally.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FirefightingService")
class FirefightingServiceTest {

    @Mock FirefightingRequestRepository firefightingRequestRepository;
    @Mock FirefightingQuotationRepository firefightingQuotationRepository;
    @Mock SchoolRepository schoolRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks FirefightingService firefightingService;

    private SchoolEntity school;

    @BeforeEach
    void setUp() {
        school = new SchoolEntity();
        school.setId(1L);
        school.setName("Test School");

        // Every flow that builds a response calls findByRequest_Code.
        when(firefightingQuotationRepository.findByRequest_Code(any()))
                .thenReturn(List.of());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── submitFireRequest ─────────────────────────────────────────────────────

    @Test
    @DisplayName("submitFireRequest: DRAFT → AWAITING_BURSAR")
    void submitFireRequest_setsAwaitingBursar() {
        FirefightingRequestEntity ff = request("FF-001", "DRAFT");

        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        firefightingService.submitFireRequest("FF-001", actor());

        ArgumentCaptor<FirefightingRequestEntity> captor =
                ArgumentCaptor.forClass(FirefightingRequestEntity.class);
        verify(firefightingRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("AWAITING_BURSAR");
    }

    @Test
    @DisplayName("submitFireRequest: records audit transition to AWAITING_BURSAR")
    void submitFireRequest_recordsAudit() {
        FirefightingRequestEntity ff = request("FF-001", "DRAFT");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        firefightingService.submitFireRequest("FF-001", actor());

        verify(auditLogService).statusTransition(
                eq("ff_request"), eq("FF-001"),
                eq("DRAFT"), eq("AWAITING_BURSAR"), anyLong());
    }

    @Test
    @DisplayName("submitFireRequest: not found → 404")
    void submitFireRequest_notFound_throws404() {
        when(firefightingRequestRepository.findById("FF-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> firefightingService.submitFireRequest("FF-999", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── approveFireBursar ─────────────────────────────────────────────────────

    @Test
    @DisplayName("approveFireBursar: AWAITING_BURSAR → AWAITING_PRINCIPAL")
    void approveFireBursar_setsAwaitingPrincipal() {
        FirefightingRequestEntity ff = request("FF-001", "AWAITING_BURSAR");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        firefightingService.approveFireBursar("FF-001", Map.of("note", "Looks good"), actor());

        ArgumentCaptor<FirefightingRequestEntity> captor =
                ArgumentCaptor.forClass(FirefightingRequestEntity.class);
        verify(firefightingRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("AWAITING_PRINCIPAL");
        assertThat(captor.getValue().getBursarNote()).isEqualTo("Looks good");
        assertThat(captor.getValue().getBursarApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("approveFirePrincipal: AWAITING_PRINCIPAL → APPROVED")
    void approveFirePrincipal_setsApproved() {
        FirefightingRequestEntity ff = request("FF-001", "AWAITING_PRINCIPAL");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        firefightingService.approveFirePrincipal("FF-001", Map.of("note", "Approved"), actor());

        ArgumentCaptor<FirefightingRequestEntity> captor =
                ArgumentCaptor.forClass(FirefightingRequestEntity.class);
        verify(firefightingRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(captor.getValue().getPrincipalNote()).isEqualTo("Approved");
        assertThat(captor.getValue().getPrincipalApprovedAt()).isNotNull();
    }

    // ── rejectFireRequest ─────────────────────────────────────────────────────

    @Test
    @DisplayName("rejectFireRequest: any status → REJECTED with reason")
    void rejectFireRequest_setsRejected() {
        FirefightingRequestEntity ff = request("FF-001", "AWAITING_BURSAR");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        firefightingService.rejectFireRequest("FF-001",
                Map.of("reason", "Budget exceeded"), actor());

        ArgumentCaptor<FirefightingRequestEntity> captor =
                ArgumentCaptor.forClass(FirefightingRequestEntity.class);
        verify(firefightingRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(captor.getValue().getRejectedReason()).isEqualTo("Budget exceeded");
    }

    // ── fulfillFireRequest ────────────────────────────────────────────────────

    @Test
    @DisplayName("fulfillFireRequest: CUSTOKING_APPROVED → FULFILLED")
    void fulfillFireRequest_setsFulfilled() {
        FirefightingRequestEntity ff = request("FF-001", "CUSTOKING_APPROVED");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        firefightingService.fulfillFireRequest("FF-001", actor());

        ArgumentCaptor<FirefightingRequestEntity> captor =
                ArgumentCaptor.forClass(FirefightingRequestEntity.class);
        verify(firefightingRequestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FULFILLED");
    }

    // ── updateFireRequest — DRAFT-only guard ──────────────────────────────────

    @Test
    @DisplayName("updateFireRequest: edit allowed in DRAFT status")
    void updateFireRequest_inDraft_succeeds() {
        FirefightingRequestEntity ff = request("FF-001", "DRAFT");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        assertThatCode(() ->
                firefightingService.updateFireRequest("FF-001",
                        Map.of("title", "Updated title"), actor()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("updateFireRequest: edit rejected when not DRAFT → 400")
    void updateFireRequest_notInDraft_throws400() {
        FirefightingRequestEntity ff = request("FF-001", "AWAITING_BURSAR");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        assertThatThrownBy(() ->
                firefightingService.updateFireRequest("FF-001",
                        Map.of("title", "Cannot update"), actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── deleteFireQuotation — DRAFT-only guard ────────────────────────────────

    @Test
    @DisplayName("deleteFireQuotation: allowed in DRAFT status")
    void deleteFireQuotation_inDraft_succeeds() {
        FirefightingRequestEntity ff = request("FF-001", "DRAFT");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        assertThatCode(() ->
                firefightingService.deleteFireQuotation("FF-001", "q-1", actor()))
                .doesNotThrowAnyException();
        verify(firefightingQuotationRepository).deleteById("q-1");
    }

    @Test
    @DisplayName("deleteFireQuotation: rejected when not DRAFT → 400")
    void deleteFireQuotation_notInDraft_throws400() {
        FirefightingRequestEntity ff = request("FF-001", "AWAITING_BURSAR");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        assertThatThrownBy(() ->
                firefightingService.deleteFireQuotation("FF-001", "q-1", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── Tenant isolation (no Docker needed) ──────────────────────────────────

    @Test
    @DisplayName("submitFireRequest: cross-school actor → 403 FORBIDDEN")
    void submitFireRequest_crossSchool_throws403() {
        // Request belongs to school 1; TenantContext holds school 2 (different school admin).
        TenantContext.set(2L);
        FirefightingRequestEntity ff = request("FF-001", "DRAFT");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        assertThatThrownBy(() -> firefightingService.submitFireRequest("FF-001", actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("approveFireBursar: cross-school actor → 403 FORBIDDEN")
    void approveFireBursar_crossSchool_throws403() {
        TenantContext.set(2L);
        FirefightingRequestEntity ff = request("FF-001", "AWAITING_BURSAR");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        assertThatThrownBy(() -> firefightingService.approveFireBursar("FF-001", Map.of(), actor()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("submitFireRequest: platform admin (TenantContext null) → no ownership check")
    void submitFireRequest_platformAdmin_noOwnershipBlock() {
        // TenantContext.get() == null means superadmin — ownership check is bypassed.
        FirefightingRequestEntity ff = request("FF-001", "DRAFT");
        when(firefightingRequestRepository.findById("FF-001")).thenReturn(Optional.of(ff));

        assertThatCode(() -> firefightingService.submitFireRequest("FF-001", actor()))
                .doesNotThrowAnyException();
    }

    // ── fireRequestStats ──────────────────────────────────────────────────────

    @Test
    @DisplayName("fireRequestStats: correctly counts active and fulfilled")
    void fireRequestStats_countsCorrectly() {
        List<FirefightingRequestEntity> rows = List.of(
                request("FF-001", "AWAITING_BURSAR"),
                request("FF-002", "FULFILLED"),
                request("FF-003", "REJECTED")
        );
        when(firefightingRequestRepository.findAllByOrderByCreatedAtDesc()).thenReturn(rows);

        Map<String, Object> stats = firefightingService.fireRequestStats(actor());

        // AWAITING_BURSAR is active; FULFILLED and REJECTED are not active
        assertThat(stats.get("activeRequests")).isEqualTo(1L);
        assertThat(stats.get("fulfilled")).isEqualTo(1L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FirefightingRequestEntity request(String code, String status) {
        FirefightingRequestEntity ff = new FirefightingRequestEntity();
        ff.setCode(code);
        ff.setTitle("Test Request");
        ff.setCategory("Furniture & fixtures");
        ff.setUrgency("MEDIUM");
        ff.setEstimatedBudget(50_000L);
        ff.setStatus(status);
        ff.setSchool(school);
        ff.setCreatedAt(OffsetDateTime.now());
        return ff;
    }

    private AuthUser actor() {
        return AuthUser.identity(1L, "Test Actor", "actor@test.com", "ADMIN", null, null);
    }
}
