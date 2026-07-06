package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the fail-loud section school resolution helper
 * ({@link AttendanceReadRepository#requireSectionSchool}).
 *
 * These are pure in-memory tests — no database required.
 */
class AttendanceReadRepositoryFailLoudTest {

    private final AttendanceReadRepository repo =
            new AttendanceReadRepository(mock(JdbcClient.class), mock(StudentPhotoStorage.class),
                    mock(OutboxWriter.class), "attendance");

    @Test
    void throws_whenSchoolIdAbsent() {
        Map<String, Object> section = Map.of(); // no schoolId key
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> repo.requireSectionSchool(section, "sec-missing"));
        assertTrue(ex.getMessage().contains("sec-missing"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no owning school_id"), ex.getMessage());
    }

    @Test
    void throws_whenSchoolIdIsZero() {
        Map<String, Object> section = Map.of("schoolId", 0L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> repo.requireSectionSchool(section, "sec-zero"));
        assertTrue(ex.getMessage().contains("sec-zero"), ex.getMessage());
    }

    @Test
    void throws_whenSchoolIdIsNegative() {
        Map<String, Object> section = Map.of("schoolId", -5L);
        assertThrows(IllegalStateException.class,
                () -> repo.requireSectionSchool(section, "sec-neg"));
    }

    @Test
    void returns_whenSchoolIdIsPositive() {
        Map<String, Object> section = Map.of("schoolId", 42L);
        Long result = repo.requireSectionSchool(section, "sec-ok");
        assertEquals(42L, result);
    }
}
