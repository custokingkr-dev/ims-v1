package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.FeeDefaulterListResponse;
import com.custoking.ims.commandcenter.dto.SendFeeRemindersRequest;
import com.custoking.ims.commandcenter.dto.SendFeeRemindersResult;
import com.custoking.ims.entity.FeeAssignmentEntity;
import com.custoking.ims.entity.NotificationLogEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.AcademicYearRepository;
import com.custoking.ims.repo.FeeAssignmentRepository;
import com.custoking.ims.repo.NotificationLogRepository;
import com.custoking.ims.repo.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeeDefaulterService {

    static final String NOTIF_TYPE_FEE_OVERDUE = "FEE_OVERDUE";

    private final AcademicYearRepository academicYearRepository;
    private final FeeAssignmentRepository feeAssignmentRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final StudentRepository studentRepository;
    private final AuditLogService auditLogService;

    public FeeDefaulterService(AcademicYearRepository academicYearRepository,
                                FeeAssignmentRepository feeAssignmentRepository,
                                NotificationLogRepository notificationLogRepository,
                                StudentRepository studentRepository,
                                AuditLogService auditLogService) {
        this.academicYearRepository = academicYearRepository;
        this.feeAssignmentRepository = feeAssignmentRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.studentRepository = studentRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public FeeDefaulterListResponse listDefaulters(Long schoolId, String classId, String sectionId,
                                                    Integer daysOverdueMin, String reminderStatus,
                                                    int page, int size) {
        var activeYear = academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No active academic year"));

        String yearId = activeYear.getId();
        Page<FeeAssignmentEntity> pageResult = feeAssignmentRepository.findOverdueBySchool(
                yearId, schoolId, classId, sectionId, PageRequest.of(page, size));

        List<Long> studentIds = pageResult.getContent().stream()
                .map(a -> a.getStudent().getId())
                .collect(Collectors.toList());

        Map<Long, NotificationLogEntity> lastNotifByStudent = studentIds.isEmpty()
                ? Map.of()
                : notificationLogRepository.findByStudentIdInAndNotificationType(studentIds, NOTIF_TYPE_FEE_OVERDUE)
                        .stream()
                        .collect(Collectors.toMap(
                                NotificationLogEntity::getStudentId,
                                n -> n,
                                (a2, b) -> a2.getSentAt() != null && b.getSentAt() != null && a2.getSentAt().isAfter(b.getSentAt()) ? a2 : b
                        ));

        LocalDate today = LocalDate.now();

        List<FeeDefaulterListResponse.FeeDefaulterItem> items = new ArrayList<>();
        for (FeeAssignmentEntity fa : pageResult.getContent()) {
            var student = fa.getStudent();
            LocalDate dueDate = fa.getAssignedAt() != null
                    ? fa.getAssignedAt().toLocalDate()
                    : today;
            int daysDue = (int) ChronoUnit.DAYS.between(dueDate, today);

            if (daysOverdueMin != null && daysDue < daysOverdueMin) continue;

            NotificationLogEntity lastNotif = lastNotifByStudent.get(student.getId());
            String remStatus = deriveReminderStatus(lastNotif);

            if (reminderStatus != null && !reminderStatus.equalsIgnoreCase(remStatus)) continue;

            items.add(new FeeDefaulterListResponse.FeeDefaulterItem(
                    student.getId(),
                    student.getFullName(),
                    student.getAdmissionNo(),
                    student.getSchoolClass().getName(),
                    student.getSection().getName(),
                    student.getFatherName(),
                    student.getFatherContact(),
                    fa.getNetPayable() - fa.getPaidAmount(),
                    dueDate,
                    Math.max(daysDue, 0),
                    lastNotif != null ? lastNotif.getSentAt() : null,
                    remStatus,
                    "OVERDUE"
            ));
        }

        long totalOverdueAmount = feeAssignmentRepository.sumOverdueAmountByYearAndSchool(yearId, schoolId) != null
                ? feeAssignmentRepository.sumOverdueAmountByYearAndSchool(yearId, schoolId) : 0L;

        OffsetDateTime oldestAt = feeAssignmentRepository.findOldestOverdueAssignedAt(yearId, schoolId);
        int oldestDueDays = oldestAt != null
                ? (int) ChronoUnit.DAYS.between(oldestAt.toLocalDate(), today)
                : 0;

        return new FeeDefaulterListResponse(
                pageResult.getTotalElements(),
                totalOverdueAmount,
                Math.max(oldestDueDays, 0),
                items,
                page,
                size,
                pageResult.getTotalElements()
        );
    }

    @Transactional
    public SendFeeRemindersResult sendReminders(Long schoolId, AuthUser actor,
                                                 SendFeeRemindersRequest request) {
        if (request.studentIds() == null || request.studentIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "studentIds must not be empty");
        }

        List<SendFeeRemindersResult.FailedItem> failed = new ArrayList<>();
        int sentCount = 0;

        for (Long studentId : request.studentIds()) {
            var studentOpt = studentRepository.findById(studentId);
            if (studentOpt.isEmpty()) {
                failed.add(new SendFeeRemindersResult.FailedItem(studentId, "Student not found"));
                continue;
            }
            var student = studentOpt.get();

            // Cross-school guard: student must belong to the actor's school
            if (!schoolId.equals(student.getSchool().getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-school access denied");
            }

            String parentContact = resolveParentContact(student.getFatherContact(), student.getPhone());
            if (parentContact == null || parentContact.isBlank()) {
                failed.add(new SendFeeRemindersResult.FailedItem(studentId, "Missing parent contact"));
                continue;
            }

            // Persist notification log — mark SENT (demo-safe; no external provider wired)
            var log = new NotificationLogEntity();
            log.setId(UUID.randomUUID().toString());
            log.setSchoolId(schoolId);
            log.setStudentId(studentId);
            log.setParentContact(parentContact);
            log.setChannel(request.channel());
            log.setNotificationType(NOTIF_TYPE_FEE_OVERDUE);
            log.setMessage(request.message());
            log.setStatus("SENT");
            log.setSentBy(actor.userId());
            log.setSentAt(OffsetDateTime.now());
            log.setCreatedAt(OffsetDateTime.now());
            log.setUpdatedAt(OffsetDateTime.now());
            notificationLogRepository.save(log);
            sentCount++;
        }

        // Audit log
        auditLogService.recordEvent(
                "FEE_DEFAULTER_REMINDER_SENT",
                actor.userId(),
                schoolId,
                "notification_logs",
                String.join(",", request.studentIds().stream().map(String::valueOf).toList()),
                null,
                buildAuditPayload(actor, schoolId, request, sentCount, failed.size())
        );

        return new SendFeeRemindersResult(sentCount, failed.size(), failed);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String deriveReminderStatus(NotificationLogEntity n) {
        if (n == null) return "NOT_SENT";
        return switch (n.getStatus()) {
            case "SENT"    -> "SENT";
            case "FAILED"  -> "FAILED";
            default        -> "PENDING";
        };
    }

    private static String resolveParentContact(String fatherContact, String phone) {
        if (fatherContact != null && !fatherContact.isBlank()) return fatherContact;
        return phone;
    }

    private static String buildAuditPayload(AuthUser actor, Long schoolId,
                                             SendFeeRemindersRequest req,
                                             int sent, int failed) {
        return "{\"actorUserId\":" + actor.userId() +
               ",\"schoolId\":" + schoolId +
               ",\"studentCount\":" + req.studentIds().size() +
               ",\"channel\":\"" + req.channel() + "\"" +
               ",\"sentCount\":" + sent +
               ",\"failedCount\":" + failed +
               ",\"actionSource\":\"DASHBOARD_COMMAND_CENTER\"}";
    }
}
