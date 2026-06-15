package com.custoking.ims.commandcenter;

import com.custoking.ims.repo.AcademicEventRepository;
import com.custoking.ims.repo.AcademicYearRepository;
import com.custoking.ims.repo.AttendanceDailyRepository;
import com.custoking.ims.repo.EventStudentContributionRepository;
import com.custoking.ims.repo.FeeAssignmentRepository;
import com.custoking.ims.repo.StudentReviewItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@Service
@Transactional(readOnly = true)
public class DashboardCommandCenterService {

    static final double LOW_ATTENDANCE_THRESHOLD = 0.75;

    private final AcademicYearRepository academicYearRepository;
    private final FeeAssignmentRepository feeAssignmentRepository;
    private final AttendanceDailyRepository attendanceDailyRepository;
    private final AcademicEventRepository academicEventRepository;
    private final EventStudentContributionRepository contributionRepository;
    private final StudentReviewItemRepository reviewItemRepository;
    private final VendorPaymentService vendorPaymentService;
    private final ReorderPredictionService reorderPredictionService;

    public DashboardCommandCenterService(AcademicYearRepository academicYearRepository,
                                         FeeAssignmentRepository feeAssignmentRepository,
                                         AttendanceDailyRepository attendanceDailyRepository,
                                         AcademicEventRepository academicEventRepository,
                                         EventStudentContributionRepository contributionRepository,
                                         StudentReviewItemRepository reviewItemRepository,
                                         VendorPaymentService vendorPaymentService,
                                         ReorderPredictionService reorderPredictionService) {
        this.academicYearRepository = academicYearRepository;
        this.feeAssignmentRepository = feeAssignmentRepository;
        this.attendanceDailyRepository = attendanceDailyRepository;
        this.academicEventRepository = academicEventRepository;
        this.contributionRepository = contributionRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.vendorPaymentService = vendorPaymentService;
        this.reorderPredictionService = reorderPredictionService;
    }

    public DashboardCommandCenterResponse getCommandCenter(Long schoolId) {
        var lifecycle = new DashboardCommandCenterResponse.LifecycleSection(0, 0);
        var zeroVendorDues = new DashboardCommandCenterResponse.VendorDuesSection(0, 0, 0, 0, 0);
        var zeroReorder = new DashboardCommandCenterResponse.ReorderSection(0);
        int attendanceThreshold = (int) (LOW_ATTENDANCE_THRESHOLD * 100);

        if (schoolId == null) {
            return new DashboardCommandCenterResponse(
                    new DashboardCommandCenterResponse.FeeSection(0, 0, 0),
                    new DashboardCommandCenterResponse.PhotographySection(null, 0, 0, 0),
                    lifecycle,
                    new DashboardCommandCenterResponse.AttendanceSection(0, attendanceThreshold),
                    zeroVendorDues,
                    zeroReorder);
        }

        var activeYear = academicYearRepository.findFirstByActiveTrue();
        if (activeYear.isEmpty()) {
            return new DashboardCommandCenterResponse(
                    new DashboardCommandCenterResponse.FeeSection(0, 0, 0),
                    new DashboardCommandCenterResponse.PhotographySection(null, 0, 0, 0),
                    lifecycle,
                    new DashboardCommandCenterResponse.AttendanceSection(0, attendanceThreshold),
                    zeroVendorDues,
                    reorderPredictionService.getSummary(schoolId));
        }

        String yearId = activeYear.get().getId();
        LocalDate today = LocalDate.now();

        long defaulterCount = feeAssignmentRepository.countOverdueByYearAndSchool(yearId, schoolId);
        Long rawSum = feeAssignmentRepository.sumOverdueAmountByYearAndSchool(yearId, schoolId);
        long totalOverdueAmountPaise = rawSum != null ? rawSum : 0L;

        OffsetDateTime oldestAt = feeAssignmentRepository.findOldestOverdueAssignedAt(yearId, schoolId);
        int oldestDueDays = oldestAt != null
                ? (int) Math.max(0, ChronoUnit.DAYS.between(oldestAt.toLocalDate(), today))
                : 0;

        long belowThreshold = attendanceDailyRepository.countSectionsBelowThreshold(
                today, yearId, schoolId, LOW_ATTENDANCE_THRESHOLD);

        // Photography section: find ACTIVE class photography event
        var photographySection = academicEventRepository
                .findFirstBySchoolIdAndEventTypeAndStatus(
                        schoolId, ClassPhotographyService.EVENT_TYPE_CLASS_PHOTOGRAPHY, "ACTIVE")
                .map(evt -> {
                    Long rawCollected = contributionRepository.sumPaidAmount(evt.getId());
                    long collected = rawCollected != null ? rawCollected : 0L;
                    long pending = Math.max(0, evt.getStudentContributionTarget() - collected);
                    return new DashboardCommandCenterResponse.PhotographySection(
                            evt.getId(), collected, pending, evt.getStudentContributionTarget());
                })
                .orElseGet(() -> new DashboardCommandCenterResponse.PhotographySection(null, 0, 0, 0));

        // Lifecycle section: pending review items across active campaigns
        long pendingReviewCount = reviewItemRepository.countPendingItemsForActiveSchoolCampaigns(schoolId);
        lifecycle = new DashboardCommandCenterResponse.LifecycleSection((int) pendingReviewCount, 0);

        var vendorDues = vendorPaymentService.getSummary(schoolId);
        var reorderSection = reorderPredictionService.getSummary(schoolId);

        return new DashboardCommandCenterResponse(
                new DashboardCommandCenterResponse.FeeSection(defaulterCount, totalOverdueAmountPaise, oldestDueDays),
                photographySection,
                lifecycle,
                new DashboardCommandCenterResponse.AttendanceSection(
                        (int) belowThreshold, attendanceThreshold),
                vendorDues,
                reorderSection);
    }
}
