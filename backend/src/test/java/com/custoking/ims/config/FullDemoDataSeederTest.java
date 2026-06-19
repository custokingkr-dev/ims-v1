package com.custoking.ims.config;

import com.custoking.ims.audit.AuditLogEntity;
import com.custoking.ims.audit.AuditLogRepository;

import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
import com.custoking.ims.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FullDemoDataSeeder")
class FullDemoDataSeederTest {

    @Mock AppUserRepository userRepo;
    @Mock SchoolRepository schoolRepo;
    @Mock AcademicYearRepository yearRepo;
    @Mock StudentRepository studentRepo;
    @Mock RoleRepository roleRepo;
    @Mock UserRoleAssignmentRepository assignmentRepo;
    @Mock SchoolModuleEntitlementRepository entitlementRepo;
    @Mock CatalogOrderRepository catalogOrderRepo;
    @Mock StudentReviewCampaignRepository campaignRepo;
    @Mock StudentReviewItemRepository reviewItemRepo;
    @Mock NotificationLogRepository notifLogRepo;
    @Mock AuditLogRepository auditRepo;
    @Mock TransactionTemplate txTemplate;
    @Mock PasswordUtil passwordUtil;

    @InjectMocks FullDemoDataSeeder seeder;

    private SchoolEntity demo;
    private AcademicYearEntity year;

    @BeforeEach
    void setUp() {
        demo = new SchoolEntity();
        demo.setId(1L);
        demo.setName("Custoking Demo School");
        demo.setShortCode("DEMO");

        year = new AcademicYearEntity();
        year.setId("ay_2025_26");
        year.setLabel("2025–26");
        year.setActive(true);

        // TransactionTemplate executes the consumer immediately in tests
        doAnswer(inv -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> consumer =
                    inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        when(passwordUtil.hash(anyString())).thenReturn("$2a$10$hashed");
        when(schoolRepo.findByShortCodeIgnoreCase("DEMO")).thenReturn(Optional.of(demo));
        when(schoolRepo.findByShortCodeIgnoreCase("GRWD")).thenReturn(Optional.of(makeSchool(2L, "GRWD")));
        when(schoolRepo.findByShortCodeIgnoreCase("SUNRISE")).thenReturn(Optional.of(makeSchool(3L, "SUNRISE")));
        when(yearRepo.findFirstByActiveTrue()).thenReturn(Optional.of(year));

        RoleEntity teacherRole = new RoleEntity(); teacherRole.setName("TEACHER");
        RoleEntity accountantRole = new RoleEntity(); accountantRole.setName("ACCOUNTANT");
        RoleEntity opsRole = new RoleEntity(); opsRole.setName("OPERATIONS");
        RoleEntity viewerRole = new RoleEntity(); viewerRole.setName("VIEWER");
        when(roleRepo.findByName("TEACHER")).thenReturn(Optional.of(teacherRole));
        when(roleRepo.findByName("ACCOUNTANT")).thenReturn(Optional.of(accountantRole));
        when(roleRepo.findByName("OPERATIONS")).thenReturn(Optional.of(opsRole));
        when(roleRepo.findByName("VIEWER")).thenReturn(Optional.of(viewerRole));

        when(userRepo.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        AppUserEntity savedUser = new AppUserEntity(); savedUser.setId(10L);
        when(userRepo.save(any())).thenReturn(savedUser);
        when(assignmentRepo.existsActiveAssignment(anyLong(), anyString(), any(), any())).thenReturn(false);

        when(entitlementRepo.findBySchool_IdAndModuleCode(anyLong(), anyString()))
                .thenReturn(Optional.empty());

        when(catalogOrderRepo.existsById(anyString())).thenReturn(false);

        when(studentRepo.findBySchool_IdOrderByFullNameAsc(anyLong())).thenReturn(List.of());

        when(auditRepo.findBySchoolIdOrderByTimestampDesc(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(notifLogRepo.existsById(anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("skips seeding when DEMO school is absent")
    void run_demoSchoolMissing_skips() throws Exception {
        when(schoolRepo.findByShortCodeIgnoreCase("DEMO")).thenReturn(Optional.empty());

        seeder.run(null);

        verify(entitlementRepo, never()).save(any());
        verify(catalogOrderRepo, never()).save(any());
    }

    @Test
    @DisplayName("creates users for each demo role")
    void run_createsExpectedDemoUsers() throws Exception {
        seeder.run(null);

        verify(userRepo, atLeast(4)).save(any(AppUserEntity.class));
    }

    @Test
    @DisplayName("seeds module entitlements for all three schools")
    void run_seedsModuleEntitlements() throws Exception {
        seeder.run(null);

        // DEMO + SUNRISE get 8 modules each, GRWD gets 6 → 22 calls when none exist
        verify(entitlementRepo, atLeast(20)).save(any(SchoolModuleEntitlementEntity.class));
    }

    @Test
    @DisplayName("seeds 7 historical catalog orders for reorder signals")
    void run_seedsHistoricalCatalogOrders() throws Exception {
        seeder.run(null);

        verify(catalogOrderRepo, times(7)).save(any(CatalogOrderEntity.class));
    }

    @Test
    @DisplayName("skips catalog order when ID already exists")
    void run_catalogOrder_idempotent() throws Exception {
        when(catalogOrderRepo.existsById(anyString())).thenReturn(true);

        seeder.run(null);

        verify(catalogOrderRepo, never()).save(any());
    }

    @Test
    @DisplayName("skips audit log when school already has entries")
    void run_auditLog_skippedIfDataExists() throws Exception {
        AuditLogEntity existing = new AuditLogEntity();
        when(auditRepo.findBySchoolIdOrderByTimestampDesc(eq(1L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(existing)));

        seeder.run(null);

        verify(auditRepo, never()).save(any());
    }

    private SchoolEntity makeSchool(Long id, String code) {
        SchoolEntity s = new SchoolEntity();
        s.setId(id);
        s.setName(code + " School");
        s.setShortCode(code);
        return s;
    }
}
