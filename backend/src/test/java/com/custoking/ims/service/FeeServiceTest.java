package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.entity.*;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FeeService — covers paise math, fee assignment net-payable
 * calculation, payment validation, and fee band validation.
 *
 * All repository calls are mocked; no Spring context or database is needed.
 * Money amounts are stored and compared in PAISE (1 INR = 100 paise).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FeeService")
class FeeServiceTest {

    @Mock FeeBandRepository feeBandRepository;
    @Mock FeeItemRepository feeItemRepository;
    @Mock FeeAssignmentRepository feeAssignmentRepository;
    @Mock PaymentRecordRepository paymentRecordRepository;
    @Mock AcademicYearRepository academicYearRepository;
    @Mock StudentRepository studentRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks FeeService feeService;

    private AcademicYearEntity activeYear;
    private StudentEntity student;
    private FeeBandEntity band;

    @BeforeEach
    void setUp() {
        activeYear = new AcademicYearEntity();
        activeYear.setId("AY-2025");
        activeYear.setLabel("2025-26");
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear));

        student = new StudentEntity();
        student.setId(10L);
        student.setFullName("Test Student");

        band = new FeeBandEntity();
        band.setId("band-1");
        band.setName("General");
        band.setClassFrom(1);
        band.setClassTo(5);
        band.setDiscount(0.0);
        band.setActiveSchedulesCsv("Annual,Term");
        band.setAcademicYear(activeYear);
        band.setCreatedAt(OffsetDateTime.now());
        band.setUpdatedAt(OffsetDateTime.now());
    }

    // ── Payment validation ────────────────────────────────────────────────────

    @Test
    @DisplayName("paymentApi: amount ≤ 0 → IllegalArgumentException")
    void paymentApi_withZeroAmount_throws() {
        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));

        Map<String, Object> req = Map.of("studentId", 10, "amount", 0);

        assertThatThrownBy(() -> feeService.recordPayment(req, actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    @DisplayName("paymentApi: negative amount → IllegalArgumentException")
    void paymentApi_withNegativeAmount_throws() {
        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));

        Map<String, Object> req = Map.of("studentId", 10, "amount", -500);

        assertThatThrownBy(() -> feeService.recordPayment(req, actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    @DisplayName("paymentApi: unknown student → IllegalArgumentException")
    void paymentApi_withUnknownStudent_throws() {
        when(studentRepository.findById(99L)).thenReturn(Optional.empty());

        Map<String, Object> req = Map.of("studentId", 99, "amount", 50000);

        assertThatThrownBy(() -> feeService.recordPayment(req, actor()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("paymentApi: no fee assignment → IllegalArgumentException")
    void paymentApi_withNoFeeAssignment_throws() {
        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));
        when(feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(10L, "AY-2025"))
                .thenReturn(Optional.empty());
        when(feeAssignmentRepository.findByStudent_Id(10L)).thenReturn(Optional.empty());

        Map<String, Object> req = Map.of("studentId", 10, "amount", 50000);

        assertThatThrownBy(() -> feeService.recordPayment(req, actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fee assignment not found");
    }

    @Test
    @DisplayName("paymentApi: successful payment updates paidAmount and student feeStatus")
    void paymentApi_success_updatesPaidAmountAndFeeStatus() {
        // Student has a fee assignment of ₹1000 (100000 paise), previously paid 0.
        FeeAssignmentEntity assignment = new FeeAssignmentEntity();
        assignment.setId("assign-1");
        assignment.setStudent(student);
        assignment.setAcademicYear(activeYear);
        assignment.setNetPayable(100_000L);
        assignment.setPaidAmount(0L);

        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));
        when(feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(10L, "AY-2025"))
                .thenReturn(Optional.of(assignment));

        // Pay ₹500 (50000 paise) — still overdue
        Map<String, Object> req = Map.of("studentId", 10, "amount", 50_000, "mode", "UPI");
        feeService.recordPayment(req, actor());

        // Assignment paidAmount should be updated
        assertThat(assignment.getPaidAmount()).isEqualTo(50_000L);
        // Not fully paid → status is Overdue
        assertThat(student.getFeeStatus()).isEqualTo("Overdue");

        verify(feeAssignmentRepository).save(assignment);
        verify(studentRepository).save(student);
    }

    @Test
    @DisplayName("paymentApi: final payment marks student as Paid")
    void paymentApi_finalPayment_marksPaid() {
        FeeAssignmentEntity assignment = new FeeAssignmentEntity();
        assignment.setId("assign-2");
        assignment.setStudent(student);
        assignment.setAcademicYear(activeYear);
        assignment.setNetPayable(50_000L);
        assignment.setPaidAmount(0L);

        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));
        when(feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(10L, "AY-2025"))
                .thenReturn(Optional.of(assignment));

        // Paying full amount
        Map<String, Object> req = Map.of("studentId", 10, "amount", 50_000, "mode", "Cash");
        feeService.recordPayment(req, actor());

        assertThat(assignment.getPaidAmount()).isEqualTo(50_000L);
        assertThat(student.getFeeStatus()).isEqualTo("Paid");
    }

    // ── Fee band validation ───────────────────────────────────────────────────

    @Test
    @DisplayName("createFeeStructureBand: blank name → IllegalArgumentException")
    void createBand_blankName_throws() {
        Map<String, Object> req = Map.of("name", "   ", "schedules", List.of("Annual"));

        assertThatThrownBy(() -> feeService.createFeeStructureBand(req, actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Band name is required");
    }

    @Test
    @DisplayName("createFeeStructureBand: classTo < classFrom → IllegalArgumentException")
    void createBand_classToLessThanClassFrom_throws() {
        Map<String, Object> req = Map.of("name", "Band A", "classFrom", 6, "classTo", 3,
                "schedules", List.of("Annual"));

        assertThatThrownBy(() -> feeService.createFeeStructureBand(req, actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Class to must be >= class from");
    }

    @Test
    @DisplayName("createFeeStructureBand: empty schedules → IllegalArgumentException")
    void createBand_emptySchedules_throws() {
        Map<String, Object> req = Map.of("name", "Band A", "schedules", List.of());

        assertThatThrownBy(() -> feeService.createFeeStructureBand(req, actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one payment schedule is required");
    }

    @Test
    @DisplayName("createFeeStructureBand: valid input persists band")
    void createBand_validInput_savesBand() {
        when(feeBandRepository.save(any(FeeBandEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(feeItemRepository.findByBand_IdOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());

        Map<String, Object> req = Map.of(
                "name", "Primary Band", "classFrom", 1, "classTo", 5,
                "discount", 10.0, "schedules", List.of("Annual", "Term"));

        Map<String, Object> result = feeService.createFeeStructureBand(req, actor());

        assertThat(result.get("name")).isEqualTo("Primary Band");
        assertThat(result.get("classFrom")).isEqualTo(1);
        assertThat(result.get("classTo")).isEqualTo(5);

        ArgumentCaptor<FeeBandEntity> captor = ArgumentCaptor.forClass(FeeBandEntity.class);
        verify(feeBandRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Primary Band");
        assertThat(captor.getValue().getDiscount()).isEqualTo(10.0);
    }

    // ── Net payable math (via feeAssignmentApi) ───────────────────────────────

    @Test
    @DisplayName("feeAssignmentApi: calculates net payable correctly — Annual schedule, no surcharge")
    void feeAssignment_netPayable_annualScheduleNoSurcharge() {
        // Total band = 100000 paise (two items of 50000 each)
        // bandDiscount = 10 %, manualDiscount = 5 %, schedule = Annual (no surcharge)
        // Expected net = 100000 - 10000 - 5000 + 0 = 85000 paise
        FeeItemEntity item1 = feeItem("item-1", 50_000L);
        FeeItemEntity item2 = feeItem("item-2", 50_000L);

        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));
        when(feeBandRepository.findById("band-1")).thenReturn(Optional.of(band));
        when(feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(10L, "AY-2025"))
                .thenReturn(Optional.empty());
        when(feeItemRepository.findByBand_IdOrderByCreatedAtAsc("band-1"))
                .thenReturn(List.of(item1, item2));

        Map<String, Object> req = Map.of(
                "studentId", 10, "bandId", "band-1", "schedule", "Annual",
                "bandDiscount", 10.0, "manualDiscount", 5.0, "surcharge", 0.0);

        ArgumentCaptor<FeeAssignmentEntity> captor = ArgumentCaptor.forClass(FeeAssignmentEntity.class);
        feeService.feeAssignmentApi(req, actor());

        verify(feeAssignmentRepository).save(captor.capture());
        assertThat(captor.getValue().getNetPayable()).isEqualTo(85_000L);
    }

    @Test
    @DisplayName("feeAssignmentApi: Annual schedule does not apply surcharge")
    void feeAssignment_annualSchedule_ignoresSurcharge() {
        // Even if surcharge = 20 %, Annual schedule means surcharge = 0
        FeeItemEntity item = feeItem("item-1", 100_000L);

        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));
        when(feeBandRepository.findById("band-1")).thenReturn(Optional.of(band));
        when(feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(10L, "AY-2025"))
                .thenReturn(Optional.empty());
        when(feeItemRepository.findByBand_IdOrderByCreatedAtAsc("band-1"))
                .thenReturn(List.of(item));

        Map<String, Object> req = Map.of(
                "studentId", 10, "bandId", "band-1", "schedule", "Annual",
                "bandDiscount", 0.0, "manualDiscount", 0.0, "surcharge", 20.0);

        ArgumentCaptor<FeeAssignmentEntity> captor = ArgumentCaptor.forClass(FeeAssignmentEntity.class);
        feeService.feeAssignmentApi(req, actor());

        verify(feeAssignmentRepository).save(captor.capture());
        // surcharge is ignored for Annual schedule
        assertThat(captor.getValue().getNetPayable()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("feeAssignmentApi: Term schedule applies surcharge")
    void feeAssignment_termSchedule_appliesSurcharge() {
        // Total = 100000, no discounts, 5% surcharge
        // Net = 100000 + 5000 = 105000 paise
        FeeItemEntity item = feeItem("item-1", 100_000L);

        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));
        when(feeBandRepository.findById("band-1")).thenReturn(Optional.of(band));
        when(feeAssignmentRepository.findByStudent_IdAndAcademicYear_Id(10L, "AY-2025"))
                .thenReturn(Optional.empty());
        when(feeItemRepository.findByBand_IdOrderByCreatedAtAsc("band-1"))
                .thenReturn(List.of(item));

        Map<String, Object> req = Map.of(
                "studentId", 10, "bandId", "band-1", "schedule", "Term",
                "bandDiscount", 0.0, "manualDiscount", 0.0, "surcharge", 5.0);

        ArgumentCaptor<FeeAssignmentEntity> captor = ArgumentCaptor.forClass(FeeAssignmentEntity.class);
        feeService.feeAssignmentApi(req, actor());

        verify(feeAssignmentRepository).save(captor.capture());
        assertThat(captor.getValue().getNetPayable()).isEqualTo(105_000L);
    }

    @Test
    @DisplayName("feeAssignmentApi: missing schedule → IllegalArgumentException")
    void feeAssignment_blankSchedule_throws() {
        when(studentRepository.findById(10L)).thenReturn(Optional.of(student));
        when(feeBandRepository.findById("band-1")).thenReturn(Optional.of(band));

        Map<String, Object> req = Map.of("studentId", 10, "bandId", "band-1", "schedule", "");

        assertThatThrownBy(() -> feeService.feeAssignmentApi(req, actor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment schedule is required");
    }

    // ── addFeeItem validation ─────────────────────────────────────────────────

    @Test
    @DisplayName("addFeeItem: blank item name → IllegalArgumentException")
    void addFeeItem_blankName_throws() {
        when(feeBandRepository.findById("band-1")).thenReturn(Optional.of(band));
        when(feeItemRepository.findByBand_IdOrderByCreatedAtAsc("band-1")).thenReturn(List.of());

        Map<String, Object> req = Map.of("bandId", "band-1", "itemName", "  ", "amount", 1000);

        assertThatThrownBy(() -> feeService.addFeeItem(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Item name is required");
    }

    @Test
    @DisplayName("addFeeItem: unknown band → IllegalArgumentException")
    void addFeeItem_unknownBand_throws() {
        when(feeBandRepository.findById("bad-id")).thenReturn(Optional.empty());

        Map<String, Object> req = Map.of("bandId", "bad-id", "itemName", "Tuition", "amount", 1000);

        assertThatThrownBy(() -> feeService.addFeeItem(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fee band not found");
    }

    // ── buildFeesModule (used by WorkspaceService) ────────────────────────────

    @Test
    @DisplayName("buildFeesModule: no assignments returns zero collected and target")
    void buildFeesModule_noAssignments_returnsZeroSummary() {
        when(feeAssignmentRepository.findByAcademicYear_IdAndStudent_School_Id("AY-2025", 1L))
                .thenReturn(List.of());
        when(paymentRecordRepository.sumAmountBySchoolId(1L)).thenReturn(0L);

        Map<String, Object> result = feeService.buildFeesModule("AY-2025", 1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary.get("collected")).isEqualTo(0L);
        assertThat(summary.get("target")).isEqualTo(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FeeItemEntity feeItem(String id, long amountPaise) {
        FeeItemEntity item = new FeeItemEntity();
        item.setId(id);
        item.setBand(band);
        item.setName("Fee " + id);
        item.setFrequency("Annual");
        item.setAmount(amountPaise);
        item.setCreatedAt(OffsetDateTime.now());
        item.setUpdatedAt(OffsetDateTime.now());
        return item;
    }

    private AuthUser actor() {
        return AuthUser.identity(1L, "Test Actor", "actor@test.com", "ADMIN", null, null);
    }
}
