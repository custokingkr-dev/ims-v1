package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);

    private final AttendanceDailyRepository attendanceDailyRepository;
    private final AttendanceStudentRecordRepository attendanceStudentRecordRepository;
    private final SchoolSectionRepository sectionRepository;
    private final SchoolClassRepository classRepository;
    private final StudentRepository studentRepository;
    private final AcademicYearRepository academicYearRepository;
    private final AuditLogService auditLogService;

    public AttendanceService(AttendanceDailyRepository attendanceDailyRepository,
                              AttendanceStudentRecordRepository attendanceStudentRecordRepository,
                              SchoolSectionRepository sectionRepository,
                              SchoolClassRepository classRepository,
                              StudentRepository studentRepository,
                              AcademicYearRepository academicYearRepository,
                              AuditLogService auditLogService) {
        this.attendanceDailyRepository = attendanceDailyRepository;
        this.attendanceStudentRecordRepository = attendanceStudentRecordRepository;
        this.sectionRepository = sectionRepository;
        this.classRepository = classRepository;
        this.studentRepository = studentRepository;
        this.academicYearRepository = academicYearRepository;
        this.auditLogService = auditLogService;
    }

    public Map<String, Object> attendanceDailySummary(String dateInput, AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        return attendanceDailySummary(dateInput, schoolId);
    }

    public Map<String, Object> attendanceDailySummary(String dateInput, Long schoolId) {
        LocalDate date = parseDate(dateInput);
        String ayId = currentAcademicYearId();
        
        List<AttendanceDailyEntity> records = attendanceDailyRepository
                .findByAttendanceDateAndAcademicYear_Id(date, ayId).stream()
                .filter(r -> r.getSection() != null && r.getSection().getSchool() != null
                        && schoolId.equals(r.getSection().getSchool().getId()))
                .toList();
        
        List<Map<String, Object>> sections = sectionRepository.findBySchool_Id(schoolId).stream()
                .filter(section -> section != null && section.getSchoolClass() != null)
                .map(section -> {
                    AttendanceDailyEntity rec = records.stream()
                            .filter(r -> r.getSection() != null && r.getSection().getId().equals(section.getId()))
                            .findFirst().orElse(null);
                    
                    // Get student record counts for this section on this date
                    List<AttendanceStudentRecordEntity> studentRecords = 
                        attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(
                            section.getId(), date);
                    
                    int totalStudents = (int) studentRepository.countBySection_Id(section.getId());
                    int presentCount = (int) studentRecords.stream()
                        .filter(r -> r.getStatus() == AttendanceStudentRecordEntity.AttendanceStatus.PRESENT)
                        .count();
                    int absentCount = (int) studentRecords.stream()
                        .filter(r -> r.getStatus() == AttendanceStudentRecordEntity.AttendanceStatus.ABSENT)
                        .count();
                    
                    double pct = totalStudents == 0 ? 0 
                            : round((presentCount * 100.0) / totalStudents);
                    
                    // Determine status: Pending -> no student records, Saved -> records but not locked, Submitted -> locked
                    String status;
                    if (studentRecords.isEmpty()) {
                        status = "Pending";
                    } else if (rec != null && rec.isLocked()) {
                        status = "Submitted";
                    } else {
                        status = "Saved";
                    }
                    
                    return row("sectionId", section.getId(),
                            "classId", section.getSchoolClass().getId(),
                            "sectionName", section.getSchoolClass().getName() + "-" + section.getName(),
                            "totalStudents", totalStudents,
                            "presentCount", presentCount,
                            "absentCount", absentCount,
                            "presentPercent", totalStudents == 0 ? 0 : pct,
                            "teacherName", section.getTeacherName(),
                            "status", status,
                            "locked", rec != null && rec.isLocked());
                }).toList();
        
        double overall = sections.stream()
                .filter(m -> !"Pending".equals(m.get("status")))
                .mapToDouble(m -> num(m.get("presentPercent"), 0)).average().orElse(0);
        
        return row("date", date.toString(),
                "dateLabel", date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
                "overallPercent", round(overall), 
                "sections", sections,
                "allSubmitted", sections.stream().allMatch(m -> "Submitted".equals(m.get("status"))),
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
        log.info("attendance.saved date={} sectionId={} presentCount={} totalEnrolled={} actorId={}",
                date, sectionId, present, total, actor.userId());
        return row("ok", true, "message", "Saved — " + schoolClass.getName() + "-" + section.getName()
                + " · " + present + "/" + total + " present ("
                + round(total == 0 ? 0 : present * 100.0 / total) + "%)");
    }

    public Map<String, Object> submitAttendanceDay(String dateText, AuthUser actor) {
        LocalDate date = parseDate(dateText);
        Long schoolId = TenantContext.get();
        
        // Get all sections with pending or saved attendance for this school and date
        List<Map<String, Object>> summary = (List<Map<String, Object>>) attendanceDailySummary(date.toString(), schoolId).get("sections");
        
        // Find sections that are still Pending (no student records)
        List<Map<String, Object>> pendingSections = summary.stream()
            .filter(s -> "Pending".equals(s.get("status")))
            .toList();
        
        if (!pendingSections.isEmpty()) {
            String pendingNames = pendingSections.stream()
                .map(s -> String.valueOf(s.get("sectionName")))
                .collect(Collectors.joining(", "));
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                "Cannot submit day: Pending sections (no records): " + pendingNames);
        }
        
        // Lock all saved sections for this date
        List<AttendanceDailyEntity> records = attendanceDailyRepository
                .findByAttendanceDateAndAcademicYear_Id(date, currentAcademicYearId()).stream()
                .filter(r -> r.getSection() != null && r.getSection().getSchool() != null
                        && schoolId.equals(r.getSection().getSchool().getId()))
                .toList();
        
        int lockedCount = 0;
        for (AttendanceDailyEntity rec : records) {
            if (!rec.isLocked()) {
                rec.setLocked(true);
                attendanceDailyRepository.save(rec);
                lockedCount++;
                
                // Audit the section submission
                auditLogService.recordEvent(
                    "ATTENDANCE_SECTION_SUBMITTED",
                    actor.userId(),
                    schoolId,
                    "attendance_daily",
                    rec.getId(),
                    "locked=false",
                    "locked=true"
                );
            }
        }
        
        // Audit the day submission
        auditLogService.recordEvent(
            "ATTENDANCE_DAY_SUBMITTED",
            actor.userId(),
            schoolId,
            "attendance_day",
            date + "-" + schoolId,
            "sections_unlocked=" + records.size(),
            "sections_locked=" + lockedCount
        );
        
        log.info("attendance.day_submitted date={} schoolId={} sectionsLocked={} actorId={}",
                date, schoolId, lockedCount, actor.userId());
        
        return row("ok", true, "submitted", lockedCount, "date", date.toString());
    }

    /**
     * Load students and attendance records for a section on a given date.
     */
    public Map<String, Object> getSectionRegister(LocalDate date, String classId, String sectionId, AuthUser actor) {
        Long schoolId = TenantContext.get();
        
        // Validate section exists and belongs to this school
        SchoolSectionEntity section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Section not found"));
        assertSchoolOwnership("section", section.getSchool().getId(), schoolId);
        
        SchoolClassEntity schoolClass = section.getSchoolClass();
        if (schoolClass == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found for section");
        }
        
        String ayId = currentAcademicYearId();
        
        // Get or create attendance_daily record
        AttendanceDailyEntity dailyRecord = attendanceDailyRepository
                .findByAttendanceDateAndSection_IdAndAcademicYear_Id(date, sectionId, ayId)
                .orElse(null);
        
        // Load all students in the section
        List<StudentEntity> students = studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(classId, sectionId);
        
        // Load existing attendance records
        List<AttendanceStudentRecordEntity> records = attendanceStudentRecordRepository
                .findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(sectionId, date);
        
        Map<Long, AttendanceStudentRecordEntity> recordsByStudentId = records.stream()
                .collect(Collectors.toMap(r -> r.getStudent().getId(), r -> r));
        
        // Build student list with attendance status
        List<Map<String, Object>> studentList = students.stream().map(student -> {
            AttendanceStudentRecordEntity record = recordsByStudentId.get(student.getId());
            return row(
                "studentId", student.getId(),
                "fullName", student.getFullName(),
                "admissionNo", student.getAdmissionNo(),
                "rollNo", student.getRollNo() != null ? student.getRollNo() : "",
                "photoUrl", student.getPhotoUrl(),
                "status", record != null ? record.getStatus().toString() : null,
                "remarks", record != null ? (record.getRemarks() != null ? record.getRemarks() : "") : ""
            );
        }).toList();
        
        int presentCount = (int) recordsByStudentId.values().stream()
            .filter(r -> r.getStatus() == AttendanceStudentRecordEntity.AttendanceStatus.PRESENT)
            .count();
        int absentCount = (int) recordsByStudentId.values().stream()
            .filter(r -> r.getStatus() == AttendanceStudentRecordEntity.AttendanceStatus.ABSENT)
            .count();
        
        double presentPercent = studentList.isEmpty() ? 0 
            : round((presentCount * 100.0) / studentList.size());
        
        return row(
            "date", date.toString(),
            "classId", classId,
            "sectionId", sectionId,
            "sectionName", schoolClass.getName() + "-" + section.getName(),
            "locked", dailyRecord != null && dailyRecord.isLocked(),
            "totalStudents", studentList.size(),
            "presentCount", presentCount,
            "absentCount", absentCount,
            "presentPercent", presentPercent,
            "students", studentList
        );
    }

    /**
     * Save/update student attendance records for a section.
     */
    public Map<String, Object> saveSectionRegister(LocalDate date, String classId, String sectionId, 
                                                    List<Map<String, Object>> records, AuthUser actor) {
        Long schoolId = TenantContext.get();
        
        // Validate section exists and belongs to this school
        SchoolSectionEntity section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Section not found"));
        assertSchoolOwnership("section", section.getSchool().getId(), schoolId);
        
        SchoolClassEntity schoolClass = section.getSchoolClass();
        if (schoolClass == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found for section");
        }
        
        String ayId = currentAcademicYearId();
        AcademicYearEntity ay = currentAcademicYearEntity();
        
        // Get or create attendance_daily record
        AttendanceDailyEntity dailyRecord = attendanceDailyRepository
                .findByAttendanceDateAndSection_IdAndAcademicYear_Id(date, sectionId, ayId)
                .orElseGet(() -> {
                    AttendanceDailyEntity entity = new AttendanceDailyEntity();
                    entity.setId(UUID.randomUUID().toString());
                    entity.markNew();
                    entity.setAttendanceDate(date);
                    entity.setSchoolClass(schoolClass);
                    entity.setSection(section);
                    entity.setAcademicYear(ay);
                    entity.setRecordedBy(actor.userId());
                    entity.setRecordedAt(OffsetDateTime.now());
                    return entity;
                });
        
        // Check if locked
        if (dailyRecord.isLocked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, 
                "This attendance is locked and cannot be edited");
        }
        
        // Load all students in the section for validation
        List<StudentEntity> sectionStudents = studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(classId, sectionId);
        Map<Long, StudentEntity> studentMap = sectionStudents.stream()
                .collect(Collectors.toMap(StudentEntity::getId, s -> s));
        
        // Process and validate attendance records
        List<AttendanceStudentRecordEntity> recordsToSave = new ArrayList<>();
        List<String> changedStudentStatuses = new ArrayList<>();
        int presentCount = 0;
        int absentCount = 0;
        
        for (Map<String, Object> recordData : records) {
            Long studentId = longNum(recordData.get("studentId"), -1L);
            String statusStr = str(recordData.get("status"), "").toUpperCase();
            String remarks = str(recordData.get("remarks"), "");
            
            if (studentId < 0 || !studentMap.containsKey(studentId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                    "Invalid or unknown student ID: " + studentId);
            }
            
            if (!statusStr.equals("PRESENT") && !statusStr.equals("ABSENT")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                    "Invalid status for student " + studentId + ": " + statusStr);
            }
            
            AttendanceStudentRecordEntity.AttendanceStatus status = 
                AttendanceStudentRecordEntity.AttendanceStatus.valueOf(statusStr);
            
            // Check if this is an update
            AttendanceStudentRecordEntity existing = attendanceStudentRecordRepository
                .findByStudent_IdAndAttendanceDateAndAcademicYear_Id(studentId, date, ayId)
                .orElse(null);
            
            AttendanceStudentRecordEntity studentRecord;
            if (existing != null) {
                studentRecord = existing;
                if (studentRecord.getStatus() != status) {
                    changedStudentStatuses.add(studentId + ":" + studentRecord.getStatus() + "->" + status);
                }
            } else {
                studentRecord = new AttendanceStudentRecordEntity();
                studentRecord.setId(UUID.randomUUID().toString());
                studentRecord.setAttendanceDate(date);
                studentRecord.setAcademicYear(ay);
                studentRecord.setSchoolClass(schoolClass);
                studentRecord.setSection(section);
                studentRecord.setStudent(studentMap.get(studentId));
                studentRecord.setSchool(section.getSchool());
                studentRecord.setRecordedBy(actor.userId());
                studentRecord.setRecordedAt(OffsetDateTime.now());
                changedStudentStatuses.add(studentId + ":new->" + status);
            }
            
            studentRecord.setStatus(status);
            studentRecord.setRemarks(remarks);
            studentRecord.setUpdatedBy(actor.userId());
            studentRecord.setUpdatedAt(OffsetDateTime.now());
            studentRecord.setAttendanceDaily(dailyRecord);
            
            recordsToSave.add(studentRecord);
            
            if (status == AttendanceStudentRecordEntity.AttendanceStatus.PRESENT) {
                presentCount++;
            } else {
                absentCount++;
            }
        }
        
        // Persist/update attendance_daily FIRST so it is MANAGED when student records reference it.
        // (New entities use persist() via Persistable.isNew(); existing entities use merge().)
        dailyRecord.setTotalEnrolled(sectionStudents.size());
        dailyRecord.setPresentCount(presentCount);
        dailyRecord.setAbsentCount(absentCount);
        dailyRecord.setUpdatedBy(actor.userId());
        dailyRecord.setUpdatedAt(OffsetDateTime.now());
        attendanceDailyRepository.save(dailyRecord);

        // Save student records after dailyRecord is MANAGED to satisfy the FK reference
        attendanceStudentRecordRepository.saveAll(recordsToSave);
        
        // Audit the save
        auditLogService.recordEvent(
            "ATTENDANCE_SECTION_SAVED",
            actor.userId(),
            schoolId,
            "attendance_daily",
            dailyRecord.getId(),
            "present=" + (dailyRecord.getPresentCount() - presentCount) + ",absent=" + (dailyRecord.getAbsentCount() - absentCount),
            "present=" + presentCount + ",absent=" + absentCount + ",changes=[" + String.join(",", changedStudentStatuses) + "]"
        );
        
        log.info("attendance.section_saved date={} classId={} sectionId={} present={} absent={} actorId={} changes={}",
                date, classId, sectionId, presentCount, absentCount, actor.userId(), changedStudentStatuses.size());
        
        // Return updated section register
        return getSectionRegister(date, classId, sectionId, actor);
    }

    /**
     * Submit a section (lock it) - requires all students to have a record.
     */
    public Map<String, Object> submitSection(LocalDate date, String classId, String sectionId, AuthUser actor) {
        Long schoolId = TenantContext.get();
        
        // Validate section
        SchoolSectionEntity section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Section not found"));
        assertSchoolOwnership("section", section.getSchool().getId(), schoolId);
        
        String ayId = currentAcademicYearId();
        
        // Get attendance_daily record
        AttendanceDailyEntity dailyRecord = attendanceDailyRepository
                .findByAttendanceDateAndSection_IdAndAcademicYear_Id(date, sectionId, ayId)
                .orElse(null);
        
        if (dailyRecord == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                "No attendance records found for this section");
        }
        
        if (dailyRecord.isLocked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                "This section attendance is already submitted");
        }
        
        // Verify all students have a record
        List<StudentEntity> sectionStudents = studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(classId, sectionId);
        List<AttendanceStudentRecordEntity> records = attendanceStudentRecordRepository
                .findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(sectionId, date);
        
        if (records.size() != sectionStudents.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, 
                "Not all students have attendance records. " + records.size() + "/" + sectionStudents.size() + " recorded");
        }
        
        // Lock the record
        dailyRecord.setLocked(true);
        attendanceDailyRepository.save(dailyRecord);
        
        // Audit
        auditLogService.recordEvent(
            "ATTENDANCE_SECTION_SUBMITTED",
            actor.userId(),
            schoolId,
            "attendance_daily",
            dailyRecord.getId(),
            "locked=false",
            "locked=true"
        );
        
        log.info("attendance.section_submitted date={} classId={} sectionId={} actorId={}",
                date, classId, sectionId, actor.userId());
        
        return row("ok", true, "message", "Section attendance locked");
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
