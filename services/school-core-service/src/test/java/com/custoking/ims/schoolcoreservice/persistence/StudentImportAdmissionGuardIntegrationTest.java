package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentImportAdmissionGuardIntegrationTest {

    static PostgreSQLContainer<?> postgres;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;

    @BeforeAll
    static void setUp() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void noisySchoolIsRejectedWithoutBlockingAnotherSchool() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        try {
            Future<Boolean> holder = executor.submit(() -> transaction.execute(status -> {
                StudentImportAdmissionGuard.acquire(jdbc, 10L);
                holderReady.countDown();
                await(releaseHolder);
                return true;
            }));

            assertThat(holderReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> transaction.execute(status -> {
                StudentImportAdmissionGuard.acquire(jdbc, 10L);
                return true;
            }))
                    .isInstanceOf(ImportAdmissionException.class)
                    .satisfies(error -> assertThat(((ImportAdmissionException) error).code())
                            .isEqualTo("school_import_active"));

            // The rejected noisy tenant does not consume the second fleet slot.
            Boolean otherSchoolAdmitted = transaction.execute(status -> {
                StudentImportAdmissionGuard.acquire(jdbc, 20L);
                return true;
            });
            assertThat(otherSchoolAdmitted).isEqualTo(Boolean.TRUE);

            releaseHolder.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void thirdConcurrentSchoolIsRejectedByTheTwoSlotFleetLimit() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch holdersReady = new CountDownLatch(StudentImportAdmissionGuard.FLEET_SLOTS);
        CountDownLatch releaseHolders = new CountDownLatch(1);
        try {
            Future<Boolean> first = holdSlot(executor, 101L, holdersReady, releaseHolders);
            Future<Boolean> second = holdSlot(executor, 202L, holdersReady, releaseHolders);
            assertThat(holdersReady.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> transaction.execute(status -> {
                StudentImportAdmissionGuard.acquire(jdbc, 303L);
                return true;
            }))
                    .isInstanceOf(ImportAdmissionException.class)
                    .satisfies(error -> {
                        ImportAdmissionException admission = (ImportAdmissionException) error;
                        assertThat(admission.code()).isEqualTo("import_capacity_busy");
                        assertThat(admission.retryAfterSeconds()).isPositive();
                    });

            releaseHolders.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseHolders.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void locksAreReleasedOnRollback() {
        assertThatThrownBy(() -> transaction.execute(status -> {
            StudentImportAdmissionGuard.acquire(jdbc, 404L);
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);

        Boolean admittedAfterRollback = transaction.execute(status -> {
            StudentImportAdmissionGuard.acquire(jdbc, 404L);
            return true;
        });
        assertThat(admittedAfterRollback).isEqualTo(Boolean.TRUE);
    }

    private Future<Boolean> holdSlot(
            ExecutorService executor,
            long schoolId,
            CountDownLatch holdersReady,
            CountDownLatch releaseHolders) {
        return executor.submit(() -> transaction.execute(status -> {
            StudentImportAdmissionGuard.acquire(jdbc, schoolId);
            holdersReady.countDown();
            await(releaseHolders);
            return true;
        }));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating test", ex);
        }
    }
}
