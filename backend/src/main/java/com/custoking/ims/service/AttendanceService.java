package com.custoking.ims.service;

import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class AttendanceService {

    private final AttendanceDailyRepository attendanceDailyRepository;
    private final SchoolSectionRepository sectionRepository;
    private final SchoolClassRepository classRepository;
    private final StudentRepository studentRepository;
    private final AcademicYearRepository academicYearRepository;

    public AttendanceService(AttendanceDailyRepository attendanceDailyRepository,
                              SchoolSectionRepository sectionRepository,
                              SchoolClassRepository classRepository,
                              StudentRepository studentRepository,
                              AcademicYearRepository academicYearRepository) {
        this.attendanceDailyRepository = attendanceDailyRepository;
        this.sectionRepository = sectionRepository;
        this.classRepository = classRepository;
        this.studentRepository = studentRepository;
        this.academicYearRepository = academicYearRepository;
    }

    public Map<String, Object> attendanceDailySummary(String dateInput, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        return attendanceDailySummary(dateInput, schoolId);
    }

    public Map<String, Object> attendanceDailySummary(String dateInput, Long schoolId) {
        LocalDate date = parseDate(dateInput);
        List<AttendanceDailyEntity> records = attendanceDailyRepository
                .findByAttendanceDateAndAcademicYear_Id(date, currentAcademicYearId()).stream()
                .filter(r -> r.getSection() != null && r.getSection().getSchool() != null
                        && schoolId.equals(r.getSection().getSchool().getId()))
                .toList();
        List<Map<String, Object>> sections = sectionRepository.findBySchool_Id(schoolId).stream()
                .filter(section -> section != null && section.getSchoolClass() != null)
                .map(section -> {
                    AttendanceDailyEntity rec = records.stream()
                            .filter(r -> r.getSection() != null && r.getSection().getId().equals(section.getId()))
                            .findFirst().orElse(null);
                    double pct = rec == null || rec.getTotalEnrolled() == 0 ? 0
                            : round((rec.getPresentCount() * 100.0) / rec.getTotalEnrolled());
                    return row("sectionId", section.getId(),
                            "sectionName", section.getSchoolClass().getName() + "-" + section.getName(),
                            "totalStudents", studentRepository.countBySection_Id(section.getId()),
                            "presentPercent", rec == null ? null : pct,
                            "presentCount", rec == null ? 0 : rec.getPresentCount(),
                            "teacherName", section.getTeacherName(),
                            "status", rec == null ? "Pending" : "Submitted");
                }).toList();
        double overall = sections.stream().filter(m -> m.get("presentPercent") != null)
                .mapToDouble(m -> num(m.get("presentPercent"), 0)).average().orElse(0);
        return row("date", date.toString(),
                "dateLabel", date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
                "overallPercent", round(overall), "sections", sections,
                "allSubmitted", sections.stream().noneMatch(m -> "Pending".equals(m.get("status"))),
                "nonWorkingDay", date.getDayOfWeek() == DayOfWeek.SUNDAY);
    }

    public Map<String, Object> attendanceSectionInfo(String dateInput, String classId, String sectionId,
                                                      AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        SchoolSectionEntity section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found"));
        assertSchoolOwnership("section", section.getSchool() == null ? null : section.getSchool().getId(), schoolId);
        return attendanceSectionInfo(dateInput, classId, sectionId);
    }

    private Map<String, Object> attendanceSectionInfo(String dateInput, String classId, String sectionId) {
        LocalDate date = parseDate(dateInput);
        SchoolSectionEntity section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found"));
        int total = (int) studentRepository.countBySection_Id(sectionId);
        AttendanceDailyEntity record = attendanceDailyRepository
                .findByAttendanceDateAndSection_IdAndAcademicYear_Id(date, sectionId, currentAcademicYearId())
                .orElse(null);
        return row("totalEnrolled", total, "teacherName", section.getTeacherName(),
                "existingRecord", record == null ? null : row(
                        "presentCount", record.getPresentCount(),
                        "savedAt", record.getUpdatedAt() == null
                                ? record.getRecordedAt().toString()
                                : record.getUpdatedAt().toString()));
    }

    public Map<String, Object> saveDailyAttendance(Map<String, Object> request, AuthUser actor) {
        LocalDate date = LocalDate.parse(str(request.get("date"), LocalDate.now().toString()));
        String classId = str(request.get("classId"), "");
        String sectionId = str(request.get("sectionId"), "");
        SchoolClassEntity schoolClass = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("Class not found"));
        SchoolSectionEntity section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found"));
        AttendanceDailyEntity entity = attendanceDailyRepository
                .findByAttendanceDateAndSection_IdAndAcademicYear_Id(date, sectionId, currentAcademicYearId())
                .orElseGet(AttendanceDailyEntity::new);
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setAcademicYear(currentAcademicYearEntity());
            entity.setAttendanceDate(date);
            entity.setSchoolClass(schoolClass);
            entity.setSection(section);
            entity.setRecordedBy(actor.userId());
            entity.setRecordedAt(OffsetDateTime.now());
        }
        int total = (int) longNum(request.get("totalEnrolled"), studentRepository.countBySection_Id(sectionId));
        int present = (int) longNum(request.get("presentCount"), 0);
        if (present < 0 || present > total) throw new IllegalArgumentException("Present count is invalid");
        entity.setTotalEnrolled(total);
        entity.setPresentCount(present);
        entity.setAbsentCount(Math.max(total - present, 0));
        entity.setUpdatedBy(actor.userId());
        entity.setUpdatedAt(OffsetDateTime.now());
        attendanceDailyRepository.save(entity);
        return row("ok", true, "message", "Saved — " + schoolClass.getName() + "-" + section.getName()
                + " · " + present + "/" + total + " present ("
                + round(total == 0 ? 0 : present * 100.0 / total) + "%)");
    }

    public Map<String, Object> submitAttendanceDay(String dateText, AuthUser actor) {
        LocalDate date = parseDate(dateText);
        List<AttendanceDailyEntity> records = attendanceDailyRepository
                .findByAttendanceDateAndAcademicYear_Id(date, currentAcademicYearId());
        records.forEach(r -> r.setLocked(true));
        attendanceDailyRepository.saveAll(records);
        return row("ok", true, "submitted", records.size());
    }

    // ── Private helpers ──────────────────────────────────────────────

    private AcademicYearEntity currentAcademicYearEntity() {
        return academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private String currentAcademicYearId() {
        return currentAcademicYearEntity().getId();
    }

    private void assertSchoolOwnership(String entityLabel, Long entitySchoolId, Long actorSchoolId) {
        if (actorSchoolId != null && entitySchoolId != null && !actorSchoolId.equals(entitySchoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have access to this " + entityLabel);
        }
    }

    private LocalDate parseDate(String input) {
        if (input == null || input.isBlank() || "today".equalsIgnoreCase(input)) return LocalDate.now();
        return LocalDate.parse(input);
    }

    private double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private double num(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
