package com.custoking.ims.commandcenter;

import com.custoking.ims.commandcenter.dto.*;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@Transactional
public class CommandCenterService {

    private final CommandCenterActionRepository actionRepo;
    private final CommandCenterFeedRepository feedRepo;
    private final PaymentRecordRepository paymentRepo;
    private final FeeAssignmentRepository feeAssignmentRepo;
    private final FirefightingRequestRepository ffRepo;
    private final CatalogOrderRepository orderRepo;
    private final AttendanceDailyRepository attendanceRepo;
    private final SchoolRepository schoolRepo;

    public CommandCenterService(CommandCenterActionRepository actionRepo,
                                CommandCenterFeedRepository feedRepo,
                                PaymentRecordRepository paymentRepo,
                                FeeAssignmentRepository feeAssignmentRepo,
                                FirefightingRequestRepository ffRepo,
                                CatalogOrderRepository orderRepo,
                                AttendanceDailyRepository attendanceRepo,
                                SchoolRepository schoolRepo) {
        this.actionRepo = actionRepo;
        this.feedRepo = feedRepo;
        this.paymentRepo = paymentRepo;
        this.feeAssignmentRepo = feeAssignmentRepo;
        this.ffRepo = ffRepo;
        this.orderRepo = orderRepo;
        this.attendanceRepo = attendanceRepo;
        this.schoolRepo = schoolRepo;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommandCenterActionResponse> getActions(AuthUser actor, Long schoolId) {
        List<CommandCenterActionEntity> actions;
        if (actor.platformAdmin()) {
            actions = actionRepo.findByStatusOrderByCreatedAtDesc("OPEN");
        } else {
            actions = schoolId != null
                    ? actionRepo.findBySchoolIdAndStatusOrderByCreatedAtDesc(schoolId, "OPEN")
                    : List.of();
        }
        return actions.stream().map(CommandCenterActionResponse::from).toList();
    }

    public CommandCenterActionResponse acceptAction(UUID id, AuthUser actor) {
        CommandCenterActionEntity action = actionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found"));
        assertActionAccess(action, actor);
        action.setStatus("ACCEPTED");
        action.setAcceptedBy(actor.userId());
        action.setAcceptedAt(OffsetDateTime.now());
        actionRepo.save(action);
        recordFeed(action.getSchoolId(), action.getModule(), "ACTION_ACCEPTED",
                "Action accepted: " + action.getTitle(), "info", actor.userId());
        return CommandCenterActionResponse.from(action);
    }

    public CommandCenterActionResponse dismissAction(UUID id, String reason, AuthUser actor) {
        CommandCenterActionEntity action = actionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found"));
        assertActionAccess(action, actor);
        action.setStatus("DISMISSED");
        action.setDismissedBy(actor.userId());
        action.setDismissedAt(OffsetDateTime.now());
        action.setDismissedReason(reason);
        actionRepo.save(action);
        recordFeed(action.getSchoolId(), action.getModule(), "ACTION_DISMISSED",
                "Action dismissed: " + action.getTitle(), "info", actor.userId());
        return CommandCenterActionResponse.from(action);
    }

    // ── Feed ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommandCenterFeedItemResponse> getFeed(AuthUser actor, Long schoolId, int limit) {
        int cap = Math.min(limit, 50);
        PageRequest page = PageRequest.of(0, cap);
        List<CommandCenterFeedEntity> items;
        if (actor.platformAdmin()) {
            items = feedRepo.findAllOrderByCreatedAtDesc(page);
        } else if (schoolId != null) {
            items = feedRepo.findBySchoolIdOrGlobalOrderByCreatedAtDesc(schoolId, page);
        } else {
            items = List.of();
        }
        return items.stream().map(CommandCenterFeedItemResponse::from).toList();
    }

    // ── Daily brief ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DailyBriefResponse getDailyBrief(AuthUser actor, Long schoolId) {
        List<CommandCenterActionEntity> openActions = actor.platformAdmin()
                ? actionRepo.findByStatusOrderByCreatedAtDesc("OPEN")
                : schoolId != null
                    ? actionRepo.findBySchoolIdAndStatusOrderByCreatedAtDesc(schoolId, "OPEN")
                    : List.of();

        long criticalCount = openActions.stream().filter(a -> "CRITICAL".equalsIgnoreCase(a.getUrgency())).count();
        long highCount = openActions.stream().filter(a -> "HIGH".equalsIgnoreCase(a.getUrgency())).count();

        String focusModule = openActions.isEmpty() ? "Operations"
                : capitalize(openActions.get(0).getModule());

        List<String> highlights = new ArrayList<>();
        List<String> risks = new ArrayList<>();

        openActions.stream()
                .filter(a -> a.getImpact() != null && !a.getImpact().isBlank())
                .limit(3)
                .forEach(a -> highlights.add(a.getImpact()));
        if (highlights.isEmpty()) highlights.add("Dashboard loaded with live data");

        openActions.stream()
                .filter(a -> "HIGH".equalsIgnoreCase(a.getUrgency()) || "CRITICAL".equalsIgnoreCase(a.getUrgency()))
                .limit(3)
                .forEach(a -> risks.add(a.getTitle()));

        String summary = buildSummary(criticalCount, highCount, openActions.size());
        String nextStep = openActions.isEmpty() ? "All actions are clear."
                : "Start by reviewing: " + openActions.get(0).getTitle();

        return new DailyBriefResponse(
                "Today's operational brief", summary,
                focusModule + " actions", highlights, risks, nextStep
        );
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    public void recordFeed(Long schoolId, String module, String eventType, String title,
                           String severity, Long actorUserId) {
        CommandCenterFeedEntity f = new CommandCenterFeedEntity();
        f.setSchoolId(schoolId);
        f.setModule(module != null ? module : "system");
        f.setEventType(eventType);
        f.setTitle(title);
        f.setSeverity(severity != null ? severity : "info");
        f.setActorUserId(actorUserId);
        feedRepo.save(f);
    }

    private void assertActionAccess(CommandCenterActionEntity action, AuthUser actor) {
        if (!actor.platformAdmin() && action.getSchoolId() != null) {
            Long actorSchool = TenantContext.get();
            if (actorSchool != null && !actorSchool.equals(action.getSchoolId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this action");
            }
        }
    }

    private String buildSummary(long critical, long high, int total) {
        if (total == 0) return "No open actions — everything is clear today.";
        StringBuilder sb = new StringBuilder();
        if (critical > 0) sb.append(critical).append(" critical");
        if (high > 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(high).append(" high-priority");
        }
        sb.append(" action").append(total > 1 ? "s" : "").append(" need attention today.");
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CommandCenterSummaryResponse getSummary(AuthUser actor, Long schoolId) {
        if (actor.platformAdmin()) {
            return buildPlatformSummary();
        }
        return buildSchoolSummary(schoolId);
    }

    private CommandCenterSummaryResponse buildSchoolSummary(Long schoolId) {
        if (schoolId == null) {
            return new CommandCenterSummaryResponse(null, "SCHOOL", OffsetDateTime.now(),
                    List.of(), List.of());
        }

        long feesPaid = paymentRepo.sumAmountBySchoolId(schoolId);
        long overdueCount = feeAssignmentRepo.countOverdueByYearAndSchool("ay_2025_26", schoolId);
        long openFF = ffRepo.countBySchool_IdAndStatusNot(schoolId, "FULFILLED");
        long pendingFFApprovals = ffRepo.countBySchool_IdAndStatus(schoolId, "AWAITING_PRINCIPAL")
                + ffRepo.countBySchool_IdAndStatus(schoolId, "AWAITING_BURSAR");

        List<String> activeOrderStatuses = List.of("SUBMITTED", "AWAITING_APPROVAL", "IN_TRANSIT",
                "AWAITING_DESIGN_APPROVAL", "DESIGN_APPROVED", "PROCESSING");
        long activeOrders = orderRepo.findBySchool_IdAndStatusIn(schoolId, activeOrderStatuses).size();

        long attendanceSections = attendanceRepo
                .findByAttendanceDateAndAcademicYear_Id(LocalDate.now(), "ay_2025_26").size();

        List<CommandCenterActionEntity> openActions =
                actionRepo.findBySchoolIdAndStatusOrderByCreatedAtDesc(schoolId, "OPEN");

        List<CommandCenterSummaryResponse.KpiCard> kpis = List.of(
                new CommandCenterSummaryResponse.KpiCard(
                        "fees_collected", "Fees Collected",
                        formatLakh(feesPaid), overdueCount + " students overdue",
                        overdueCount > 20 ? "warning" : "success",
                        "fees", "collected"),
                new CommandCenterSummaryResponse.KpiCard(
                        "attendance_today", "Attendance Today",
                        attendanceSections + " sections",
                        attendanceSections > 0 ? "submitted today" : "pending",
                        attendanceSections > 0 ? "success" : "warning",
                        "attendance", "today"),
                new CommandCenterSummaryResponse.KpiCard(
                        "open_firefighting", "Open Firefighting",
                        String.valueOf(openFF), pendingFFApprovals + " need approval",
                        pendingFFApprovals > 0 ? "critical" : "success",
                        "firefighting", "urgent"),
                new CommandCenterSummaryResponse.KpiCard(
                        "orders_in_progress", "Orders In Progress",
                        String.valueOf(activeOrders), activeOrders + " active",
                        activeOrders > 5 ? "warning" : "success",
                        "orders", "active")
        );

        List<CommandCenterSummaryResponse.CriticalAlert> alerts = openActions.stream()
                .filter(a -> "CRITICAL".equalsIgnoreCase(a.getUrgency())
                        || "HIGH".equalsIgnoreCase(a.getUrgency()))
                .limit(3)
                .map(a -> new CommandCenterSummaryResponse.CriticalAlert(
                        a.getTitle(), a.getModule(), a.getUrgency().toLowerCase()))
                .toList();

        return new CommandCenterSummaryResponse(schoolId, "SCHOOL", OffsetDateTime.now(), kpis, alerts);
    }

    private CommandCenterSummaryResponse buildPlatformSummary() {
        long totalSchools = schoolRepo.count();
        long totalActions = actionRepo.findByStatusOrderByCreatedAtDesc("OPEN").size();

        List<CommandCenterSummaryResponse.KpiCard> kpis = List.of(
                new CommandCenterSummaryResponse.KpiCard(
                        "active_schools", "Active Schools",
                        String.valueOf(totalSchools), "platform-wide", "success",
                        "schools", "active"),
                new CommandCenterSummaryResponse.KpiCard(
                        "open_actions", "Open Actions",
                        String.valueOf(totalActions), "across all schools",
                        totalActions > 10 ? "warning" : "success",
                        "command-centre", "open"),
                new CommandCenterSummaryResponse.KpiCard(
                        "platform_orders", "Orders",
                        "Active", "all schools", "info",
                        "orders", "all"),
                new CommandCenterSummaryResponse.KpiCard(
                        "firefighting_sla", "Firefighting SLA",
                        "Monitoring", "platform-wide", "info",
                        "firefighting", "all")
        );

        List<CommandCenterSummaryResponse.CriticalAlert> alerts =
                actionRepo.findByStatusOrderByCreatedAtDesc("OPEN").stream()
                        .filter(a -> "CRITICAL".equalsIgnoreCase(a.getUrgency()))
                        .limit(3)
                        .map(a -> new CommandCenterSummaryResponse.CriticalAlert(
                                a.getTitle(), a.getModule(), "critical"))
                        .toList();

        return new CommandCenterSummaryResponse(null, "PLATFORM", OffsetDateTime.now(), kpis, alerts);
    }

    private static String formatLakh(long paise) {
        double rupees = paise / 100.0;
        if (rupees >= 100000) return String.format("₹%.1fL", rupees / 100000);
        if (rupees >= 1000) return String.format("₹%.0fK", rupees / 1000);
        return String.format("₹%.0f", rupees);
    }
}
