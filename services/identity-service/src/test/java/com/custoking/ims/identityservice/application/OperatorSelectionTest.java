package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.application.OperatorSelection.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperatorSelectionTest {

    @Test
    void aSingleItemSpecificOperatorIsPickedEvenWhenSchoolWideOperatorsExist() {
        var result = OperatorSelection.choose(List.of(7L), List.of(1L, 2L));
        assertEquals(Outcome.ITEM_SPECIFIC, result.outcome());
        assertEquals(7L, result.userId());
    }

    @Test
    void severalItemSpecificOperatorsPickNobodyAndSuperadminAssigns() {
        var result = OperatorSelection.choose(List.of(9L, 7L), List.of(1L));
        assertEquals(Outcome.AMBIGUOUS, result.outcome());
        assertNull(result.userId());
        assertEquals(List.of(7L, 9L), result.candidates());
    }

    @Test
    void withNoItemSpecificOperatorASingleSchoolWideOperatorIsTheFallback() {
        var result = OperatorSelection.choose(List.of(), List.of(4L));
        assertEquals(Outcome.SCHOOL_WIDE, result.outcome());
        assertEquals(4L, result.userId());
    }

    @Test
    void severalSchoolWideOperatorsAreAmbiguousToo() {
        var result = OperatorSelection.choose(List.of(), List.of(4L, 5L));
        assertEquals(Outcome.AMBIGUOUS, result.outcome());
        assertNull(result.userId());
        assertEquals(List.of(4L, 5L), result.candidates());
    }

    @Test
    void noOperatorAtAllIsNone() {
        var result = OperatorSelection.choose(List.of(), List.of());
        assertEquals(Outcome.NONE, result.outcome());
        assertNull(result.userId());
        assertTrue(result.candidates().isEmpty());
        assertEquals(Outcome.NONE, OperatorSelection.choose(null, null).outcome());
    }

    @Test
    void theSameUserListedTwiceCountsOnce() {
        assertEquals(Outcome.ITEM_SPECIFIC, OperatorSelection.choose(List.of(7L, 7L), List.of()).outcome());
        assertEquals(Outcome.SCHOOL_WIDE, OperatorSelection.choose(List.of(), List.of(4L, 4L)).outcome());
    }

    @Test
    void categoryCodesAreUpperCasedValidatedAndBlankMeansNone() {
        assertEquals("NOTEBOOKS", CatalogScope.normalize("  notebooks "));
        assertEquals("REPORT_CARDS", CatalogScope.normalize("report_cards"));
        assertNull(CatalogScope.normalize(null));
        assertNull(CatalogScope.normalize("   "));
        for (String bad : new String[]{"1BAD", "A", "HAS SPACE", "semi;colon", "x".repeat(41), "dash-ed"}) {
            assertThrows(IllegalArgumentException.class, () -> CatalogScope.normalize(bad), bad);
        }
        assertEquals("4:NOTEBOOKS", CatalogScope.key(4, "NOTEBOOKS"));
    }
}
