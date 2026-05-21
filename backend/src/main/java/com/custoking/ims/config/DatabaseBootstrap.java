package com.custoking.ims.config;

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
        FirefightingRequestEntity ff9 = fire(repo, school, "FF-009", "New projector screens", "Electronics & security", "DRAFT", 1400000L, null, null, OffsetDateTime.now().minusDays(1));
    }
    private FirefightingRequestEntity fire(FirefightingRequestRepository repo, SchoolEntity school, String code, String title, String category, String status, long budget, String winner, Long amount, OffsetDateTime createdAt){ FirefightingRequestEntity e = new FirefightingRequestEntity(); e.setCode(code); e.setSchool(school); e.setTitle(title); e.setCategory(category); e.setUrgency("MEDIUM"); e.setRequiredByDate(createdAt.toLocalDate().plusDays(14)); e.setEstimatedBudget(budget); e.setDescription(title + " required urgently for school operations"); e.setRaisedBy(2L); e.setStatus(status); e.setWinnerVendor(winner); e.setWinnerAmount(amount); e.setCreatedAt(createdAt); if (List.of("APPROVED","FULFILLED").contains(status)) { e.setBursarApprovedAt(createdAt.plusDays(1)); e.setPrincipalApprovedAt(createdAt.plusDays(2)); } repo.save(e); return e; }
    private void quote(FirefightingQuotationRepository repo, FirefightingRequestEntity request, String vendor, long amount, String timeline, boolean ck, boolean rec, String notes){ FirefightingQuotationEntity q = new FirefightingQuotationEntity(); q.setId(UUID.randomUUID().toString()); q.setRequest(request); q.setVendorName(vendor); q.setAmount(amount); q.setDeliveryTimeline(timeline); q.setCustoking(ck); q.setRecommended(rec); q.setNotes(notes); repo.save(q);}    
}
