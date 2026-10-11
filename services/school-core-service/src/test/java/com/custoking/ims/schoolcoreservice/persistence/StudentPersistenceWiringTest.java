package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.erasure.StudentErasureJournal;
import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StudentPersistenceWiringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    @Test void managedGroupsWireWithoutCyclesAndCannotWriteOutsideAnApplicationTransaction() {
        JdbcClient jdbc = mock(JdbcClient.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class, StudentReadRepository.class, StudentDirectoryQueries.class,
                    StudentProfileQueries.class, StudentStructurePersistence.class, StudentEnrollmentPersistence.class,
                    StudentImportPersistence.class, StudentPromotionPersistence.class, StudentReviewPersistence.class,
                    StudentReviewInvalidationService.class);
            context.registerBean(JdbcClient.class, () -> jdbc);
            context.registerBean(StudentPhotoStorage.class, () -> mock(StudentPhotoStorage.class));
            context.registerBean(OutboxWriter.class, () -> mock(OutboxWriter.class));
            context.registerBean(StudentErasureJournal.class, () -> mock(StudentErasureJournal.class));
            context.registerBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(mock(DataSource.class)));
            context.refresh();
            assertThatThrownBy(() -> context.getBean(StudentImportPersistence.class).confirmImport(Map.of()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> context.getBean(StudentPromotionPersistence.class).createPromotionBatch(Map.of()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> context.getBean(StudentReviewPersistence.class).initiateIdCardReview(Map.of()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> context.getBean(StudentReadRepository.class).confirmImport(Map.of()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(jdbc);
        }
    }
}
