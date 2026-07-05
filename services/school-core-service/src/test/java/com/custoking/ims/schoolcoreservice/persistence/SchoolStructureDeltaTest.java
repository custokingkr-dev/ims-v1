package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SchoolStructureDeltaTest {

    @Test
    void sectionLetter_mapsIndexToLetter() {
        assertThat(SchoolStructureDelta.sectionLetter(0)).isEqualTo("A");
        assertThat(SchoolStructureDelta.sectionLetter(2)).isEqualTo("C");
    }

    @Test
    void letterIndex_parsesSingleLetterCaseInsensitive() {
        assertThat(SchoolStructureDelta.letterIndex("A")).isEqualTo(0);
        assertThat(SchoolStructureDelta.letterIndex("c")).isEqualTo(2);
        assertThat(SchoolStructureDelta.letterIndex("AB")).isEqualTo(-1);
        assertThat(SchoolStructureDelta.letterIndex(null)).isEqualTo(-1);
    }

    @Test
    void activeLetters_returnsFirstNLetters() {
        assertThat(SchoolStructureDelta.activeLetters(1)).containsExactly("A");
        assertThat(SchoolStructureDelta.activeLetters(3)).containsExactly("A", "B", "C");
    }

    @Test
    void droppedLetters_returnsExistingLettersAtOrBeyondNewCount() {
        assertThat(SchoolStructureDelta.droppedLetters(List.of("A", "B", "C", "D"), 2))
                .containsExactly("C", "D");
        assertThat(SchoolStructureDelta.droppedLetters(List.of("A", "B"), 3)).isEmpty();
        assertThat(SchoolStructureDelta.droppedLetters(List.of("A", "C", "C"), 1))
                .containsExactly("C");
    }
}
