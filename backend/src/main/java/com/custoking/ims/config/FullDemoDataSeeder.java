package com.custoking.ims.config;

import com.custoking.ims.audit.AuditLogEntity;
import com.custoking.ims.audit.AuditLogRepository;
import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
import org.springframework.data.domain.PageRequest;
import com.custoking.ims.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Comprehensive demo-data seeder that builds rich scenario data on top of the
 * baseline data already created by {@link DatabaseBootstrap}.
 *
 * Activate with: {@code app.full-demo-seed.enabled=true}
 *
 * Fully idempotent — checks by stable business key before every insert.
 * Safe to re-run on a database that already contains the seed data.
 */
@Component
@ConditionalOnProperty(name = "app.full-demo-seed.enabled", havingValue = "true")
@Order(100)
public class FullDemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FullDemoDataSeeder.class);
    private static final String WELCOME_PASSWORD = "Welcome@123";

    private final AppUserRepository userRepo;
    private final SchoolRepository schoolRepo;
    private final AcademicYearRepository yearRepo;
    private final StudentRepository studentRepo;
    private final RoleRepository roleRepo;
    private final UserRoleAssignmentRepository assignmentRepo;
    private final SchoolModuleEntitlementRepository entitlementRepo;
    private final CatalogOrderRepository catalogOrderRepo;
    private final StudentReviewCampaignRepository campaignRepo;
    private final StudentReviewItemRepository reviewItemRepo;
    private final NotificationLogRepository notifLogRepo;
    private final AuditLogRepository auditRepo;
    private final TransactionTemplate txTemplate;
    private final PasswordUtil passwordUtil;

    public FullDemoDataSeeder(
            AppUserRepository userRepo,
            SchoolRepository schoolRepo,
            AcademicYearRepository yearRepo,
            StudentRepository studentRepo,
            RoleRepository roleRepo,
            UserRoleAssignmentRepository assignmentRepo,
            SchoolModuleEntitlementRepository entitlementRepo,
            CatalogOrderRepository catalogOrderRepo,
            StudentReviewCampaignRepository campaignRepo,
            StudentReviewItemRepository reviewItemRepo,
            NotificationLogRepository notifLogRepo,
            AuditLogRepository auditRepo,
            TransactionTemplate txTemplate,
            PasswordUtil passwordUtil) {
        this.userRepo        = userRepo;
        this.schoolRepo      = schoolRepo;
        this.yearRepo        = yearRepo;
        this.studentRepo     = studentRepo;
        this.roleRepo        = roleRepo;
        this.assignmentRepo  = assignmentRepo;
        this.entitlementRepo = entitlementRepo;
        this.catalogOrderRepo = catalogOrderRepo;
        this.campaignRepo    = campaignRepo;
        this.reviewItemRepo  = reviewItemRepo;
        this.notifLogRepo    = notifLogRepo;
        this.auditRepo       = auditRepo;
        this.txTemplate      = txTemplate;
        this.passwordUtil    = passwordUtil;
    }

    @Override
    public void run(ApplicationArguments args) {
        txTemplate.executeWithoutResult(status -> {
            SchoolEntity demo = schoolRepo.findByShortCodeIgnoreCase("DEMO").orElse(null);
            if (demo == null) {
                log.warn("FullDemoDataSeeder: DEMO school not found — ensure app.bootstrap-users=true has run first.");
                return;
            }
            SchoolEntity grwd    = schoolRepo.findByShortCodeIgnoreCase("GRWD").orElseThrow();
            SchoolEntity sunrise = schoolRepo.findByShortCodeIgnoreCase("SUNRISE").orElseThrow();
            AcademicYearEntity year = yearRepo.findFirstByActiveTrue().orElseThrow();

            seedDemoUsers(demo, grwd, sunrise, year);
            seedModuleEntitlements(demo, grwd, sunrise);
            seedHistoricalCatalogOrders(demo);
            seedReviewCampaigns(demo, year);
            seedNotificationLogs(demo);
            seedAuditLog(demo);

            log.info("FullDemoDataSeeder: completed.");
        });
    }

    // ── 1. Additional demo users with RBAC ─────────────────────────────────────

    private void seedDemoUsers(SchoolEntity demo, SchoolEntity grwd, SchoolEntity sunrise,
                               AcademicYearEntity year) {
        String hash = passwordUtil.hash(WELCOME_PASSWORD);

        ensureUser("teacher@demo.custoking.com",    "Demo Teacher",    hash, "TEACHER",    demo);
        ensureUser("accountant@demo.custoking.com", "Demo Accountant", hash, "ACCOUNTANT", demo);
        ensureUser("operations@demo.custoking.com", "Demo Operations", hash, "OPERATIONS", demo);
        ensureUser("viewer@demo.custoking.com",     "Demo Viewer",     hash, "VIEWER",     demo);
        ensureUser("teacher@greenwood.custoking.com", "Greenwood Teacher", hash, "TEACHER", grwd);
        ensureUser("teacher@sunrise.custoking.com",   "Sunrise Teacher",   hash, "TEACHER", sunrise);
    }

    private void ensureUser(String email, String fullName, String hash,
                            String roleName, SchoolEntity school) {
        AppUserEntity user = userRepo.findByEmailIgnoreCase(email).orElseGet(() -> {
            AppUserEntity u = new AppUserEntity();
            u.setEmail(email);
            u.setFullName(fullName);
            u.setPasswordHash(hash);
            u.setRole(roleName);
            u.setBranchId(school.getId());
            u.setBranchName(school.getName());
            return userRepo.save(u);
        });

        RoleEntity role = roleRepo.findByName(roleName).orElseThrow(
                () -> new IllegalStateException("Role not found: " + roleName));

        boolean alreadyAssigned = assignmentRepo.existsActiveAssignment(
                user.getId(), roleName, school.getId(), null);
        if (!alreadyAssigned) {
            UserRoleAssignmentEntity ura = new UserRoleAssignmentEntity();
            ura.setUser(user);
            ura.setRole(role);
            ura.setSchoolId(school.getId());
            ura.setActive(true);
            ura.setAssignedBy(1L);
            assignmentRepo.save(ura);
        }
    }

    // ── 2. Module entitlements ─────────────────────────────────────────────────

    private static final List<String> ALL_MODULES = List.of(
            "STUDENTS", "FEES", "ATTENDANCE", "INVOICES", "PAYMENTS",
            "ORDERS", "FIREFIGHTING", "REPORTS");

    private static final List<String> CORE_MODULES = List.of(
            "STUDENTS", "FEES", "ATTENDANCE", "ORDERS", "FIREFIGHTING", "REPORTS");

    private void seedModuleEntitlements(SchoolEntity demo, SchoolEntity grwd, SchoolEntity sunrise) {
        for (String m : ALL_MODULES)  ensureEntitlement(demo,    m);
        for (String m : CORE_MODULES) ensureEntitlement(grwd,    m);
        for (String m : ALL_MODULES)  ensureEntitlement(sunrise, m);
    }

    private void ensureEntitlement(SchoolEntity school, String moduleCode) {
        entitlementRepo.findBySchool_IdAndModuleCode(school.getId(), moduleCode).orElseGet(() -> {
            SchoolModuleEntitlementEntity e = new SchoolModuleEntitlementEntity();
            e.setSchool(school);
            e.setModuleCode(moduleCode);
            e.setEnabled(true);
            e.setPlan("FULL");
            e.setCreatedBy(1L);
            return entitlementRepo.save(e);
        });
    }

    // ── 3. Historical catalog orders for reorder-signal demo ──────────────────
    //
    // Status APPROVED/FULFILLED is required for ReorderPredictionService.
    // Dates are relative to today so signals stay accurate across deployments.

    private void seedHistoricalCatalogOrders(SchoolEntity demo) {
        LocalDate today = LocalDate.now();

        // UNIFORMS — two orders → avg interval ~120 days, last order 160 days ago → RED
        ensureCatalogOrder("CK-SEED-001", demo, "UNIFORMS",
                "Class 1–5 Uniforms (Apr batch)",   "280 sets", 8400000L, 0L, "FULFILLED",
                today.minusDays(280));
        ensureCatalogOrder("CK-SEED-002", demo, "UNIFORMS",
                "Class 6–10 Uniforms (Sep batch)",  "320 sets", 9600000L, 0L, "APPROVED",
                today.minusDays(160));

        // NOTEBOOKS — two orders → avg interval ~200 days, last order 200 days ago → YELLOW
        ensureCatalogOrder("CK-SEED-003", demo, "NOTEBOOKS",
                "A4 ruled notebooks Term 1",        "1000 units", 4500000L, 0L, "FULFILLED",
                today.minusDays(400));
        ensureCatalogOrder("CK-SEED-004", demo, "NOTEBOOKS",
                "A4 ruled notebooks Term 2",        "1200 units", 5400000L, 0L, "APPROVED",
                today.minusDays(200));

        // STATIONERY — single recent order → OK
        ensureCatalogOrder("CK-SEED-005", demo, "STATIONERY",
                "Stationery kits Term 3 refresh",   "487 units", 2100000L, 0L, "APPROVED",
                today.minusDays(40));

        // EVENTS — single old order → YELLOW (> 180 days single-order threshold)
        ensureCatalogOrder("CK-SEED-006", demo, "EVENTS",
                "Annual Day print kit",             "1 lot",     1660000L, 0L, "APPROVED",
                today.minusDays(220));

        // IDCARDS — single recent → OK
        ensureCatalogOrder("CK-SEED-007", demo, "IDCARDS",
                "ID cards Term 3 issuance",         "120 cards",  360000L, 0L, "FULFILLED",
                today.minusDays(50));
    }

    private void ensureCatalogOrder(String id, SchoolEntity school, String category,
                                    String title, String items, long subtotal, long gst,
                                    String status, LocalDate placedDate) {
        if (catalogOrderRepo.existsById(id)) return;
        CatalogOrderEntity e = new CatalogOrderEntity();
        e.setId(id);
        e.setSchool(school);
        e.setCategory(category);
        e.setOrderData("{\"title\":\"" + title + "\",\"items\":\"" + items + "\"}");
        e.setSubtotal(subtotal);
        e.setGst(gst);
        e.setTotalAmount(subtotal + gst);
        e.setStatus(status);
        e.setPlacedBy(2L);
        e.setPlacedAt(placedDate.atStartOfDay().atOffset(ZoneOffset.UTC));
        e.setCreatedAt(placedDate.atStartOfDay().atOffset(ZoneOffset.UTC));
        catalogOrderRepo.save(e);
    }

    // ── 4. Student review campaigns ────────────────────────────────────────────

    private void seedReviewCampaigns(SchoolEntity demo, AcademicYearEntity year) {
        List<StudentEntity> demoStudents = studentRepo.findBySchool_IdOrderByFullNameAsc(demo.getId());
        if (demoStudents.isEmpty()) return;

        seedIdCardReviewCampaign(demo, year, demoStudents);
        seedFullNameVerificationCampaign(demo, year, demoStudents);
    }

    private void seedIdCardReviewCampaign(SchoolEntity demo, AcademicYearEntity year,
                                          List<StudentEntity> students) {
        String campaignId = "camp-idcard-demo-" + year.getId();
        if (campaignRepo.existsById(campaignId)) return;

        StudentReviewCampaignEntity campaign = new StudentReviewCampaignEntity();
        campaign.setId(campaignId);
        campaign.setSchoolId(demo.getId());
        campaign.setAcademicYearId(year.getId());
        campaign.setReviewType("ID_CARD_REVIEW");
        campaign.setTitle("ID Card Data Verification 2025–26");
        campaign.setStatus("ACTIVE");
        campaign.setVerifier("TEACHER");
        campaign.setInitiatedBy(2L);
        campaign.setInitiatedAt(OffsetDateTime.now().minusDays(7));
        campaign.setDueDate(LocalDate.now().plusDays(14));
        campaignRepo.save(campaign);

        // Seed review items — a sample of students with realistic mixed statuses
        String[] statuses = {"COMPLETED","COMPLETED","COMPLETED","COMPLETED",
                             "PENDING","PENDING","PENDING",
                             "NEEDS_CORRECTION","NEEDS_CORRECTION","PENDING"};
        List<StudentEntity> sample = students.stream().limit(10).toList();
        for (int i = 0; i < sample.size(); i++) {
            StudentEntity student = sample.get(i);
            String itemId = "item-idcard-" + student.getId();
            if (reviewItemRepo.existsById(itemId)) continue;

            StudentReviewItemEntity item = new StudentReviewItemEntity();
            item.setId(itemId);
            item.setCampaign(campaign);
            item.setStudent(student);
            item.setSchoolId(demo.getId());
            item.setCurrentFullName(student.getFullName());
            String st = statuses[i % statuses.length];
            item.setStatus(st);
            if ("COMPLETED".equals(st)) {
                item.setVerifiedPhoto(true);
                item.setVerifiedFullName(true);
                item.setVerifiedAdmissionNo(true);
                item.setVerifiedClassSection(true);
                item.setVerifiedRollNo(true);
                item.setTeacherConfirmed(true);
                item.setCompletedAt(OffsetDateTime.now().minusDays(3));
            } else if ("NEEDS_CORRECTION".equals(st)) {
                item.setCorrectionRequested(true);
                item.setCorrectionNotes("Father name mismatch on ID vs admission form.");
            }
            reviewItemRepo.save(item);
        }
    }

    private void seedFullNameVerificationCampaign(SchoolEntity demo, AcademicYearEntity year,
                                                   List<StudentEntity> students) {
        String campaignId = "camp-fullname-demo-" + year.getId();
        if (campaignRepo.existsById(campaignId)) return;

        StudentReviewCampaignEntity campaign = new StudentReviewCampaignEntity();
        campaign.setId(campaignId);
        campaign.setSchoolId(demo.getId());
        campaign.setAcademicYearId(year.getId());
        campaign.setReviewType("FULL_NAME_VERIFICATION");
        campaign.setTitle("Full Name Verification 2025–26");
        campaign.setStatus("ACTIVE");
        campaign.setVerifier("PARENT");
        campaign.setInitiatedBy(2L);
        campaign.setInitiatedAt(OffsetDateTime.now().minusDays(5));
        campaign.setDueDate(LocalDate.now().plusDays(10));
        campaignRepo.save(campaign);

        String[] statuses = {"COMPLETED","COMPLETED","COMPLETED",
                             "PENDING","PENDING","PENDING","PENDING",
                             "NEEDS_CORRECTION"};
        List<StudentEntity> sample = students.stream().skip(10).limit(8).toList();
        for (int i = 0; i < sample.size(); i++) {
            StudentEntity student = sample.get(i);
            String itemId = "item-fname-" + student.getId();
            if (reviewItemRepo.existsById(itemId)) continue;

            StudentReviewItemEntity item = new StudentReviewItemEntity();
            item.setId(itemId);
            item.setCampaign(campaign);
            item.setStudent(student);
            item.setSchoolId(demo.getId());
            item.setCurrentFullName(student.getFullName());
            String st = statuses[i % statuses.length];
            item.setStatus(st);
            if ("COMPLETED".equals(st)) {
                item.setVerifiedFullName(true);
                item.setParentConfirmed(true);
                item.setCompletedAt(OffsetDateTime.now().minusDays(2));
            } else if ("NEEDS_CORRECTION".equals(st)) {
                item.setCorrectionRequested(true);
                item.setSuggestedFullName(student.getFullName() + " Kumar");
                item.setCorrectionNotes("Parent requested middle name addition.");
            }
            reviewItemRepo.save(item);
        }
    }

    // ── 5. Notification logs ───────────────────────────────────────────────────

    private void seedNotificationLogs(SchoolEntity demo) {
        List<StudentEntity> students = studentRepo.findBySchool_IdOrderByFullNameAsc(demo.getId());
        if (students.isEmpty()) return;

        String[] types  = {"FEE_REMINDER", "ATTENDANCE_ALERT", "FEE_REMINDER",
                           "MEETING_INVITE", "FEE_REMINDER"};
        String[] msgs   = {
            "Dear Parent, your ward's Term 2 fees are due. Please pay before the 5th to avoid late fees.",
            "Alert: Your ward was absent for 3 consecutive days. Please contact the school.",
            "Reminder: ₹4,200 outstanding fees. Pay via UPI at school portal.",
            "Dear Parent, a Parent-Teacher Meeting is scheduled for Saturday 15th at 10 AM.",
            "Final reminder: Fee deadline is tomorrow. Contact accounts to avoid suspension."
        };

        for (int i = 0; i < 5 && i < students.size(); i++) {
            StudentEntity student = students.get(i);
            String logId = "notif-seed-demo-" + student.getId() + "-" + types[i];
            if (notifLogRepo.existsById(logId)) continue;

            NotificationLogEntity n = new NotificationLogEntity();
            n.setId(logId);
            n.setSchoolId(demo.getId());
            n.setStudentId(student.getId());
            n.setParentContact(student.getFatherContact());
            n.setChannel("WhatsApp");
            n.setNotificationType(types[i]);
            n.setMessage(msgs[i]);
            n.setStatus(i < 4 ? "SENT" : "FAILED");
            n.setSentBy(2L);
            n.setSentAt(OffsetDateTime.now().minusDays(i + 1));
            n.setCreatedAt(OffsetDateTime.now().minusDays(i + 1));
            n.setUpdatedAt(OffsetDateTime.now().minusDays(i + 1));
            if (i == 4) n.setFailureReason("Invalid phone number format");
            notifLogRepo.save(n);
        }
    }

    // ── 6. Audit log entries ───────────────────────────────────────────────────

    private void seedAuditLog(SchoolEntity demo) {
        if (auditRepo.findBySchoolIdOrderByTimestampDesc(demo.getId(),
                PageRequest.of(0, 1)).hasContent()) return;

        record Entry(String action, String entityType, String entityId, String old, String nw) {}
        List<Entry> entries = List.of(
            new Entry("FEE_REMINDER_SENT",     "STUDENT",             "1001", null,     "32 reminders sent via WhatsApp"),
            new Entry("ORDER_APPROVED",        "CATALOG_ORDER",       "CK-SEED-002", "DRAFT",  "APPROVED"),
            new Entry("FIREFIGHTING_APPROVED", "FIREFIGHTING_REQUEST","FF-006",      "AWAITING_PRINCIPAL", "APPROVED"),
            new Entry("STUDENT_REVIEW_ITEM_COMPLETED", "REVIEW_ITEM", "item-idcard-1001", "PENDING", "COMPLETED"),
            new Entry("VENDOR_MARKED_PAID",    "CATALOG_ORDER",       "CK-1082",    "UNPAID", "PAID"),
            new Entry("MODULE_ENTITLEMENT_GRANTED", "SCHOOL",         String.valueOf(demo.getId()), null, "ORDERS module enabled"),
            new Entry("LOGIN_SUCCESS",         null,                  null,         null,     null)
        );

        OffsetDateTime base = OffsetDateTime.now().minusHours(12);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            AuditLogEntity entry = new AuditLogEntity();
            entry.setAction(e.action());
            entry.setUserId(2L);
            entry.setSchoolId(demo.getId());
            entry.setEntityType(e.entityType());
            entry.setEntityId(e.entityId());
            entry.setOldValue(e.old());
            entry.setNewValue(e.nw());
            entry.setOutcome("SUCCESS");
            entry.setActorEmail("admin@demo.custoking.com");
            entry.setTimestamp(base.minusMinutes(i * 30L));
            auditRepo.save(entry);
        }
    }
}
