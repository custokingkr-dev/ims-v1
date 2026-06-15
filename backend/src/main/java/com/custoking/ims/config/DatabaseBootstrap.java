package com.custoking.ims.config;

import com.custoking.ims.commandcenter.*;
import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
import com.custoking.ims.service.RbacService;
import com.custoking.ims.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Configuration
public class DatabaseBootstrap {

    private final PasswordUtil passwordUtil;
    private final RbacService rbacService;

    public DatabaseBootstrap(PasswordUtil passwordUtil, @Lazy RbacService rbacService) {
        this.passwordUtil = passwordUtil;
        this.rbacService = rbacService;
    }

    @Bean
    CommandLineRunner bootstrapData(
            @Value("${app.bootstrap-users:true}") boolean bootstrap,
            @Value("${SUPERADMIN_EMAIL:superadmin@custoking.com}") String superAdminEmail,
            @Value("${SUPERADMIN_PASSWORD:#{null}}") String superAdminPassword,
            @Value("${DEMO_ADMIN_PASSWORD:#{null}}") String demoAdminPassword,
            AppUserRepository userRepository,
            SchoolRepository schoolRepository,
            AcademicYearRepository academicYearRepository,
            SchoolClassRepository classRepository,
            SchoolSectionRepository sectionRepository,
            StudentRepository studentRepository,
            FeeBandRepository feeBandRepository,
            FeeItemRepository feeItemRepository,
            FeeAssignmentRepository feeAssignmentRepository,
            PaymentRecordRepository paymentRecordRepository,
            AttendanceDailyRepository attendanceDailyRepository,
            StaffMemberRepository staffMemberRepository,
            CatalogOrderRepository catalogOrderRepository,
            AnnualPlanItemRepository annualPlanItemRepository,
            FirefightingRequestRepository firefightingRequestRepository,
            FirefightingQuotationRepository firefightingQuotationRepository,
            CommandCenterActionRepository commandCenterActionRepository,
            CommandCenterFeedRepository commandCenterFeedRepository,
            NotificationBroadcastRepository notificationBroadcastRepository,
            NotificationDeliveryLogRepository notificationDeliveryLogRepository,
            AcademicEventRepository academicEventRepository,
            EventStudentContributionRepository eventContributionRepository,
            TransactionTemplate transactionTemplate
    ) {
        return args -> transactionTemplate.executeWithoutResult(status -> {
            if (!bootstrap) return;

            SchoolEntity demoSchool = schoolRepository.findByShortCodeIgnoreCase("DEMO").orElseGet(() -> {
                SchoolEntity s = new SchoolEntity();
                s.setName("Custoking Demo School");
                s.setShortCode("DEMO");
                s.setCity("Hyderabad");
                s.setState("Telangana");
                s.setActive(true);
                return schoolRepository.save(s);
            });

            if (academicYearRepository.count() == 0) {
                AcademicYearEntity year = new AcademicYearEntity();
                year.setId("ay_2025_26");
                year.setLabel("2025–26");
                year.setActive(true);
                academicYearRepository.save(year);
            }
            AcademicYearEntity year = academicYearRepository.findFirstByActiveTrue().orElseThrow();

            // ApplicationSecurityValidator has already enforced that superAdminPassword
            // is non-null and not a known weak value before this runner executes.
            if (userRepository.findByEmailIgnoreCase(superAdminEmail).isEmpty()) {
                createUser(userRepository, "Super Admin", superAdminEmail, superAdminPassword, "SUPERADMIN", null, null);
            }

            // Demo school admin — only created when DEMO_ADMIN_PASSWORD is explicitly set.
            // Omitting the env var skips creation; the validator ensures it is not weak if set.
            if (demoAdminPassword != null
                    && userRepository.findByEmailIgnoreCase("admin@demo.custoking.com").isEmpty()) {
                createUser(userRepository, "Demo Admin", "admin@demo.custoking.com", demoAdminPassword, "ADMIN", demoSchool.getId(), demoSchool.getName());
            }

            SchoolEntity greenwoodSchool = schoolRepository.findByShortCodeIgnoreCase("GRWD").orElseGet(() -> {
                SchoolEntity s = new SchoolEntity();
                s.setName("Greenwood Academy");
                s.setShortCode("GRWD");
                s.setCity("Bengaluru");
                s.setState("Karnataka");
                s.setActive(true);
                return schoolRepository.save(s);
            });

            SchoolEntity sunriseSchool = schoolRepository.findByShortCodeIgnoreCase("SUNRISE").orElseGet(() -> {
                SchoolEntity s = new SchoolEntity();
                s.setName("Sunrise International School");
                s.setShortCode("SUNRISE");
                s.setCity("Mumbai");
                s.setState("Maharashtra");
                s.setActive(true);
                return schoolRepository.save(s);
            });

            if (demoAdminPassword != null
                    && userRepository.findByEmailIgnoreCase("admin@greenwood.custoking.com").isEmpty()) {
                createUser(userRepository, "Greenwood Admin", "admin@greenwood.custoking.com", demoAdminPassword, "ADMIN", greenwoodSchool.getId(), greenwoodSchool.getName());
            }

            if (demoAdminPassword != null
                    && userRepository.findByEmailIgnoreCase("admin@sunrise.custoking.com").isEmpty()) {
                createUser(userRepository, "Sunrise Admin", "admin@sunrise.custoking.com", demoAdminPassword, "ADMIN", sunriseSchool.getId(), sunriseSchool.getName());
            }

            if (classRepository.count() == 0) {
                for (int i = 1; i <= 12; i++) {
                    SchoolClassEntity schoolClass = new SchoolClassEntity();
                    schoolClass.setId(String.valueOf(i));
                    schoolClass.setName("Class " + i);
                    schoolClass.setSortOrder(i);
                    classRepository.save(schoolClass);
                }
            }

            if (sectionRepository.count() == 0) {
                for (int i = 1; i <= 12; i++) {
                    SchoolClassEntity schoolClass = classRepository.findById(String.valueOf(i)).orElseThrow();
                    for (String sec : List.of("A", "B")) {
                        SchoolSectionEntity section = new SchoolSectionEntity();
                        section.setId(i + sec);
                        section.setSchoolClass(schoolClass);
                        section.setSchool(demoSchool);
                        section.setName(sec);
                        section.setTeacherName((char)('A' + i - 1) + ". Menon");
                        section.setActive(true);
                        sectionRepository.save(section);
                    }
                }
            }

            if (feeBandRepository.count() == 0) {
                createBand(feeBandRepository, feeItemRepository, year, "band-1-5", "Class 1–5", 1, 5, 8, List.of("Monthly","Quarterly","Annual"), List.of(item("Tuition fee", "Annual", 3600000L), item("Lab fee", "Annual", 400000L), item("Activity & sports", "Annual", 200000L)));
                createBand(feeBandRepository, feeItemRepository, year, "band-6-8", "Class 6–8", 6, 8, 6, List.of("Monthly","Quarterly","Half-yearly","Annual"), List.of(item("Tuition fee", "Annual", 4000000L), item("Lab fee", "Annual", 500000L), item("Technology fee", "Annual", 250000L)));
                createBand(feeBandRepository, feeItemRepository, year, "band-9-10", "Class 9–10", 9, 10, 5, List.of("Monthly","Quarterly","Half-yearly","Annual"), List.of(item("Tuition fee", "Annual", 4200000L), item("Exam fee", "Annual", 300000L), item("Activity & sports", "Annual", 300000L)));
                createBand(feeBandRepository, feeItemRepository, year, "band-11-12", "Class 11–12", 11, 12, 4, List.of("Quarterly","Half-yearly","Annual"), List.of(item("Tuition fee", "Annual", 4600000L), item("Lab fee", "Annual", 600000L), item("Board prep", "Annual", 400000L)));
            }

            if (studentRepository.count() == 0) seedStudents(studentRepository, classRepository, sectionRepository, year);

            if (feeAssignmentRepository.count() == 0) {
                for (StudentEntity s : studentRepository.findAll()) {
                    FeeBandEntity band = feeBandRepository.findFirstByAcademicYear_IdAndClassFromLessThanEqualAndClassToGreaterThanEqual(year.getId(), s.getSchoolClass().getSortOrder(), s.getSchoolClass().getSortOrder()).orElseThrow();
                    FeeAssignmentEntity a = new FeeAssignmentEntity();
                    a.setId(UUID.randomUUID().toString());
                    a.setStudent(s);
                    a.setBand(band);
                    a.setAcademicYear(year);
                    a.setSchedule(s.getSchoolClass().getSortOrder() >= 9 ? "Quarterly" : "Monthly");
                    a.setBandDiscount(band.getDiscount());
                    a.setManualDiscount(0);
                    a.setSurcharge(2);
                    long total = feeItemRepository.findByBand_IdOrderByCreatedAtAsc(band.getId()).stream().mapToLong(FeeItemEntity::getAmount).sum();
                    a.setNetPayable(total - Math.round(total * band.getDiscount() / 100.0));
                    a.setPaidAmount(s.getId() % 3 == 0 ? total / 2 : total);
                    a.setAssignedBy(1L);
                    a.setAssignedAt(OffsetDateTime.now().minusDays(10));
                    a.setUpdatedBy(1L);
                    a.setUpdatedAt(OffsetDateTime.now().minusDays(3));
                    feeAssignmentRepository.save(a);
                }
            }

            if (paymentRecordRepository.count() == 0) {
                feeAssignmentRepository.findAll().forEach(a -> {
                    if (a.getPaidAmount() <= 0) return;
                    PaymentRecordEntity p = new PaymentRecordEntity();
                    p.setId(UUID.randomUUID().toString());
                    p.setStudent(a.getStudent());
                    p.setAssignment(a);
                    p.setAmount(a.getPaidAmount());
                    p.setMode("UPI");
                    p.setNotes("Seed payment");
                    p.setPaidAt(OffsetDateTime.now().minusDays(2));
                    p.setRecordedBy(1L);
                    p.setReceiptNumber("RCPT-" + a.getStudent().getId());
                    paymentRecordRepository.save(p);
                });
            }

            if (attendanceDailyRepository.count() == 0) {
                LocalDate today = LocalDate.now();
                sectionRepository.findAll().stream().limit(6).forEach(section -> {
                    int total = (int) studentRepository.countBySection_Id(section.getId());
                    if (total == 0) return;
                    AttendanceDailyEntity attendance = new AttendanceDailyEntity();
                    attendance.setId(UUID.randomUUID().toString());
                    attendance.setAttendanceDate(today);
                    attendance.setSchoolClass(section.getSchoolClass());
                    attendance.setSection(section);
                    attendance.setAcademicYear(year);
                    attendance.setTotalEnrolled(total);
                    attendance.setPresentCount(Math.max(0, total - 1));
                    attendance.setAbsentCount(total - attendance.getPresentCount());
                    attendance.setRecordedBy(1L);
                    attendance.setRecordedAt(OffsetDateTime.now().minusHours(2));
                    attendance.setUpdatedBy(1L);
                    attendance.setUpdatedAt(OffsetDateTime.now().minusHours(2));
                    attendance.setLocked(false);
                    attendanceDailyRepository.save(attendance);
                });
            }

            if (staffMemberRepository.count() == 0) {
                staff("Priya Sharma", "Teacher", "Mathematics", 5200000L, "Processed", demoSchool, staffMemberRepository);
                staff("Arun Menon", "Teacher", "Science", 5000000L, "Pending", demoSchool, staffMemberRepository);
                staff("Sailaja Rao", "Admin", "Operations", 3600000L, "Processed", demoSchool, staffMemberRepository);
            }

            if (catalogOrderRepository.count() == 0) seedCatalogOrders(catalogOrderRepository, demoSchool);
            if (annualPlanItemRepository.count() == 0) seedAnnualPlan(annualPlanItemRepository, demoSchool, year);
            if (firefightingRequestRepository.count() == 0) seedFirefighting(firefightingRequestRepository, firefightingQuotationRepository, demoSchool);

            // ── Greenwood Academy sections ────────────────────────────────────────
            if (sectionRepository.findBySchool_Id(greenwoodSchool.getId()).isEmpty()) {
                for (int i = 1; i <= 8; i++) {
                    SchoolClassEntity schoolClass = classRepository.findById(String.valueOf(i)).orElseThrow();
                    for (String sec : List.of("A", "B")) {
                        SchoolSectionEntity section = new SchoolSectionEntity();
                        section.setId("grwd-" + i + sec);
                        section.setSchoolClass(schoolClass);
                        section.setSchool(greenwoodSchool);
                        section.setName(sec);
                        section.setTeacherName((char)('A' + i - 1) + ". Kumar");
                        section.setActive(true);
                        sectionRepository.save(section);
                    }
                }
            }

            // ── Sunrise International sections ────────────────────────────────────
            if (sectionRepository.findBySchool_Id(sunriseSchool.getId()).isEmpty()) {
                for (int i = 1; i <= 10; i++) {
                    SchoolClassEntity schoolClass = classRepository.findById(String.valueOf(i)).orElseThrow();
                    for (String sec : List.of("A", "B")) {
                        SchoolSectionEntity section = new SchoolSectionEntity();
                        section.setId("sunrise-" + i + sec);
                        section.setSchoolClass(schoolClass);
                        section.setSchool(sunriseSchool);
                        section.setName(sec);
                        section.setTeacherName((char)('A' + i - 1) + ". Iyer");
                        section.setActive(true);
                        sectionRepository.save(section);
                    }
                }
            }

            // ── Greenwood Academy students ─────────────────────────────────────────
            if (studentRepository.countBySchool_Id(greenwoodSchool.getId()) == 0) {
                long serial = 2001;
                for (int klass = 1; klass <= 8; klass++) {
                    for (String sec : List.of("A", "B")) {
                        SchoolClassEntity schoolClass = classRepository.findById(String.valueOf(klass)).orElseThrow();
                        SchoolSectionEntity section = sectionRepository.findById("grwd-" + klass + sec).orElseThrow();
                        for (int i = 0; i < 3; i++) {
                            StudentEntity s = new StudentEntity();
                            s.setAdmissionNo("GRWD-" + serial);
                            s.setRollNo(String.valueOf(i + 1));
                            s.setBoardRegNo("GRN" + serial);
                            s.setFullName("Student" + serial + " " + sec);
                            s.setDob(LocalDate.of(2010 + Math.min(klass, 5), Math.max(1, (i % 12) + 1), 10 + i));
                            s.setGender(i % 2 == 0 ? "Male" : "Female");
                            s.setFatherName("Parent " + serial);
                            s.setFatherContact("98765" + String.format("%05d", serial % 100000));
                            s.setMotherName("Mother " + serial);
                            s.setPhone(s.getFatherContact());
                            s.setCity("Bengaluru");
                            s.setState("Karnataka");
                            s.setPinCode("560001");
                            s.setAddress("H-" + serial + ", MG Road, Bengaluru, Karnataka 560001");
                            s.setSchool(section.getSchool());
                            s.setSchoolClass(schoolClass);
                            s.setSection(section);
                            s.setAcademicYear(year);
                            s.setFeeStatus(i == 0 ? "Overdue" : "Paid");
                            s.setAttendancePercent(85.0 + (i * 3));
                            studentRepository.save(s);
                            serial++;
                        }
                    }
                }
            }

            // ── Sunrise International students ─────────────────────────────────────
            if (studentRepository.countBySchool_Id(sunriseSchool.getId()) == 0) {
                long serial = 3001;
                for (int klass = 1; klass <= 10; klass++) {
                    for (String sec : List.of("A", "B")) {
                        SchoolClassEntity schoolClass = classRepository.findById(String.valueOf(klass)).orElseThrow();
                        SchoolSectionEntity section = sectionRepository.findById("sunrise-" + klass + sec).orElseThrow();
                        for (int i = 0; i < 3; i++) {
                            StudentEntity s = new StudentEntity();
                            s.setAdmissionNo("SRS-" + serial);
                            s.setRollNo(String.valueOf(i + 1));
                            s.setBoardRegNo("SRS" + serial);
                            s.setFullName("Student" + serial + " " + sec);
                            s.setDob(LocalDate.of(2010 + Math.min(klass, 5), Math.max(1, (i % 12) + 1), 10 + i));
                            s.setGender(i % 2 == 0 ? "Male" : "Female");
                            s.setFatherName("Parent " + serial);
                            s.setFatherContact("98765" + String.format("%05d", serial % 100000));
                            s.setMotherName("Mother " + serial);
                            s.setPhone(s.getFatherContact());
                            s.setCity("Mumbai");
                            s.setState("Maharashtra");
                            s.setPinCode("400001");
                            s.setAddress("H-" + serial + ", Marine Drive, Mumbai, Maharashtra 400001");
                            s.setSchool(section.getSchool());
                            s.setSchoolClass(schoolClass);
                            s.setSection(section);
                            s.setAcademicYear(year);
                            s.setFeeStatus(i == 0 ? "Overdue" : "Paid");
                            s.setAttendancePercent(87.0 + (i * 2));
                            studentRepository.save(s);
                            serial++;
                        }
                    }
                }
            }

            // ── Greenwood Academy FF requests ──────────────────────────────────────
            if (firefightingRequestRepository.findBySchool_Id(greenwoodSchool.getId()).isEmpty()) {
                fire(firefightingRequestRepository, greenwoodSchool, "FF-GRWD-001", "Library book shelves", "Furniture", "AWAITING_BURSAR", 1800000L, null, null, OffsetDateTime.now().minusDays(7));
                fire(firefightingRequestRepository, greenwoodSchool, "FF-GRWD-002", "CCTV camera upgrade", "Electronics & security", "DRAFT", 2500000L, null, null, OffsetDateTime.now().minusDays(2));
            }

            // ── Sunrise International FF requests ──────────────────────────────────
            if (firefightingRequestRepository.findBySchool_Id(sunriseSchool.getId()).isEmpty()) {
                fire(firefightingRequestRepository, sunriseSchool, "FF-SRS-001", "Auditorium sound system", "Events & occasions", "FULFILLED", 4200000L, "Custoking", 3980000L, OffsetDateTime.now().minusDays(30));
                fire(firefightingRequestRepository, sunriseSchool, "FF-SRS-002", "Cafeteria tables", "Furniture", "AWAITING_PRINCIPAL", 1500000L, null, null, OffsetDateTime.now().minusDays(5));
            }

            // ── Command Center seed (actions, feed, broadcasts) ─────────────────
            if (commandCenterFeedRepository.countBySchoolId(demoSchool.getId()) == 0) {
                seedCommandCenter(commandCenterActionRepository, commandCenterFeedRepository, notificationBroadcastRepository, notificationDeliveryLogRepository, demoSchool);
            }
            if (commandCenterFeedRepository.countBySchoolId(greenwoodSchool.getId()) == 0) {
                seedCommandCenter(commandCenterActionRepository, commandCenterFeedRepository, notificationBroadcastRepository, notificationDeliveryLogRepository, greenwoodSchool);
            }
            if (commandCenterFeedRepository.countBySchoolId(sunriseSchool.getId()) == 0) {
                seedCommandCenter(commandCenterActionRepository, commandCenterFeedRepository, notificationBroadcastRepository, notificationDeliveryLogRepository, sunriseSchool);
            }

            // ── Class Photography events ───────────────────────────────────────────
            seedPhotographyEvent(academicEventRepository, eventContributionRepository,
                    studentRepository, demoSchool, year, "evt-photo-demo", "DEMO");
            seedPhotographyEvent(academicEventRepository, eventContributionRepository,
                    studentRepository, greenwoodSchool, year, "evt-photo-grwd", "GRWD");
            seedPhotographyEvent(academicEventRepository, eventContributionRepository,
                    studentRepository, sunriseSchool, year, "evt-photo-srs", "SRS");
        });
    }

    private void createUser(AppUserRepository repo, String fullName, String email, String password, String role, Long branchId, String branchName) {
        AppUserEntity user = new AppUserEntity();
        user.setFullName(fullName);
        user.setEmail(email);
        // BCrypt hash — on first run after V99 migration, seed users are created fresh with BCrypt.
        // Existing rows with old SHA-256 hashes must be deleted or reset by an operator.
        user.setPasswordHash(passwordUtil.hash(password));
        user.setRole(role);
        user.setBranchId(branchId);
        user.setBranchName(branchName);
        repo.save(user);
        // Use explicitly-scoped assignment: platform-wide for SUPERADMIN, school-scoped otherwise.
        if (branchId == null) {
            rbacService.assignPlatformRole(user.getId(), role, null);
        } else {
            rbacService.assignSchoolRole(user.getId(), role, branchId, null);
        }
    }

    private void seedStudents(StudentRepository repo, SchoolClassRepository classRepo, SchoolSectionRepository sectionRepo, AcademicYearEntity year) {
        long serial = 1001;
        for (int klass = 1; klass <= 10; klass++) {
            for (String sec : List.of("A", "B")) {
                SchoolClassEntity schoolClass = classRepo.findById(String.valueOf(klass)).orElseThrow();
                SchoolSectionEntity section = sectionRepo.findById(klass + sec).orElseThrow();
                for (int i = 0; i < 4; i++) {
                    StudentEntity s = new StudentEntity();
                    s.setAdmissionNo("ADM-" + serial);
                    s.setRollNo(String.valueOf(i + 1));
                    s.setBoardRegNo("BRN" + serial);
                    s.setFullName("Student" + serial + " " + sec);
                    s.setDob(LocalDate.of(2010 + Math.min(klass, 5), Math.max(1, (i % 12) + 1), 10 + i));
                    s.setGender(i % 2 == 0 ? "Male" : "Female");
                    s.setFatherName("Parent " + serial);
                    s.setFatherContact("98765" + String.format("%05d", serial % 100000));
                    s.setMotherName("Mother " + serial);
                    s.setPhone(s.getFatherContact());
                    s.setHouseNumber("H-" + (10 + i));
                    s.setStreet("Main Road");
                    s.setLocality("Miyapur");
                    s.setCity("Hyderabad");
                    s.setState("Telangana");
                    s.setPinCode("500049");
                    s.setAddress("H-" + (10 + i) + ", Main Road, Miyapur, Hyderabad, Telangana 500049");
                    s.setSchool(section.getSchool());
                    s.setSchoolClass(schoolClass);
                    s.setSection(section);
                    s.setAcademicYear(year);
                    s.setFeeStatus(i == 0 ? "Overdue" : "Paid");
                    s.setAttendancePercent(88.0 + (i * 2));
                    repo.save(s);
                    serial++;
                }
            }
        }
    }

    private record FeeItemSeed(String name, String frequency, long amount) {}
    private FeeItemSeed item(String name, String frequency, long amount) { return new FeeItemSeed(name, frequency, amount); }
    private void createBand(FeeBandRepository bands, FeeItemRepository items, AcademicYearEntity year, String id, String name, int from, int to, double discount, List<String> schedules, List<FeeItemSeed> seededItems) {
        FeeBandEntity band = new FeeBandEntity();
        band.setId(id); band.setName(name); band.setClassFrom(from); band.setClassTo(to); band.setDiscount(discount); band.setActiveSchedulesCsv(String.join(",", schedules)); band.setAcademicYear(year); bands.save(band);
        for (FeeItemSeed seed : seededItems) {
            FeeItemEntity item = new FeeItemEntity(); item.setId(UUID.randomUUID().toString()); item.setBand(band); item.setName(seed.name()); item.setFrequency(seed.frequency()); item.setAmount(seed.amount()); items.save(item);
        }
    }

    private void staff(String n, String d, String dept, long salary, String status, SchoolEntity school, StaffMemberRepository repo){ StaffMemberEntity e=new StaffMemberEntity(); e.setName(n); e.setDesignation(d); e.setDepartment(dept); e.setMonthlySalary(salary); e.setPayrollStatus(status); e.setSchool(school); repo.save(e);}    

    private void seedCatalogOrders(CatalogOrderRepository repo, SchoolEntity school) {
        order(repo, school, "CK-1082", "UNIFORMS", "Class 6–8 Uniforms", "340 units", 10200000L, 510000L, "DELIVERED", "3–4 weeks", LocalDate.of(2026,1,25));
        order(repo, school, "CK-1055", "NOTEBOOKS", "A4 ruled notebooks", "1200 units", 5400000L, 648000L, "IN_TRANSIT", "1–2 weeks", LocalDate.of(2026,1,15));
        order(repo, school, "CK-0990", "HOUSEKEEPING", "Weekly housekeeping contract", "₹18,000/mo", 1800000L, 0L, "ACTIVE", "Service active", LocalDate.of(2024,8,1));
        order(repo, school, "CK-1060", "IDCARDS", "Student + Staff ID cards", "890 units", 2670000L, 480600L, "AWAITING_APPROVAL", "10 days", LocalDate.of(2026,1,2));
        order(repo, school, "CK-1048", "STATIONERY", "Stationery kits", "487 students", 2290800L, 0L, "DELIVERED", "Delivered", LocalDate.of(2025,12,15));
        order(repo, school, "CK-1031", "EVENTS", "Annual day trophies & certificates", "220 items", 1660000L, 0L, "DELIVERED", "Delivered", LocalDate.of(2025,11,28));
    }
    private void order(CatalogOrderRepository repo, SchoolEntity school, String id, String category, String title, String items, long subtotal, long gst, String status, String est, LocalDate placedDate){ CatalogOrderEntity e = new CatalogOrderEntity(); e.setId(id); e.setSchool(school); e.setCategory(category); e.setOrderData("{\"title\":\""+title+"\",\"items\":\""+items+"\"}"); e.setSubtotal(subtotal); e.setGst(gst); e.setTotalAmount(subtotal+gst); e.setStatus(status); e.setEstimatedDelivery(est); e.setPlacedBy(2L); e.setPlacedAt(placedDate.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)); e.setCreatedAt(placedDate.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)); repo.save(e);}    

    private void seedAnnualPlan(AnnualPlanItemRepository repo, SchoolEntity school, AcademicYearEntity year) {
        plan(repo, school, year, "Term 1", "UNIFORMS", "Uniforms for new academic year", "340 sets", 10200000L, "ORDERED");
        plan(repo, school, year, "Term 1", "NOTEBOOKS", "A4 ruled notebooks", "1200 books", 6048000L, "ORDERED");
        plan(repo, school, year, "Term 1", "IDCARDS", "Student + Staff ID cards", "890 cards", 3150600L, "PLANNED");
        plan(repo, school, year, "Term 1", "STATIONERY", "Stationery kits", "487 kits", 2290800L, "ORDERED");
        plan(repo, school, year, "Term 2", "HOUSEKEEPING", "Weekly housekeeping contract", "4 months", 7200000L, "ACTIVE");
        plan(repo, school, year, "Term 2", "HEALTH", "Annual health check camp", "1 service", 1500000L, "PLANNED");
        plan(repo, school, year, "Term 3", "EVENTS", "Annual Day print and event kit", "1 lot", 1660000L, "PLANNED");
    }
    private void plan(AnnualPlanItemRepository repo, SchoolEntity school, AcademicYearEntity year, String term, String category, String desc, String qty, long amt, String status){ AnnualPlanItemEntity e = new AnnualPlanItemEntity(); e.setId(UUID.randomUUID().toString()); e.setSchool(school); e.setAcademicYear(year); e.setTermName(term); e.setCategory(category); e.setDescription(desc); e.setQuantity(qty); e.setEstimatedAmount(amt); e.setStatus(status); repo.save(e);}    

    private void seedFirefighting(FirefightingRequestRepository repo, FirefightingQuotationRepository qrepo, SchoolEntity school) {
        fire(repo, school, "FF-003", "PA system replacement", "Events & occasions", "FULFILLED", 2400000L, "Custoking", 2280000L, OffsetDateTime.now().minusDays(50));
        fire(repo, school, "FF-004", "Chemistry lab stools", "Lab equipment", "FULFILLED", 1850000L, "Custoking", 1760000L, OffsetDateTime.now().minusDays(35));
        fire(repo, school, "FF-005", "Sports turf repair", "Sports & playground", "FULFILLED", 5200000L, "TurfPro", 5050000L, OffsetDateTime.now().minusDays(20));
        fire(repo, school, "FF-006", "House stage lighting", "Events & occasions", "APPROVED", 2100000L, "Custoking", 1980000L, OffsetDateTime.now().minusDays(10));
        FirefightingRequestEntity ff7 = fire(repo, school, "FF-007", "Housekeeping deep clean AMC", "Services & AMC", "AWAITING_PRINCIPAL", 1900000L, null, null, OffsetDateTime.now().minusDays(5));
        quote(qrepo, ff7, "Custoking", 1750000L, "5 days", true, true, "Full consumables included");
        quote(qrepo, ff7, "CleanEdge", 1820000L, "6 days", false, false, "Standard package");
        quote(qrepo, ff7, "Spark Services", 1890000L, "4 days", false, false, "Fastest option");
        ff7.setBursarNote("Custoking is lower and includes GST invoice support"); repo.save(ff7);
        FirefightingRequestEntity ff8 = fire(repo, school, "FF-008", "Infirmary beds and divider", "Health", "AWAITING_PRINCIPAL", 2600000L, null, null, OffsetDateTime.now().minusDays(3));
        quote(qrepo, ff8, "Custoking", 2480000L, "7 days", true, true, "Beds plus privacy divider");
        quote(qrepo, ff8, "MediSupply", 2550000L, "8 days", false, false, "Beds only");
        fire(repo, school, "FF-009", "New projector screens", "Electronics & security", "DRAFT", 1400000L, null, null, OffsetDateTime.now().minusDays(1));
    }
    private FirefightingRequestEntity fire(FirefightingRequestRepository repo, SchoolEntity school, String code, String title, String category, String status, long budget, String winner, Long amount, OffsetDateTime createdAt){ FirefightingRequestEntity e = new FirefightingRequestEntity(); e.setCode(code); e.setSchool(school); e.setTitle(title); e.setCategory(category); e.setUrgency("MEDIUM"); e.setRequiredByDate(createdAt.toLocalDate().plusDays(14)); e.setEstimatedBudget(budget); e.setDescription(title + " required urgently for school operations"); e.setRaisedBy(2L); e.setStatus(status); e.setWinnerVendor(winner); e.setWinnerAmount(amount); e.setCreatedAt(createdAt); if (List.of("APPROVED","FULFILLED").contains(status)) { e.setBursarApprovedAt(createdAt.plusDays(1)); e.setPrincipalApprovedAt(createdAt.plusDays(2)); } repo.save(e); return e; }
    private void quote(FirefightingQuotationRepository repo, FirefightingRequestEntity request, String vendor, long amount, String timeline, boolean ck, boolean rec, String notes){ FirefightingQuotationEntity q = new FirefightingQuotationEntity(); q.setId(UUID.randomUUID().toString()); q.setRequest(request); q.setVendorName(vendor); q.setAmount(amount); q.setDeliveryTimeline(timeline); q.setCustoking(ck); q.setRecommended(rec); q.setNotes(notes); repo.save(q);}

    // ── Command Center helpers ────────────────────────────────────────────────

    private void seedCommandCenter(
            CommandCenterActionRepository actionRepo,
            CommandCenterFeedRepository feedRepo,
            NotificationBroadcastRepository broadcastRepo,
            NotificationDeliveryLogRepository deliveryRepo,
            SchoolEntity school) {

        OffsetDateTime now = OffsetDateTime.now();

        // 12 actions ordered by urgency (newer createdAt → comes first via DESC sort)
        ccAction(actionRepo, school, "fees",         "CRITICAL", 94, "₹4.2L fee collection overdue — 32 students",       "Term 2 deadline passed 3 days ago",                          "₹4.2L at risk · late-fee waiver window closing",     "OVERDUE",           "COLLECTED",       "Send Reminders",      now);
        ccAction(actionRepo, school, "firefighting", "HIGH",     91, "Infirmary procurement approval overdue 3 days",      "Principal sign-off required for beds and privacy dividers",  "₹2.6L · health & safety compliance gap",             "AWAITING_PRINCIPAL","APPROVED",        "Review & Approve",    now.minusMinutes(2));
        ccAction(actionRepo, school, "supply",       "HIGH",     88, "ID card order CK-1060 awaiting approval",            "890-unit batch — 10-day delivery SLA starts on approval",    "₹2.7L · delivery deadline at risk",                  "AWAITING_APPROVAL", "APPROVED",        "Approve Order",       now.minusMinutes(4));
        ccAction(actionRepo, school, "fees",         "HIGH",     85, "₹1.8L scholarship disbursement overdue",             "Merit scholarships for 12 students pending bank transfer",   "₹1.8L · student satisfaction risk",                  "PENDING",           "DISBURSED",       "Initiate Transfer",   now.minusMinutes(6));
        ccAction(actionRepo, school, "attendance",   "MEDIUM",   82, "Grade 11 attendance below 88% for 2 weeks",          "Consecutive weeks below board-mandated threshold",           "Parent notifications required · board report risk",   "85%",               "≥88%",            "Notify Parents",      now.minusMinutes(10));
        ccAction(actionRepo, school, "firefighting", "MEDIUM",   79, "FF-007 quotation comparison ready",                  "3 vendor quotes in for deep-clean AMC",                      "Best option saves ₹1.4L vs highest quote",           "3 QUOTES IN",       "VENDOR SELECTED", "View Quotes",         now.minusMinutes(12));
        ccAction(actionRepo, school, "students",     "MEDIUM",   77, "46 students missing profile photos",                 "Digital ID cards require all student photos",                "ID card batch delayed · compliance gap",              "PENDING_PHOTO",     "COMPLETE",        "Upload Photos",       now.minusMinutes(14));
        ccAction(actionRepo, school, "fees",         "MEDIUM",   75, "3 fee concession applications pending review",       "Mid-term concession requests need finance approval",         "₹84,000 · awaiting decision",                        "PENDING_REVIEW",    "APPROVED",        "Review Applications", now.minusMinutes(16));
        ccAction(actionRepo, school, "supply",       "MEDIUM",   72, "Stationery kits approaching reorder threshold",      "18 kits remaining — new batch needed before term end",       "Reorder window: 2 weeks remaining",                   "LOW_STOCK",         "REORDERED",       "Place Order",         now.minusMinutes(18));
        ccAction(actionRepo, school, "students",     "LOW",      68, "Section re-assignment review due",                   "15 students flagged for section change this term",           "Academic performance improvement opportunity",         "REVIEW_PENDING",    "SECTIONS_ASSIGNED","Review Cases",       now.minusMinutes(22));
        ccAction(actionRepo, school, "attendance",   "LOW",      65, "Monthly attendance report ready to export",          "October report generated — submit to principal by month end","Board compliance — submit by 31st",                   "GENERATED",         "SUBMITTED",       "Export Report",       now.minusMinutes(24));
        ccAction(actionRepo, school, "supply",       "LOW",      62, "Uniform contract renewal due in 30 days",            "Current vendor contract expiring — 340 students affected",   "Renewal required before end of month",                "EXPIRING_SOON",     "RENEWED",         "Initiate Renewal",    now.minusMinutes(26));

        // 20 feed items with staggered timestamps
        ccFeed(feedRepo, school, "firefighting", "QUOTATION_RECEIVED",  "FF-009 quotation received from 3 vendors — SLA 38 min", "info",    now.minusMinutes(2));
        ccFeed(feedRepo, school, "fees",         "PAYMENT_BATCH",       "₹4.2L collected · 18 UPI auto-debits processed",        "success", now.minusMinutes(7));
        ccFeed(feedRepo, school, "supply",       "ORDER_SUBMITTED",     "ORD-CK-1060 ID card order submitted for approval",      "info",    now.minusMinutes(12));
        ccFeed(feedRepo, school, "fees",         "REMINDER_SENT",       "32 payment reminders delivered · 14 parents opened",    "info",    now.minusMinutes(18));
        ccFeed(feedRepo, school, "attendance",   "ALERT",               "Grade 11 attendance dipped below 88%",                  "warning", now.minusMinutes(25));
        ccFeed(feedRepo, school, "students",     "BATCH_CLEARED",       "46 students cleared section gate review",               "success", now.minusMinutes(33));
        ccFeed(feedRepo, school, "supply",       "QUOTE_RECEIVED",      "Vendor quote received for lab consumables",             "info",    now.minusMinutes(41));
        ccFeed(feedRepo, school, "fees",         "COLLECTION",          "₹1.8L collected · 7 fresh UPI debits",                 "success", now.minusHours(1).minusMinutes(5));
        ccFeed(feedRepo, school, "attendance",   "ATTENDANCE_MARKED",   "Section 8-B attendance marked · 2 absentees logged",   "info",    now.minusHours(1).minusMinutes(22));
        ccFeed(feedRepo, school, "firefighting", "RESOLVED",            "FF-005 Sports turf repair marked fulfilled",            "success", now.minusHours(2));
        ccFeed(feedRepo, school, "students",     "ADMISSION",           "3 new admissions confirmed · Grade 6",                  "success", now.minusHours(3));
        ccFeed(feedRepo, school, "fees",         "CONCESSION",          "Concession application submitted · ADM-1034",           "info",    now.minusHours(4));
        ccFeed(feedRepo, school, "supply",       "DELIVERY",            "Stationery kits batch delivered — 487 units",           "success", now.minusHours(5));
        ccFeed(feedRepo, school, "firefighting", "APPROVED",            "FF-006 House stage lighting approved by principal",     "success", now.minusHours(6));
        ccFeed(feedRepo, school, "attendance",   "EXPORT",              "September attendance report exported",                  "info",    now.minusHours(8));
        ccFeed(feedRepo, school, "fees",         "OVERDUE",             "12 students crossed overdue threshold — 30+ days",      "warning", now.minusHours(10));
        ccFeed(feedRepo, school, "supply",       "CONTRACT",            "Housekeeping AMC contract renewed for 12 months",       "success", now.minusHours(12));
        ccFeed(feedRepo, school, "students",     "TRANSFER",            "2 student transfer certificates issued",                "info",    now.minusHours(15));
        ccFeed(feedRepo, school, "firefighting", "QUOTE_ADDED",         "FF-007 Custoking quote added — ₹17.5L",                 "info",    now.minusHours(20));
        ccFeed(feedRepo, school, "fees",         "SCHOLARSHIP",         "Merit scholarship list finalized · 12 students",        "success", now.minusHours(24));

        // 6 broadcasts in mixed statuses
        ccBroadcast(broadcastRepo, school, "fees",         "Term 2 Fee Payment Reminder",         "Dear Parent, your fee payment is due by 5th of this month. Pay via UPI or school portal to avoid late fees.",                   "ALL_PARENTS",   "SMS,WhatsApp",     "DRAFT",     null,                   null);
        ccBroadcast(broadcastRepo, school, "attendance",   "Parent–Teacher Meeting · Grade 6–8",  "We invite you to the Parent-Teacher Meeting on Saturday 15th. Please confirm attendance via the school portal.",                "GRADE_PARENTS", "SMS,Email,WhatsApp","SCHEDULED", now.plusDays(3),        now.minusHours(2));
        ccBroadcast(broadcastRepo, school, "students",     "Annual Day Invitation — 18th December","The Annual Day ceremony will be held on 18th December at 5 PM in the school auditorium. All parents are invited.",            "ALL_PARENTS",   "SMS,WhatsApp,Push","SENT",      null,                   now.minusDays(2));
        ccBroadcast(broadcastRepo, school, "supply",       "Uniform Distribution Schedule",       "New uniforms for Classes 1–5 will be distributed Monday 9 AM–12 PM. Please send the student with the fee receipt.",           "CLASS_PARENTS", "SMS",              "SENT",      null,                   now.minusDays(5));
        ccBroadcast(broadcastRepo, school, "firefighting", "Sports Day Postponement Notice",      "Sports Day on 12th has been postponed to 19th due to weather. Updated schedule will be shared shortly.",                      "ALL_PARENTS",   "SMS,WhatsApp",     "DRAFT",     now.plusDays(7),        null);
        ccBroadcast(broadcastRepo, school, "fees",         "Early Bird Fee Discount — Last 3 Days","Pay full annual fees before 31st and avail 5% early-bird discount. Log in to the school portal for details.",                "ALL_PARENTS",   "SMS,Email",        "SCHEDULED", now.plusDays(2),        now.minusHours(5));

        // Delivery logs for the 2 SENT broadcasts (Annual Day + Uniform Distribution)
        broadcastRepo.findBySchoolIdOrderByCreatedAtDesc(school.getId()).stream()
                .filter(b -> "SENT".equals(b.getStatus()))
                .limit(2)
                .forEach(b -> {
                    String[] channels = b.getChannels().split(",");
                    for (String ch : channels) {
                        for (int i = 0; i < 12; i++) {
                            String status = i < 10 ? "DELIVERED" : i == 10 ? "FAILED" : "PENDING";
                            ccDeliveryLog(deliveryRepo, b.getId(), "PARENT", "parent-" + i + "@demo.in",
                                    ch.trim(), status, now.minusDays(i < 10 ? 2 : 5));
                        }
                    }
                });
    }

    private void ccAction(CommandCenterActionRepository repo, SchoolEntity school,
            String module, String urgency, int confidence, String title,
            String reason, String impact, String currentState, String targetState,
            String ctaLabel, OffsetDateTime createdAt) {
        CommandCenterActionEntity e = new CommandCenterActionEntity();
        e.setSchoolId(school.getId());
        e.setModule(module);
        e.setUrgency(urgency);
        e.setConfidence(confidence);
        e.setTitle(title);
        e.setReason(reason);
        e.setImpact(impact);
        e.setCurrentState(currentState);
        e.setTargetState(targetState);
        e.setCtaLabel(ctaLabel);
        e.setStatus("OPEN");
        e.setSourceType("SEED");
        e.setCreatedAt(createdAt);
        repo.save(e);
    }

    private void ccFeed(CommandCenterFeedRepository repo, SchoolEntity school,
            String module, String eventType, String title, String severity, OffsetDateTime createdAt) {
        CommandCenterFeedEntity e = new CommandCenterFeedEntity();
        e.setSchoolId(school.getId());
        e.setModule(module);
        e.setEventType(eventType);
        e.setTitle(title);
        e.setSeverity(severity);
        e.setCreatedAt(createdAt);
        repo.save(e);
    }

    private void ccDeliveryLog(NotificationDeliveryLogRepository repo, UUID broadcastId,
            String recipientType, String recipientRef, String channel,
            String status, OffsetDateTime createdAt) {
        NotificationDeliveryLogEntity e = new NotificationDeliveryLogEntity();
        e.setBroadcastId(broadcastId);
        e.setRecipientType(recipientType);
        e.setRecipientRef(recipientRef);
        e.setChannel(channel);
        e.setStatus(status);
        if ("DELIVERED".equals(status)) e.setDeliveredAt(createdAt.plusMinutes(2));
        e.setCreatedAt(createdAt);
        repo.save(e);
    }

    private void ccBroadcast(NotificationBroadcastRepository repo, SchoolEntity school,
            String module, String title, String message, String audienceType,
            String channels, String status, OffsetDateTime scheduledAt, OffsetDateTime sentAt) {
        NotificationBroadcastEntity e = new NotificationBroadcastEntity();
        e.setSchoolId(school.getId());
        e.setModule(module);
        e.setTitle(title);
        e.setMessage(message);
        e.setAudienceType(audienceType);
        e.setChannels(channels);
        e.setStatus(status);
        e.setScheduledAt(scheduledAt);
        e.setSentAt(sentAt);
        repo.save(e);
    }

    private void seedPhotographyEvent(
            AcademicEventRepository eventRepo,
            EventStudentContributionRepository contribRepo,
            StudentRepository studentRepo,
            SchoolEntity school,
            AcademicYearEntity year,
            String eventId,
            String prefix) {

        if (eventRepo.findFirstBySchoolIdAndEventTypeAndStatus(
                school.getId(), ClassPhotographyService.EVENT_TYPE_CLASS_PHOTOGRAPHY, "ACTIVE").isPresent()) {
            return;
        }

        AcademicEventEntity event = new AcademicEventEntity();
        event.setId(eventId);
        event.setSchoolId(school.getId());
        event.setAcademicYearId(year.getId());
        event.setTitle("Class Photography 2025–26");
        event.setEventType(ClassPhotographyService.EVENT_TYPE_CLASS_PHOTOGRAPHY);
        event.setEventDate(LocalDate.now().plusDays(18));
        event.setTotalBudget(4500000L);          // ₹45,000 in paise
        event.setSchoolContribution(2000000L);   // ₹20,000
        event.setStudentContributionTarget(2500000L); // ₹25,000
        event.setStatus("ACTIVE");
        eventRepo.save(event);

        java.util.List<StudentEntity> students = studentRepo.findAll().stream()
                .filter(s -> school.getId().equals(s.getSchool().getId()))
                .limit(15)
                .toList();

        long perStudent = students.isEmpty() ? 0 : 2500000L / students.size();
        int idx = 0;
        for (StudentEntity s : students) {
            EventStudentContributionEntity c = new EventStudentContributionEntity();
            c.setId(UUID.randomUUID().toString());
            c.setEvent(event);
            c.setStudent(s);
            c.setSchoolId(school.getId());
            c.setExpectedAmount(perStudent);
            long paid = idx % 3 == 0 ? perStudent : (idx % 3 == 1 ? perStudent / 2 : 0L);
            c.setPaidAmount(paid);
            c.setStatus(idx % 3 == 0 ? "PAID" : (idx % 3 == 1 ? "PARTIAL" : "PENDING"));
            contribRepo.save(c);
            idx++;
        }
    }
}
