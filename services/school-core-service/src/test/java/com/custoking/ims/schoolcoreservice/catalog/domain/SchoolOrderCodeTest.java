package com.custoking.ims.schoolcoreservice.catalog.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchoolOrderCodeTest {

    @Test
    void initialsOfTheNameAndTheFirstThreeLettersOfTheCity() {
        assertThat(SchoolOrderCode.derive("Delhi Public School", "Gurugram", "Haryana")).isEqualTo("DPS_GUR");
        assertThat(SchoolOrderCode.derive("  st. mary's convent school ", "New Delhi", null)).isEqualTo("SMCS_NEW");
    }

    @Test
    void stopWordsAndAmpersandsAreIgnored() {
        assertThat(SchoolOrderCode.derive("The School of Arts and Science for Girls", "Pune", null)).isEqualTo("SASG_PUN");
        assertThat(SchoolOrderCode.derive("Ryan & Sons Academy", "Mumbai", null)).isEqualTo("RSA_MUM");
    }

    @Test
    void atMostEightInitials() {
        assertThat(SchoolOrderCode.derive("A B C D E F G H I J", "Agra", null)).isEqualTo("ABCDEFGH_AGR");
    }

    @Test
    void aOneWordNameUsesItsFirstThreeLetters() {
        assertThat(SchoolOrderCode.derive("Kendriya", "Bhopal", null)).isEqualTo("KEN_BHO");
        assertThat(SchoolOrderCode.derive("The School 21", "Bhopal", null)).isEqualTo("SCH_BHO");
        assertThat(SchoolOrderCode.derive("X", "Bhopal", null)).as("padded to two characters").isEqualTo("XX_BHO");
        assertThat(SchoolOrderCode.derive(null, null, null)).isEqualTo("XX_IN");
    }

    @Test
    void theLocationFallsBackToTheStateThenToIndia() {
        assertThat(SchoolOrderCode.derive("Delhi Public School", "", "Karnataka")).isEqualTo("DPS_KAR");
        assertThat(SchoolOrderCode.derive("Delhi Public School", "  ", null)).isEqualTo("DPS_IN");
        assertThat(SchoolOrderCode.derive("Delhi Public School", "42", "1")).as("digits are not a location").isEqualTo("DPS_IN");
    }

    @Test
    void accentsAndPunctuationAreDropped() {
        assertThat(SchoolOrderCode.derive("École Élan", "Sölden", null)).matches(SchoolOrderCode.FORMAT);
    }

    @Test
    void collisionsAppendTwoToNineThenWiderNumbersWithinEightCharacters() {
        assertThat(SchoolOrderCode.withSuffix("DPS_GUR", 2)).isEqualTo("DPS2_GUR");
        assertThat(SchoolOrderCode.withSuffix("ABCDEFGH_AGR", 9)).isEqualTo("ABCDEFG9_AGR");
        assertThat(SchoolOrderCode.withSuffix("ABCDEFGH_AGR", 12)).isEqualTo("ABCDEF12_AGR");
        Set<String> taken = Set.of("DPS_GUR", "DPS2_GUR", "DPS3_GUR");
        assertThat(SchoolOrderCode.firstFree("DPS_GUR", taken::contains)).isEqualTo("DPS4_GUR");
        assertThat(SchoolOrderCode.firstFree("KVS_GUR", taken::contains)).isEqualTo("KVS_GUR");
    }

    @Test
    void everyDerivedCodeMatchesTheFormat() {
        for (String name : new String[]{"Delhi Public School", "A", "", "The", "123", "Ryan International School Of Excellence And Arts"}) {
            assertThat(SchoolOrderCode.derive(name, "Gurugram", null)).matches(SchoolOrderCode.FORMAT);
        }
    }

    @Test
    void aTypedCodeIsTrimmedAndUpperCasedAndBlankMeansDerive() {
        assertThat(SchoolOrderCode.normalise(" dps_gur ")).isEqualTo("DPS_GUR");
        assertThat(SchoolOrderCode.normalise("  ")).isNull();
        assertThat(SchoolOrderCode.normalise(null)).isNull();
    }

    @Test
    void aTypedCodeMustMatchTheFormat() {
        for (String bad : new String[]{"DPS-GUR", "D_GUR", "DPS_G", "DPSDPSDPS_GUR", "DPS_GURGAON", "DPSGUR", "DP S_GUR"}) {
            assertThatThrownBy(() -> SchoolOrderCode.normalise(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DPS_GUR");
        }
    }
}
