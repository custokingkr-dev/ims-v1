package com.custoking.ims.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that read-path methods on the core services are annotated with
 * {@code @Transactional(readOnly = true)}.
 *
 * No Spring context is loaded — pure reflection, runs in milliseconds.
 * Prevents regressions where a read method inadvertently opts back in to
 * full read-write transaction semantics (skipped dirty-checking, flush-mode
 * NEVER, and read-replica routing all depend on this flag).
 */
@DisplayName("Read-path methods must carry @Transactional(readOnly=true)")
class ReadTransactionTest {

    // ── StudentService ────────────────────────────────────────────────────────

    @Test
    @DisplayName("StudentService.studentsData is readOnly")
    void studentService_studentsData() {
        assertReadOnly(StudentService.class, "studentsData", Long.class);
    }

    @Test
    @DisplayName("StudentService.studentsPage is readOnly")
    void studentService_studentsPage() {
        assertReadOnlyByName(StudentService.class, "studentsPage");
    }

    @Test
    @DisplayName("StudentService.studentDetail is readOnly")
    void studentService_studentDetail() {
        assertReadOnlyByName(StudentService.class, "studentDetail");
    }

    @Test
    @DisplayName("StudentService.classesList is readOnly")
    void studentService_classesList() {
        assertReadOnlyByName(StudentService.class, "classesList");
    }

    @Test
    @DisplayName("StudentService.sectionsForClass is readOnly")
    void studentService_sectionsForClass() {
        assertReadOnlyByName(StudentService.class, "sectionsForClass");
    }

    @Test
    @DisplayName("StudentService.studentsForClassSection is readOnly")
    void studentService_studentsForClassSection() {
        assertReadOnlyByName(StudentService.class, "studentsForClassSection");
    }

    @Test
    @DisplayName("StudentService.importJobStatus is readOnly")
    void studentService_importJobStatus() {
        assertReadOnly(StudentService.class, "importJobStatus", String.class);
    }

    @Test
    @DisplayName("StudentService.scopedStudents is readOnly")
    void studentService_scopedStudents() {
        assertReadOnly(StudentService.class, "scopedStudents", Long.class);
    }

    // ── SupplyOrderService ────────────────────────────────────────────────────

    @Test
    @DisplayName("SupplyOrderService.catalogCategories is readOnly")
    void supplyOrderService_catalogCategories() {
        assertReadOnly(SupplyOrderService.class, "catalogCategories");
    }

    @Test
    @DisplayName("SupplyOrderService.listCatalogOrders (3-arg) is readOnly")
    void supplyOrderService_listCatalogOrders() {
        assertReadOnlyByName(SupplyOrderService.class, "listCatalogOrders");
    }

    @Test
    @DisplayName("SupplyOrderService.catalogOrderDetail is readOnly")
    void supplyOrderService_catalogOrderDetail() {
        assertReadOnlyByName(SupplyOrderService.class, "catalogOrderDetail");
    }

    @Test
    @DisplayName("SupplyOrderService.catalogOrderStats is readOnly")
    void supplyOrderService_catalogOrderStats() {
        assertReadOnlyByName(SupplyOrderService.class, "catalogOrderStats");
    }

    @Test
    @DisplayName("SupplyOrderService.listAnnualPlan is readOnly")
    void supplyOrderService_listAnnualPlan() {
        assertReadOnlyByName(SupplyOrderService.class, "listAnnualPlan");
    }

    @Test
    @DisplayName("SupplyOrderService.listOrdersPendingApproval is readOnly")
    void supplyOrderService_listOrdersPendingApproval() {
        assertReadOnlyByName(SupplyOrderService.class, "listOrdersPendingApproval");
    }

    // ── FirefightingService ───────────────────────────────────────────────────

    @Test
    @DisplayName("FirefightingService.listFireRequests is readOnly")
    void firefightingService_listFireRequests() {
        assertReadOnlyByName(FirefightingService.class, "listFireRequests");
    }

    @Test
    @DisplayName("FirefightingService.fireRequestStats is readOnly")
    void firefightingService_fireRequestStats() {
        assertReadOnlyByName(FirefightingService.class, "fireRequestStats");
    }

    @Test
    @DisplayName("FirefightingService.fireRequestDetail is readOnly")
    void firefightingService_fireRequestDetail() {
        assertReadOnlyByName(FirefightingService.class, "fireRequestDetail");
    }

    @Test
    @DisplayName("FirefightingService.pendingFireApprovals is readOnly")
    void firefightingService_pendingFireApprovals() {
        assertReadOnlyByName(FirefightingService.class, "pendingFireApprovals");
    }

    @Test
    @DisplayName("FirefightingService.fireRequestTimeline is readOnly")
    void firefightingService_fireRequestTimeline() {
        assertReadOnlyByName(FirefightingService.class, "fireRequestTimeline");
    }

    // ── FeeService ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FeeService.feeStructureData is readOnly")
    void feeService_feeStructureData() {
        assertReadOnly(FeeService.class, "feeStructureData", String.class);
    }

    @Test
    @DisplayName("FeeService.feeReport is readOnly")
    void feeService_feeReport() {
        assertReadOnlyByName(FeeService.class, "feeReport");
    }

    @Test
    @DisplayName("FeeService.feeOverdue is readOnly")
    void feeService_feeOverdue() {
        assertReadOnlyByName(FeeService.class, "feeOverdue");
    }

    @Test
    @DisplayName("FeeService.buildFeesModule is readOnly")
    void feeService_buildFeesModule() {
        assertReadOnlyByName(FeeService.class, "buildFeesModule");
    }

    @Test
    @DisplayName("FeeService.feeOverdueCount is readOnly")
    void feeService_feeOverdueCount() {
        assertReadOnlyByName(FeeService.class, "feeOverdueCount");
    }

    // ── WorkspaceService ──────────────────────────────────────────────────────

    @Test
    @DisplayName("WorkspaceService.workspace is readOnly")
    void workspaceService_workspace() {
        assertReadOnlyByName(WorkspaceService.class, "workspace");
    }

    @Test
    @DisplayName("WorkspaceService.users is readOnly")
    void workspaceService_users() {
        assertReadOnly(WorkspaceService.class, "users");
    }

    @Test
    @DisplayName("WorkspaceService.approvals is readOnly")
    void workspaceService_approvals() {
        assertReadOnlyByName(WorkspaceService.class, "approvals");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Asserts that the method identified by exact parameter types carries
     * {@code @Transactional(readOnly=true)}.
     */
    private void assertReadOnly(Class<?> serviceClass, String methodName, Class<?>... paramTypes) {
        try {
            Method m = serviceClass.getDeclaredMethod(methodName, paramTypes);
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx)
                    .as("%s.%s must be annotated @Transactional(readOnly=true)", serviceClass.getSimpleName(), methodName)
                    .isNotNull();
            assertThat(tx.readOnly())
                    .as("%s.%s @Transactional.readOnly must be true", serviceClass.getSimpleName(), methodName)
                    .isTrue();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "Method not found: " + serviceClass.getSimpleName() + "." + methodName
                            + " with params " + Arrays.toString(paramTypes), e);
        }
    }

    /**
     * Asserts that ALL overloads of {@code methodName} in {@code serviceClass}
     * carry {@code @Transactional(readOnly=true)}.  Use this when overloads share
     * the same semantics (e.g. zero-arg delegates calling the main implementation).
     */
    private void assertReadOnlyByName(Class<?> serviceClass, String methodName) {
        List<Method> methods = Stream.of(serviceClass.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();

        assertThat(methods)
                .as("No methods named '%s' found in %s", methodName, serviceClass.getSimpleName())
                .isNotEmpty();

        for (Method m : methods) {
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx)
                    .as("%s.%s(%s) must be annotated @Transactional(readOnly=true)",
                            serviceClass.getSimpleName(), methodName,
                            Arrays.toString(m.getParameterTypes()))
                    .isNotNull();
            assertThat(tx.readOnly())
                    .as("%s.%s(%s) @Transactional.readOnly must be true",
                            serviceClass.getSimpleName(), methodName,
                            Arrays.toString(m.getParameterTypes()))
                    .isTrue();
        }
    }
}
