package com.custoking.ims.schoolcoreservice.catalog.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderNumbersTest {

    @Test
    void theNumberIsItemSchoolPlacementDateAndAFourDigitRunningNumber() {
        assertThat(OrderNumbers.format("NB", "DPS_GUR", LocalDate.of(2026, 11, 13), 42)).isEqualTo("NB-DPS_GUR-26-11-13-0042");
        assertThat(OrderNumbers.format("BBK", "SMCS2_DEL", LocalDate.of(2027, 1, 5), 1)).isEqualTo("BBK-SMCS2_DEL-27-01-05-0001");
    }

    @Test
    void halfPastMidnightInIndiaIsStillTodayThoughUtcIsYesterday() {
        Instant placed = Instant.parse("2026-11-12T19:00:00Z"); // 00:30 IST on the 13th
        assertThat(OrderNumbers.placementDate(placed, "Asia/Kolkata")).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(OrderNumbers.placementDate(placed, "UTC")).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(OrderNumbers.format("NB", "DPS_GUR", OrderNumbers.placementDate(placed, "Asia/Kolkata"), 7))
                .isEqualTo("NB-DPS_GUR-26-11-13-0007");
    }

    @Test
    void theYearOfTheCounterIsTheYearOfTheLocalDate() {
        Instant newYearsEve = Instant.parse("2026-12-31T19:00:00Z"); // 00:30 IST on 1 January 2027
        assertThat(OrderNumbers.counterYear(OrderNumbers.placementDate(newYearsEve, "Asia/Kolkata"))).isEqualTo(2027);
    }

    @Test
    void aMissingOrUnknownTimeZoneFallsBackToIndia() {
        Instant placed = Instant.parse("2026-11-12T19:00:00Z");
        assertThat(OrderNumbers.placementDate(placed, null)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(OrderNumbers.placementDate(placed, "Not/AZone")).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void theRunningNumberWidensPastNineThousandNineHundredNinetyNineAndStaysUnique() {
        LocalDate day = LocalDate.of(2026, 11, 13);
        assertThat(OrderNumbers.format("FE", "DPS_GUR", day, 9999)).isEqualTo("FE-DPS_GUR-26-11-13-9999");
        assertThat(OrderNumbers.format("FE", "DPS_GUR", day, 10000)).isEqualTo("FE-DPS_GUR-26-11-13-10000");
        assertThat(OrderNumbers.format("FE", "DPS_GUR", day, 10001)).isNotEqualTo(OrderNumbers.format("FE", "DPS_GUR", day, 1000));
    }

    @Test
    void theLongestCodesStillFitTheColumnAndSplitCleanlyOnHyphens() {
        String number = OrderNumbers.format("ABCD", "ABCDEFGH_ABCDEF", LocalDate.of(2026, 11, 13), 12345);
        assertThat(number).hasSizeLessThanOrEqualTo(40);
        assertThat(number.split("-")).containsExactly("ABCD", "ABCDEFGH_ABCDEF", "26", "11", "13", "12345");
    }

    @Test
    void invalidPartsAreRefused() {
        LocalDate day = LocalDate.of(2026, 11, 13);
        assertThatThrownBy(() -> OrderNumbers.format("nb", "DPS_GUR", day, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderNumbers.format("NB", "DPS-GUR", day, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderNumbers.format("NB", "DPS_GUR", day, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lookupsAcceptOnlyCharactersANumberCanHold() {
        assertThat(OrderNumbers.LOOKUP.matcher("nb-dps_gur-26-11-13-0042").matches()).isTrue();
        assertThat(OrderNumbers.LOOKUP.matcher("NB/../x").matches()).isFalse();
    }
}
