package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AttendanceService")
class AttendanceServiceTest {

    @Mock AttendanceDailyRepository attendanceDailyRepository;
    @Mock AttendanceStudentRecordRepository attendanceStudentRecordRepository;
    @Mock SchoolSectionRepository sectionRepository;
    @Mock SchoolClassRepository classRepository;
    @Mock StudentRepository studentRepository;
    @Mock AcademicYearRepository academicYearRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks AttendanceService attendanceService;

    private static final Long SCHOOL_ID = 1L;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 13);
    private static final String AY_ID = "AY-2025";
    private static final String CLASS_ID = "class-1a";
    private static final String SECTION_ID = "section-1a";

    private AcademicYearEntity academicYear;
    private SchoolEntity school;
    private SchoolClassEntity schoolClass;
    private SchoolSectionEntity section;

    @BeforeEach
    void setUp() {
        TenantContext.set(SCHOOL_ID);

        academicYear = new AcademicYearEntity();
        academicYear.setId(AY_ID);
        academicYear.setLabel("2025-26");
        academicYear.setActive(true);
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(academicYear));

        school = new SchoolEntity();
        school.setId(SCHOOL_ID);

        schoolClass = new SchoolClassEntity();
        schoolClass.setId(CLASS_ID);
        schoolClass.setName("Class 1");

        section = new SchoolSectionEntity();
        section.setId(SECTION_ID);
        section.setName("A");
        section.setSchoolClass(schoolClass);
        section.setSchool(school);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── getSectionRegister ────────────────────────────────────────────────────

    @Test
    @DisplayName("getSectionRegister: returns all students with null status when no records exist")
    void getSectionRegister_noRecords_returnsStudentsWithNullStatus() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");
        StudentEntity s2 = student(20L, "Bob Sharma", "ADM002");

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1, s2));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.empty());
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of());

        Map<String, Object> result = attendanceService.getSectionRegister(DATE, CLASS_ID, SECTION_ID, actor());

        assertThat(result.get("totalStudents")).isEqualTo(2);
        assertThat(result.get("presentCount")).isEqualTo(0);
        assertThat(result.get("locked")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) result.get("students");
        assertThat(students).hasSize(2);
        assertThat(students.get(0).get("status")).isNull();
        assertThat(students.get(0).get("fullName")).isEqualTo("Alice Kumar");
    }

    @Test
    @DisplayName("getSectionRegister: reflects saved PRESENT/ABSENT statuses")
    void getSectionRegister_withRecords_returnsSavedStatuses() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");

        AttendanceStudentRecordEntity rec = studentRecord(s1, AttendanceStudentRecordEntity.AttendanceStatus.PRESENT);

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.empty());
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of(rec));

        Map<String, Object> result = attendanceService.getSectionRegister(DATE, CLASS_ID, SECTION_ID, actor());

        assertThat(result.get("presentCount")).isEqualTo(1);
        assertThat(result.get("absentCount")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) result.get("students");
        assertThat(students.get(0).get("status")).isEqualTo("PRESENT");
    }

    // ── saveSectionRegister ───────────────────────────────────────────────────

    @Test
    @DisplayName("saveSectionRegister: creates attendance_daily and student records on first save")
    void saveSectionRegister_firstSave_createsEntities() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");
        StudentEntity s2 = student(20L, "Bob Sharma", "ADM002");

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1, s2));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.empty());
        when(attendanceStudentRecordRepository.findByStudent_IdAndAttendanceDateAndAcademicYear_Id(10L, DATE, AY_ID))
                .thenReturn(Optional.empty());
        when(attendanceStudentRecordRepository.findByStudent_IdAndAttendanceDateAndAcademicYear_Id(20L, DATE, AY_ID))
                .thenReturn(Optional.empty());
        when(attendanceStudentRecordRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of());

        attendanceService.saveSectionRegister(DATE, CLASS_ID, SECTION_ID,
                List.of(
                        Map.of("studentId", 10, "status", "PRESENT", "remarks", ""),
                        Map.of("studentId", 20, "status", "ABSENT", "remarks", "Sick")
                ), actor());

        ArgumentCaptor<AttendanceDailyEntity> dailyCaptor = ArgumentCaptor.forClass(AttendanceDailyEntity.class);
        verify(attendanceDailyRepository).save(dailyCaptor.capture());
        AttendanceDailyEntity saved = dailyCaptor.getValue();
        assertThat(saved.getPresentCount()).isEqualTo(1);
        assertThat(saved.getAbsentCount()).isEqualTo(1);
        assertThat(saved.getTotalEnrolled()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AttendanceStudentRecordEntity>> recordsCaptor = ArgumentCaptor.forClass(List.class);
        verify(attendanceStudentRecordRepository).saveAll(recordsCaptor.capture());
        assertThat(recordsCaptor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("saveSectionRegister: recalculates counts when updating existing record")
    void saveSectionRegister_update_recalculatesCounts() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");

        AttendanceDailyEntity existing = dailyEntity("daily-1", false, 0, 1, 1);
        AttendanceStudentRecordEntity existingRec = studentRecord(s1, AttendanceStudentRecordEntity.AttendanceStatus.ABSENT);
        existingRec.setId("rec-1");

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.of(existing));
        when(attendanceStudentRecordRepository.findByStudent_IdAndAttendanceDateAndAcademicYear_Id(10L, DATE, AY_ID))
                .thenReturn(Optional.of(existingRec));
        when(attendanceStudentRecordRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of());

        attendanceService.saveSectionRegister(DATE, CLASS_ID, SECTION_ID,
                List.of(Map.of("studentId", 10, "status", "PRESENT", "remarks", "")), actor());

        ArgumentCaptor<AttendanceDailyEntity> captor = ArgumentCaptor.forClass(AttendanceDailyEntity.class);
        verify(attendanceDailyRepository).save(captor.capture());
        assertThat(captor.getValue().getPresentCount()).isEqualTo(1);
        assertThat(captor.getValue().getAbsentCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("saveSectionRegister: section from different school → 403 FORBIDDEN")
    void saveSectionRegister_crossSchoolSection_throwsForbidden() {
        SchoolEntity otherSchool = new SchoolEntity();
        otherSchool.setId(99L);

        SchoolSectionEntity foreignSection = new SchoolSectionEntity();
        foreignSection.setId(SECTION_ID);
        foreignSection.setName("A");
        foreignSection.setSchoolClass(schoolClass);
        foreignSection.setSchool(otherSchool);

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(foreignSection));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of());
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                attendanceService.saveSectionRegister(DATE, CLASS_ID, SECTION_ID,
                        List.of(Map.of("studentId", 10, "status", "PRESENT", "remarks", "")), actor()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("saveSectionRegister: locked attendance_daily → 403 FORBIDDEN")
    void saveSectionRegister_lockedSection_throwsForbidden() {
        AttendanceDailyEntity locked = dailyEntity("daily-1", true, 1, 0, 1);

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(student(10L, "Alice", "ADM001")));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.of(locked));

        assertThatThrownBy(() ->
                attendanceService.saveSectionRegister(DATE, CLASS_ID, SECTION_ID,
                        List.of(Map.of("studentId", 10, "status", "PRESENT", "remarks", "")), actor()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("saveSectionRegister: writes ATTENDANCE_SECTION_SAVED audit event")
    void saveSectionRegister_writesAuditEvent() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.empty());
        when(attendanceStudentRecordRepository.findByStudent_IdAndAttendanceDateAndAcademicYear_Id(10L, DATE, AY_ID))
                .thenReturn(Optional.empty());
        when(attendanceStudentRecordRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of());

        attendanceService.saveSectionRegister(DATE, CLASS_ID, SECTION_ID,
                List.of(Map.of("studentId", 10, "status", "PRESENT", "remarks", "")), actor());

        verify(auditLogService).recordEvent(
                eq("ATTENDANCE_SECTION_SAVED"),
                eq(actor().userId()),
                eq(SCHOOL_ID),
                eq("attendance_daily"),
                any(),
                any(),
                any()
        );
    }

    // ── submitSection ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("submitSection: all students recorded → locks attendance_daily")
    void submitSection_allRecorded_locks() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");

        AttendanceDailyEntity daily = dailyEntity("daily-1", false, 1, 0, 1);
        AttendanceStudentRecordEntity rec = studentRecord(s1, AttendanceStudentRecordEntity.AttendanceStatus.PRESENT);

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.of(daily));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1));
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of(rec));

        attendanceService.submitSection(DATE, CLASS_ID, SECTION_ID, actor());

        ArgumentCaptor<AttendanceDailyEntity> captor = ArgumentCaptor.forClass(AttendanceDailyEntity.class);
        verify(attendanceDailyRepository).save(captor.capture());
        assertThat(captor.getValue().isLocked()).isTrue();
    }

    @Test
    @DisplayName("submitSection: no attendance_daily row → 409 CONFLICT")
    void submitSection_noDailyRecord_throwsConflict() {
        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> attendanceService.submitSection(DATE, CLASS_ID, SECTION_ID, actor()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("submitSection: not all students have records → 409 CONFLICT")
    void submitSection_incompleteRecords_throwsConflict() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");
        StudentEntity s2 = student(20L, "Bob Sharma", "ADM002");

        AttendanceDailyEntity daily = dailyEntity("daily-1", false, 1, 0, 2);
        AttendanceStudentRecordEntity rec = studentRecord(s1, AttendanceStudentRecordEntity.AttendanceStatus.PRESENT);

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.of(daily));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1, s2));
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of(rec)); // only 1 of 2

        assertThatThrownBy(() -> attendanceService.submitSection(DATE, CLASS_ID, SECTION_ID, actor()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("submitSection: already locked → 409 CONFLICT")
    void submitSection_alreadyLocked_throwsConflict() {
        AttendanceDailyEntity locked = dailyEntity("daily-1", true, 1, 0, 1);

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> attendanceService.submitSection(DATE, CLASS_ID, SECTION_ID, actor()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("submitSection: writes ATTENDANCE_SECTION_SUBMITTED audit event")
    void submitSection_writesAuditEvent() {
        StudentEntity s1 = student(10L, "Alice Kumar", "ADM001");

        AttendanceDailyEntity daily = dailyEntity("daily-audit", false, 1, 0, 1);
        AttendanceStudentRecordEntity rec = studentRecord(s1, AttendanceStudentRecordEntity.AttendanceStatus.PRESENT);

        when(sectionRepository.findById(SECTION_ID)).thenReturn(Optional.of(section));
        when(attendanceDailyRepository.findByAttendanceDateAndSection_IdAndAcademicYear_Id(DATE, SECTION_ID, AY_ID))
                .thenReturn(Optional.of(daily));
        when(studentRepository.findBySchoolClass_IdAndSection_IdOrderByFullNameAsc(CLASS_ID, SECTION_ID))
                .thenReturn(List.of(s1));
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of(rec));

        attendanceService.submitSection(DATE, CLASS_ID, SECTION_ID, actor());

        verify(auditLogService).recordEvent(
                eq("ATTENDANCE_SECTION_SUBMITTED"),
                eq(actor().userId()),
                eq(SCHOOL_ID),
                eq("attendance_daily"),
                eq("daily-audit"),
                eq("locked=false"),
                eq("locked=true")
        );
    }

    // ── submitAttendanceDay ───────────────────────────────────────────────────

    @Test
    @DisplayName("submitAttendanceDay: section with no student records (Pending) → 409 CONFLICT")
    void submitAttendanceDay_pendingSections_throwsConflict() {
        when(sectionRepository.findBySchool_Id(SCHOOL_ID)).thenReturn(List.of(section));
        when(attendanceDailyRepository.findByAttendanceDateAndAcademicYear_Id(DATE, AY_ID))
                .thenReturn(List.of());
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of()); // empty → Pending
        when(studentRepository.countBySection_Id(SECTION_ID)).thenReturn(2L);

        assertThatThrownBy(() -> attendanceService.submitAttendanceDay(DATE.toString(), actor()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("submitAttendanceDay: all sections Saved → locks them and writes audit")
    void submitAttendanceDay_allSaved_locksAndAudits() {
        StudentEntity s1 = student(10L, "Alice", "ADM001");
        AttendanceStudentRecordEntity rec = studentRecord(s1, AttendanceStudentRecordEntity.AttendanceStatus.PRESENT);

        AttendanceDailyEntity daily = dailyEntity("daily-1", false, 1, 0, 1);
        daily.setSection(section);

        when(sectionRepository.findBySchool_Id(SCHOOL_ID)).thenReturn(List.of(section));
        when(attendanceDailyRepository.findByAttendanceDateAndAcademicYear_Id(DATE, AY_ID))
                .thenReturn(List.of(daily));
        when(attendanceStudentRecordRepository.findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(SECTION_ID, DATE))
                .thenReturn(List.of(rec)); // has records → Saved
        when(studentRepository.countBySection_Id(SECTION_ID)).thenReturn(1L);

        Map<String, Object> result = attendanceService.submitAttendanceDay(DATE.toString(), actor());

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("submitted")).isEqualTo(1);

        verify(auditLogService).recordEvent(
                eq("ATTENDANCE_DAY_SUBMITTED"),
                eq(actor().userId()),
                eq(SCHOOL_ID),
                eq("attendance_day"),
                any(),
                any(),
                any()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private StudentEntity student(Long id, String name, String admissionNo) {
        StudentEntity s = new StudentEntity();
        s.setId(id);
        s.setFullName(name);
        s.setAdmissionNo(admissionNo);
        return s;
    }

    private AttendanceStudentRecordEntity studentRecord(StudentEntity student,
                                                         AttendanceStudentRecordEntity.AttendanceStatus status) {
        AttendanceStudentRecordEntity rec = new AttendanceStudentRecordEntity();
        rec.setStudent(student);
        rec.setStatus(status);
        rec.setRemarks("");
        return rec;
    }

    private AttendanceDailyEntity dailyEntity(String id, boolean locked,
                                               int presentCount, int absentCount, int totalEnrolled) {
        AttendanceDailyEntity e = new AttendanceDailyEntity();
        e.setId(id);
        e.setLocked(locked);
        e.setPresentCount(presentCount);
        e.setAbsentCount(absentCount);
        e.setTotalEnrolled(totalEnrolled);
        return e;
    }

    private AuthUser actor() {
        return AuthUser.identity(1L, "Test Actor", "actor@test.com", "ADMIN", null, null);
    }
}
