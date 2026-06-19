package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.ClassPhotographyPaymentStatusResponse;
import com.custoking.ims.commandcenter.dto.SendEventPaymentRemindersRequest;
import com.custoking.ims.commandcenter.dto.SendEventPaymentRemindersResult;
import com.custoking.ims.entity.AcademicEventEntity;
import com.custoking.ims.entity.NotificationLogEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.AcademicEventRepository;
import com.custoking.ims.repo.EventStudentContributionRepository;
import com.custoking.ims.repo.NotificationLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ClassPhotographyService {

    public static final String EVENT_TYPE_CLASS_PHOTOGRAPHY = "CLASS_PHOTOGRAPHY";
    static final String NOTIF_TYPE_EVENT_PAYMENT = "EVENT_PAYMENT";

    private final AcademicEventRepository academicEventRepository;
    private final EventStudentContributionRepository contributionRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final AuditLogService auditLogService;

    public ClassPhotographyService(AcademicEventRepository academicEventRepository,
                                   EventStudentContributionRepository contributionRepository,
                                   NotificationLogRepository notificationLogRepository,
                                   AuditLogService auditLogService) {
        this.academicEventRepository = academicEventRepository;
        this.contributionRepository = contributionRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public ClassPhotographyPaymentStatusResponse getPaymentStatus(
            Long schoolId, String classId, String sectionId, String status, int page, int size) {

        var eventOpt = academicEventRepository.findFirstBySchoolIdAndEventTypeAndStatus(
                schoolId, EVENT_TYPE_CLASS_PHOTOGRAPHY, "ACTIVE");

        if (eventOpt.isEmpty()) {
            return new ClassPhotographyPaymentStatusResponse(
                    null, null, null, 0, 0, 0, 0, 0, List.of(), page, size, 0);
        }

        AcademicEventEntity event = eventOpt.get();
        Page<com.custoking.ims.entity.EventStudentContributionEntity> pageResult =
                contributionRepository.findByEventAndSchool(
                        event.getId(), schoolId, classId, sectionId, status,
                        PageRequest.of(page, size));

        Long rawCollected = contributionRepository.sumPaidAmount(event.getId());
        long collectedAmount = rawCollected != null ? rawCollected : 0L;
        long pendingAmount = Math.max(0, event.getStudentContributionTarget() - collectedAmount);

        var items = pageResult.getContent().stream().map(c -> {
            var student = c.getStudent();
            String parentPhone = student.getFatherContact() != null ? student.getFatherContact() : student.getPhone();
            long due = Math.max(0, c.getExpectedAmount() - c.getPaidAmount());
            return new ClassPhotographyPaymentStatusResponse.ContributionItem(
                    student.getId(),
                    student.getFullName(),
                    student.getAdmissionNo(),
                    student.getSchoolClass().getName(),
                    student.getSection().getName(),
                    parentPhone,
                    c.getExpectedAmount(),
                    c.getPaidAmount(),
                    due,
                    c.getStatus(),
                    c.getLastReminderSentAt()
            );
        }).toList();

        return new ClassPhotographyPaymentStatusResponse(
                event.getId(),
                event.getTitle(),
                event.getEventDate(),
                event.getTotalBudget(),
                event.getSchoolContribution(),
                event.getStudentContributionTarget(),
                collectedAmount,
                pendingAmount,
                items,
                page,
                size,
                pageResult.getTotalElements()
        );
    }

    @Transactional
    public SendEventPaymentRemindersResult sendPaymentReminders(
            Long schoolId, String eventId, AuthUser actor, SendEventPaymentRemindersRequest request) {

        // Validate event: belongs to school and is ACTIVE
        AcademicEventEntity event = academicEventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));

        if (!schoolId.equals(event.getSchoolId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-school access denied");
        }
        if (!"ACTIVE".equals(event.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Reminders can only be sent for ACTIVE events");
        }

        List<SendEventPaymentRemindersResult.FailedItem> failed = new ArrayList<>();
        int sentCount = 0;

        for (Long studentId : request.studentIds()) {
            var contribOpt = contributionRepository.findByEvent_IdAndStudent_IdAndSchoolId(
                    eventId, studentId, schoolId);

            if (contribOpt.isEmpty()) {
                failed.add(new SendEventPaymentRemindersResult.FailedItem(
                        studentId, "Student not found in this event"));
                continue;
            }

            var contrib = contribOpt.get();
            var student = contrib.getStudent();

            String parentContact = student.getFatherContact() != null && !student.getFatherContact().isBlank()
                    ? student.getFatherContact()
                    : student.getPhone();

            if (parentContact == null || parentContact.isBlank()) {
                failed.add(new SendEventPaymentRemindersResult.FailedItem(
                        studentId, "Missing parent contact"));
                continue;
            }

            // Persist notification log (demo-safe: mark SENT immediately)
            var log = new NotificationLogEntity();
            log.setId(UUID.randomUUID().toString());
            log.setSchoolId(schoolId);
            log.setStudentId(studentId);
            log.setParentContact(parentContact);
            log.setChannel(request.channel());
            log.setNotificationType(NOTIF_TYPE_EVENT_PAYMENT);
            log.setMessage(request.message());
            log.setStatus("SENT");
            log.setSentBy(actor.userId());
            log.setSentAt(OffsetDateTime.now());
            log.setCreatedAt(OffsetDateTime.now());
            log.setUpdatedAt(OffsetDateTime.now());
            notificationLogRepository.save(log);

            // Update last_reminder_sent_at
            contrib.setLastReminderSentAt(OffsetDateTime.now());
            contrib.setUpdatedAt(OffsetDateTime.now());
            contributionRepository.save(contrib);

            sentCount++;
        }

        auditLogService.recordEvent(
                "CLASS_PHOTOGRAPHY_PAYMENT_REMINDER_SENT",
                actor.userId(),
                schoolId,
                "notification_logs",
                String.join(",", request.studentIds().stream().map(String::valueOf).toList()),
                null,
                buildAuditPayload(actor, schoolId, eventId, request, sentCount, failed.size())
        );

        return new SendEventPaymentRemindersResult(sentCount, failed.size(), failed);
    }

    private static String buildAuditPayload(AuthUser actor, Long schoolId, String eventId,
                                             SendEventPaymentRemindersRequest req,
                                             int sent, int failed) {
        return "{\"actorUserId\":" + actor.userId() +
               ",\"schoolId\":" + schoolId +
               ",\"eventId\":\"" + eventId + "\"" +
               ",\"studentCount\":" + req.studentIds().size() +
               ",\"channel\":\"" + req.channel() + "\"" +
               ",\"sentCount\":" + sent +
               ",\"failedCount\":" + failed +
               ",\"actionSource\":\"DASHBOARD_COMMAND_CENTER\"}";
    }
}
