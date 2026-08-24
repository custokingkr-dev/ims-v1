package com.custoking.ims.schoolcoreservice.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceStorageHealthReporterTest {

    @Test
    void firstSampleEstablishesBaselineWithoutInventingHistoricalScans() {
        var current = sample(10_000_000, 700, 70_000_000, 2_000);

        var interval = AttendanceStorageHealthReporter.interval(null, current);

        assertThat(interval.baseline()).isTrue();
        assertThat(interval.sequentialScans()).isZero();
        assertThat(interval.sequentialTuplesRead()).isZero();
        assertThat(interval.indexScans()).isZero();
        assertThat(interval.fullTableScanEquivalentsMilli()).isZero();
    }

    @Test
    void intervalReportsFullTableScanEquivalentsAndIndexTraffic() {
        var previous = sample(10_000_000, 700, 70_000_000, 2_000);
        var current = sample(10_000_000, 703, 90_000_000, 2_250);

        var interval = AttendanceStorageHealthReporter.interval(previous, current);

        assertThat(interval.sequentialScans()).isEqualTo(3);
        assertThat(interval.sequentialTuplesRead()).isEqualTo(20_000_000);
        assertThat(interval.indexScans()).isEqualTo(250);
        assertThat(interval.fullTableScanEquivalentsMilli()).isEqualTo(2_000);
        assertThat(interval.statisticsResetDetected()).isFalse();
    }

    @Test
    void statisticsResetNeverEmitsNegativeDeltasOrFalseScanRisk() {
        var previous = sample(10_000_000, 700, 70_000_000, 2_000);
        var current = sample(10_100_000, 4, 300_000, 20);

        var interval = AttendanceStorageHealthReporter.interval(previous, current);

        assertThat(interval.statisticsResetDetected()).isTrue();
        assertThat(interval.sequentialScans()).isZero();
        assertThat(interval.sequentialTuplesRead()).isZero();
        assertThat(interval.indexScans()).isZero();
        assertThat(interval.fullTableScanEquivalentsMilli()).isZero();
    }

    @Test
    void smallTableSequentialScansRemainBelowTheGrowthAlertFloor() {
        var previous = sample(50_000, 10, 500_000, 100);
        var current = sample(50_000, 20, 1_000_000, 110);

        var interval = AttendanceStorageHealthReporter.interval(previous, current);

        assertThat(interval.sequentialScans()).isEqualTo(10);
        assertThat(interval.sequentialTuplesRead()).isEqualTo(500_000);
        assertThat(interval.fullTableScanEquivalentsMilli()).isZero();
    }

    private static AttendanceStorageHealthReporter.Sample sample(
            long rows, long sequentialScans, long sequentialTuplesRead, long indexScans) {
        return new AttendanceStorageHealthReporter.Sample(
                rows, 1_000_000, 2_000_000, 3_000_000,
                sequentialScans, sequentialTuplesRead, indexScans);
    }
}
