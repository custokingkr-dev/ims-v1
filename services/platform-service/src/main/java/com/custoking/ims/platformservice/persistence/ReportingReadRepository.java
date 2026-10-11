package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ReportingReadRepository {

    private final JdbcClient jdbc;

    private final InvoiceReportingQueries invoices;
    private final PhotographyReportingQueries photography;
    private final AttendanceReportingQueries attendance;
    private final FeeReportingQueries fees;
    private final ProcurementReportingQueries procurement;
    private final WorkspaceReportingQueries workspace;
    private final CommandCenterReportingQueries commandCenter;

    public ReportingReadRepository(JdbcClient jdbc) {
        this(jdbc, new InvoiceReportingQueries(jdbc), new PhotographyReportingQueries(jdbc));
    }

    public ReportingReadRepository(JdbcClient jdbc, InvoiceReportingQueries invoices,
                                   PhotographyReportingQueries photography) {
        this.jdbc = jdbc;
        this.invoices = invoices;
        this.photography = photography;
        ReportingSchoolContext schools = new ReportingSchoolContext(jdbc);
        ReportingScalarQueries scalars = new ReportingScalarQueries(jdbc);
        this.attendance = new AttendanceReportingQueries(jdbc, schools);
        this.fees = new FeeReportingQueries(jdbc, schools, scalars);
        this.procurement = new ProcurementReportingQueries(jdbc);
        this.workspace = new WorkspaceReportingQueries(jdbc, schools, scalars);
        this.commandCenter = new CommandCenterReportingQueries(jdbc, schools, scalars, procurement, photography);
    }

    @Autowired
    public ReportingReadRepository(JdbcClient jdbc, InvoiceReportingQueries invoices,
                                   PhotographyReportingQueries photography,
                                   AttendanceReportingQueries attendance, FeeReportingQueries fees,
                                   ProcurementReportingQueries procurement, WorkspaceReportingQueries workspace,
                                   CommandCenterReportingQueries commandCenter) {
        this.jdbc = jdbc;
        this.invoices = invoices;
        this.photography = photography;
        this.attendance = attendance;
        this.fees = fees;
        this.procurement = procurement;
        this.workspace = workspace;
        this.commandCenter = commandCenter;
    }

    public List<CommandCenterFeedRow> feed(Long schoolId, String module, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module, event_type, title, message, severity,
                       entity_type, entity_id, actor_user_id, created_at
                FROM reporting.command_center_feed
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND (school_id = :schoolId OR school_id IS NULL)");
        if (module != null && !module.isBlank()) sql.append(" AND module = :module");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (module != null && !module.isBlank()) spec = spec.param("module", module);
        return spec.query(CommandCenterFeedRow.class).list();
    }

    public List<CommandCenterActionRow> actions(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module, urgency, confidence, title, reason, impact,
                       current_state, target_state, cta_label, status, source_type, source_id,
                       accepted_by, accepted_at, dismissed_by, dismissed_reason, dismissed_at,
                       created_at, updated_at
                FROM reporting.command_center_actions
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(CommandCenterActionRow.class).list();
    }

    public List<InvoiceRow> invoices(Long schoolId, String status, int limit) {
        return invoices.invoices(schoolId, status, limit);
    }

    public Map<String, Object> invoiceStats(Long schoolId) {
        return invoices.invoiceStats(schoolId);
    }

    public List<AcademicEventRow> academicEvents(Long schoolId, String status, int limit) {
        return photography.academicEvents(schoolId, status, limit);
    }

    public List<EventContributionRow> eventContributions(String eventId, Long schoolId, int limit) {
        return photography.eventContributions(eventId, schoolId, limit);
    }

    public Map<String, Object> classPhotographyPaymentStatus(
            Long schoolId, String classId, String sectionId, String status, int page, int size) {
        return photography.classPhotographyPaymentStatus(schoolId, classId, sectionId, status, page, size);
    }

    public List<BroadcastRow> broadcasts(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module, title, message, audience_type, channels, status,
                       scheduled_at, approved_by, approved_at, sent_by, sent_at, created_by,
                       created_at, updated_at
                FROM notification.notification_broadcasts
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(BroadcastRow.class).list();
    }

    public Map<String, Object> summary(Long schoolId) {
        return workspace.summary(schoolId);
    }

    public Map<String, Object> workspaceDashboardSummary(Long schoolId) {
        return workspace.workspaceDashboardSummary(schoolId);
    }

    public Map<String, Object> vendorDues(Long schoolId) {
        return procurement.vendorDues(schoolId);
    }

    public Map<String, Object> reorderSignals(Long schoolId) {
        return procurement.reorderSignals(schoolId);
    }

    public Map<String, Object> dashboardCommandCenter(Long schoolId) {
        return commandCenter.dashboardCommandCenter(schoolId);
    }

    public Map<String, Object> lowAttendanceSections(Long schoolId, LocalDate date) {
        return attendance.lowAttendanceSections(schoolId, date);
    }

    public List<Map<String, Object>> lowAttendanceStudents(Long schoolId, String sectionId) {
        return attendance.lowAttendanceStudents(schoolId, sectionId);
    }

    public Map<String, Object> feeDefaulters(Long schoolId, String classId, String sectionId,
                                             Integer daysOverdueMin, String reminderStatus,
                                             int page, int size) {
        return fees.feeDefaulters(schoolId, classId, sectionId, daysOverdueMin, reminderStatus, page, size);
    }

    public Map<String, Object> commandCenterSummary(Long schoolId, boolean platform) {
        return commandCenter.commandCenterSummary(schoolId, platform);
    }

    public record CommandCenterFeedRow(UUID id, Long schoolId, String module, String eventType, String title,
                                       String message, String severity, String entityType, String entityId,
                                       Long actorUserId, OffsetDateTime createdAt) {}

    public record CommandCenterActionRow(UUID id, Long schoolId, String module, String urgency, Integer confidence,
                                         String title, String reason, String impact, String currentState,
                                         String targetState, String ctaLabel, String status, String sourceType,
                                         String sourceId, Long acceptedBy, OffsetDateTime acceptedAt,
                                         Long dismissedBy, String dismissedReason, OffsetDateTime dismissedAt,
                                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record InvoiceRow(String id, String orderRef, String school, Long schoolId, String description,
                             Integer qty, Long rate, Long amount, Long gstAmount, Long total, String status,
                             String issuedAt, String dueAt, String notes, OffsetDateTime createdAt) {}

    public record AcademicEventRow(String id, Long schoolId, String academicYearId, String title, String eventType,
                                   LocalDate eventDate, Long totalBudget, Long schoolContribution,
                                   Long studentContributionTarget, String status, Long createdBy,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record EventContributionRow(String id, String eventId, Long studentId, Long schoolId, Long expectedAmount,
                                       Long paidAmount, String status, OffsetDateTime lastReminderSentAt,
                                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record BroadcastRow(UUID id, Long schoolId, String module, String title, String message,
                               String audienceType, String channels, String status, OffsetDateTime scheduledAt,
                               Long approvedBy, OffsetDateTime approvedAt, Long sentBy, OffsetDateTime sentAt,
                               Long createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
