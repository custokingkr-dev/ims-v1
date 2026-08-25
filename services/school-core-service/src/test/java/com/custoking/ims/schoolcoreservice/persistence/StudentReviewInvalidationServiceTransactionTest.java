package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig(StudentReviewInvalidationServiceTransactionTest.Config.class)
class StudentReviewInvalidationServiceTransactionTest {

    @Autowired
    StudentReviewInvalidationService reviewInvalidation;

    @Test
    void rejectsMutationWhenCallerHasNoTransaction() {
        assertThatThrownBy(() -> reviewInvalidation.invalidatePhoto(1L))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("existing transaction");
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        OutboxWriter outboxWriter() {
            return mock(OutboxWriter.class);
        }

        @Bean
        StudentReviewInvalidationService reviewInvalidation(JdbcClient jdbc, OutboxWriter outbox) {
            return new StudentReviewInvalidationService(jdbc, outbox);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
