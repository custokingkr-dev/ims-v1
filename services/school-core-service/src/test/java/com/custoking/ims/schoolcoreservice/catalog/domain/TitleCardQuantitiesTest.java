package com.custoking.ims.schoolcoreservice.catalog.domain;

import com.custoking.ims.schoolcoreservice.catalog.domain.TitleCardQuantities.Line;
import com.custoking.ims.schoolcoreservice.catalog.domain.TitleCardQuantities.Row;
import com.custoking.ims.schoolcoreservice.catalog.domain.TitleCardQuantities.Unmatched;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TitleCardQuantitiesTest {

    private static Map<String, Object> size(String code, String label) {
        return Map.of("SIZE", Map.of("code", code, "label", label), "RULING", Map.of("code", "SINGLE_RULE", "label", "Single rule"));
    }

    @Test
    void theClientsSampleOrderNeeds1088And513And775AndNoFaSheets() {
        var result = TitleCardQuantities.compute(List.of(
                new Line(1, size("KING", "King"), 8_600),
                new Line(2, size("LONG", "Long"), 4_000),
                new Line(3, size("JUMBO_LONG", "Jumbo Long"), 3_000)));

        assertThat(result.rows()).extracting(Row::sheets).containsExactly(1_088, 513, 775, 0);
        assertThat(result.rows()).extracting(Row::notebooks).containsExactly(8_600, 4_000, 3_000, 0);
        assertThat(result.rows()).extracting(Row::type).containsExactly("KING_JUMBO_KING", "LONG", "JUMBO_LONG", "FA");
        assertThat(result.rows()).extracting(Row::label).containsExactly("KING/JUMBO KING", "LONG", "JUMBO LONG", "FA");
        assertThat(result.rows()).extracting(Row::size).containsExactly("80*105", "72*112", "63.5*76", "23*36");
        assertThat(result.counts()).containsExactly(Map.entry("king", 8_600), Map.entry("long", 4_000), Map.entry("jumboKing", 0),
                Map.entry("jumboLong", 3_000), Map.entry("fa", 0), Map.entry("other", 0));
        assertThat(result.unmatched()).isEmpty();
    }

    @Test
    void kingAndJumboKingShareOneRowAndFaUsesTheQuarterSheetRule() {
        var result = TitleCardQuantities.compute(List.of(
                new Line(1, size("KING", "King"), 10),
                new Line(2, size("JUMBO_KING", "Jumbo King"), 7),
                new Line(3, size("FA_A4", "FA / A4 notebook"), 5)));

        assertThat(result.rows().getFirst().notebooks()).isEqualTo(17);
        assertThat(result.rows().getFirst().sheets()).as("ceil(17/8)+13").isEqualTo(16);
        assertThat(result.rows().get(3).sheets()).as("ceil(5/4)+25").isEqualTo(27);
        assertThat(result.counts()).containsEntry("king", 10).containsEntry("jumboKing", 7).containsEntry("fa", 5);
    }

    @Test
    void linesWithNoTitleCardSizeAreListedAsNotOnATitleCard() {
        var result = TitleCardQuantities.compute(List.of(
                new Line(1, size("LONG", "Long"), 100),
                new Line(2, size("DRAWING_BOOK", "Drawing book"), 40),
                new Line(3, Map.of(), 3)));

        assertThat(result.unmatched()).containsExactly(new Unmatched(2, "Drawing book", 40), new Unmatched(3, "Line 3", 3));
        assertThat(result.counts()).containsEntry("other", 43).containsEntry("long", 100);
    }

    @Test
    void aSizeWithNoNotebooksNeedsNoWastageSheets() {
        assertThat(TitleCardQuantities.sheets(0, 8, 13)).isZero();
        assertThat(TitleCardQuantities.sheets(1, 8, 13)).isEqualTo(14);
        assertThat(TitleCardQuantities.sheets(8, 8, 13)).isEqualTo(14);
        assertThat(TitleCardQuantities.sheets(9, 8, 13)).isEqualTo(15);
        assertThat(TitleCardQuantities.sheets(1, 4, 25)).isEqualTo(26);
    }

    @Test
    void plainTextSelectionsAreMatchedToo() {
        var result = TitleCardQuantities.compute(List.of(new Line(1, Map.of("SIZE", "Jumbo Long"), 4)));
        assertThat(result.rows().get(2).notebooks()).isEqualTo(4);
        assertThat(result.rows().get(2).sheets()).isEqualTo(26);
    }
}
