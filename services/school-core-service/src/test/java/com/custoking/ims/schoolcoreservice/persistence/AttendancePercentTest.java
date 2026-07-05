package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendancePercentTest {

    @Test
    void oneEach_leaveExcluded_isTwoThirds() {
        // P=1, Late=1, Absent=1 (+ Leave excluded) -> attended 2 / denom 3 = 66.7
        assertThat(AttendanceReadRepository.attendancePercent(1, 1, 1)).isEqualTo(66.7);
    }

    @Test
    void lateCountsAsAttended() {
        // P=0, Late=2, Absent=0 -> 2/2 = 100
        assertThat(AttendanceReadRepository.attendancePercent(0, 2, 0)).isEqualTo(100.0);
    }

    @Test
    void allPresent_isHundred() {
        assertThat(AttendanceReadRepository.attendancePercent(3, 0, 0)).isEqualTo(100.0);
    }

    @Test
    void emptyDenominator_isZero() {
        // e.g. only Leave marked, or nobody marked -> denom 0
        assertThat(AttendanceReadRepository.attendancePercent(0, 0, 0)).isEqualTo(0.0);
    }
}
