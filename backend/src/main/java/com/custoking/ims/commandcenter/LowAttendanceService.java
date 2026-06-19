package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.*;
import com.custoking.ims.entity.NotificationLogEntity;
import com.custoking.ims.entity.StudentEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LowAttendanceService {

    static final double THRESHOLD = 0.75;
    static final int THRESHOLD_PCT = (int) (THRESHOLD * 100);
    static final String NOTIF_TYPE = "LOW_ATTENDANCE_MEETING_INVITE";

    private final AttendanceDailyRepository attendanceDailyRepository;
    private final AcademicYearRepository academicYearRepository;
    private final StudentRepository studentRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final AuditLogService auditLogService;

    public LowAttendanceService(AttendanceDailyRepository attendanceDailyRepository,
                                 AcademicYearRepository academicYearRepository,
                                 StudentRepository studentRepository,
                                 NotificationLogRepository notificationLogRepository,
                                 AuditLogService auditLogService) {
        this.attendanceDailyRepository = attendanceDailyRepository;
        this.academicYearRepository = academicYearRepository;
        this.studentRepository = studentRepository;
        this.notificationLogRepository = notificationLogRepository;
        this.auditLogService = auditLogService;
    }

    // ── Low sections list ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LowAttendanceSectionsResponse getLowAttendanceSections(Long schoolId, LocalDate date) {
        if (schoolId == null) {
            return new LowAttendanceSectionsResponse(date, THRESHOLD_PCT, List.of());
        }

        String yearId = academicYearRepository.findFirstByActiveTrue()
                .map(ay -> ay.getId())
                .orElse(null);

        if (yearId == null) {
            return new LowAttendanceSectionsResponse(date, THRESHOLD_PCT, List.of());
        }

        var rows = attendanceDailyRepository.findSectionsBelowThresholdList(date, yearId, schoolId, THRESHOLD);

        List<LowAttendanceSectionItem> items = rows.stream().map(a -> {
            var sec = a.getSection();
            double pct = a.getTotalEnrolled() == 0 ? 0.0
                    : Math.round((a.getPresentCount() * 10000.0 / a.getTotalEnrolled())) / 100.0;
            long belowCount = studentRepository
                    .findBySection_IdAndSchool_IdOrderByFullNameAsc(sec.getId(), schoolId)
                    .stream()
                    .filter(s -> s.getAttendancePercent() == null || s.getAttendancePercent() < THRESHOLD * 100)
                    .count();
            return new LowAttendanceSectionItem(
                    sec.getId(),
                    sec.getName(),
                    sec.getSchoolClass().getName(),
                    a.getPresentCount(),
                    a.getTotalEnrolled(),
                    pct,
                    belowCount
            );
        }).collect(Collectors.toList());

        return new LowAttendanceSectionsResponse(date, THRESHOLD_PCT, items);
    }

    // ── Students for a section ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LowAttendanceStudentItem> getStudentsForSection(Long schoolId, String sectionId) {
        List<StudentEntity> students = studentRepository
                .findBySection_IdAndSchool_IdOrderByFullNameAsc(sectionId, schoolId);

        List<Long> studentIds = students.stream().map(StudentEntity::getId).collect(Collectors.toList());
        Map<Long, OffsetDateTime> lastInviteMap = buildLastInviteMap(schoolId, studentIds);

        return students.stream()
                .filter(s -> s.getAttendancePercent() == null || s.getAttendancePercent() < THRESHOLD * 100)
                .map(s -> new LowAttendanceStudentItem(
                        s.getId(),
                        s.getFullName(),
                        s.getAdmissionNo(),
                        s.getSchoolClass().getName(),
                        s.getSection().getName(),
                        s.getFatherName(),
                        s.getFatherContact(),
                        s.getAttendancePercent(),
                        lastInviteMap.get(s.getId())
                ))
                .collect(Collectors.toList());
    }

    // ── Send meeting invites ─────────────────────────────────────────────────

    @Transactional
    public SendMeetingInvitesResult sendMeetingInvites(Long schoolId, SendMeetingInvitesRequest request,
                                                        AuthUser actor) {
        int sent = 0;
        List<Long> failed = new ArrayList<>();

        for (Long studentId : request.studentIds()) {
            StudentEntity student = studentRepository.findById(studentId)
                    .orElse(null);

            // Cross-school guard: silently skip (returns as failed) unknown students;
            // throw 403 only when the student clearly belongs to a different school.
            if (student == null) {
                failed.add(studentId);
                continue;
            }
            if (!schoolId.equals(student.getSchool().getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Cross-school access denied for student " + studentId);
            }

            NotificationLogEntity log = new NotificationLogEntity();
            log.setId(UUID.randomUUID().toString());
            log.setSchoolId(schoolId);
            log.setStudentId(studentId);
            log.setParentContact(student.getFatherContact());
            log.setChannel(request.channel());
            log.setNotificationType(NOTIF_TYPE);
            log.setMessage(request.message());
            log.setStatus("QUEUED");
            log.setSentBy(actor.userId());
            log.setSentAt(OffsetDateTime.now());
            log.setCreatedAt(OffsetDateTime.now());
            notificationLogRepository.save(log);
            sent++;
        }

        auditLogService.recordEvent("ATTENDANCE_MEETING_INVITE_SENT",
                actor.userId(), schoolId, "notification_logs", null, null,
                "{\"sentCount\":" + sent + ",\"failedCount\":" + failed.size()
                        + ",\"channel\":\"" + request.channel() + "\"}");

        return new SendMeetingInvitesResult(sent, failed.size(), failed);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Map<Long, OffsetDateTime> buildLastInviteMap(Long schoolId, List<Long> studentIds) {
        if (studentIds.isEmpty()) return Map.of();
        var logs = notificationLogRepository.findByStudentIdInAndNotificationType(studentIds, NOTIF_TYPE);
        Map<Long, OffsetDateTime> map = new HashMap<>();
        for (var log : logs) {
            if (log.getSchoolId().equals(schoolId)) {
                map.merge(log.getStudentId(), log.getSentAt(),
                        (existing, newer) -> newer != null && (existing == null || newer.isAfter(existing)) ? newer : existing);
            }
        }
        return map;
    }
}
