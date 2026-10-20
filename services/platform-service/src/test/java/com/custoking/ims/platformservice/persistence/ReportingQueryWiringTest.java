package com.custoking.ims.platformservice.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReportingQueryWiringTest {
    @Test void managedFacadeUsesTheIndependentQueryGroupsWithoutCircularDependencies() {
        JdbcClient jdbc = mock(JdbcClient.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcClient.class, () -> jdbc);
            context.register(ReportingReadRepository.class, InvoiceReportingQueries.class, PhotographyReportingQueries.class,
                    AttendanceReportingQueries.class, FeeReportingQueries.class, ProcurementReportingQueries.class,
                    WorkspaceReportingQueries.class, CommandCenterReportingQueries.class,
                    ReportingSchoolContext.class, ReportingScalarQueries.class);
            context.refresh();
            ReportingReadRepository repository = context.getBean(ReportingReadRepository.class);
            assertThat(repository.lowAttendanceStudents(null, null)).isEmpty();
            assertThat(repository.feeDefaulters(null, null, null, null, null, 0, 25)).containsEntry("totalElements", 0L);
            assertThat(repository.reorderSignals(null)).containsEntry("alertCount", 0);
            assertThat(repository.vendorDues(null)).containsEntry("totalDuesPaise", 0L);
            verifyNoInteractions(jdbc);
        }
    }
}
