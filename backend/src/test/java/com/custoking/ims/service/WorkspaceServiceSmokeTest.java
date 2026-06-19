package com.custoking.ims.service;

import com.custoking.ims.entity.AcademicYearEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Smoke tests for WorkspaceService.commandCentreCards — verifies no NPE
 * when repositories return empty results, for both superadmin and school-admin paths.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkspaceService smoke tests")
class WorkspaceServiceSmokeTest {

    @Mock SchoolRepository schoolRepository;
    @Mock SchoolSectionRepository sectionRepository;
    @Mock AcademicYearRepository academicYearRepository;
    @Mock AppUserRepository userRepository;
    @Mock StaffMemberRepository staffMemberRepository;
    @Mock CatalogOrderRepository catalogOrderRepository;
    @Mock AnnualPlanItemRepository annualPlanItemRepository;
    @Mock FirefightingRequestRepository firefightingRequestRepository;
    @Mock FirefightingQuotationRepository firefightingQuotationRepository;
    @Mock StudentService studentService;
    @Mock FeeService feeService;
    @Mock AttendanceService attendanceService;

    @InjectMocks WorkspaceService workspaceService;

    private AcademicYearEntity activeYear;

    @BeforeEach
    void setUp() {
        activeYear = new AcademicYearEntity();
        activeYear.setId("ay_2025_26");
        activeYear.setLabel("2025-26");
        activeYear.setActive(true);

        // Default stubs — all repos return empty, no NPE
        when(firefightingRequestRepository.findByStatus(any())).thenReturn(List.of());
        when(firefightingRequestRepository.findBySchool_IdAndStatus(any(), any())).thenReturn(List.of());
        when(catalogOrderRepository.findByStatusOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(catalogOrderRepository.findBySchool_IdAndStatus(any(), any())).thenReturn(List.of());
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear));
        when(feeService.feeOverdueCount(any(), any())).thenReturn(0L);
    }

    @Test
    @DisplayName("commandCentreCards: superadmin (schoolId=null) returns list without NPE")
    void commandCentreCards_superadmin_returnsEmptyList() {
        AuthUser actor = AuthUser.identity(1L, "Super Admin", "superadmin@custoking.com", "SUPERADMIN", null, null);

        assertThatCode(() -> {
            List<Map<String, Object>> cards = workspaceService.commandCentreCards(actor, null);
            assertThat(cards).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("commandCentreCards: school admin (schoolId=1) returns list without NPE")
    void commandCentreCards_schoolAdmin_returnsListWithoutNpe() {
        AuthUser actor = AuthUser.identity(2L, "School Admin", "admin@demo.custoking.com", "ADMIN", null, null);

        assertThatCode(() -> {
            List<Map<String, Object>> cards = workspaceService.commandCentreCards(actor, 1L);
            assertThat(cards).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("commandCentreCards: superadmin sees FF approval card when requests are pending")
    void commandCentreCards_superadmin_seesApprovalCard_whenPending() {
        AuthUser actor = AuthUser.identity(1L, "Super Admin", "superadmin@custoking.com", "SUPERADMIN", null, null);

        com.custoking.ims.entity.FirefightingRequestEntity pending =
                new com.custoking.ims.entity.FirefightingRequestEntity();
        pending.setCode("FF-001");
        pending.setTitle("Test");
        pending.setStatus("AWAITING_BURSAR");

        when(firefightingRequestRepository.findByStatus("AWAITING_BURSAR")).thenReturn(List.of(pending));
        when(firefightingRequestRepository.findByStatus("AWAITING_PRINCIPAL")).thenReturn(List.of());

        List<Map<String, Object>> cards = workspaceService.commandCentreCards(actor, null);
        assertThat(cards).anyMatch(c -> "cc-ff-approval".equals(c.get("id")));
    }
}
