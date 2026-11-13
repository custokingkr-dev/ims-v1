package com.custoking.ims.schoolcoreservice.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DesignDraftRulesTest {
    private static final long MB = 1024L * 1024;

    @Test
    void aFifthFileIsRefusedWhateverItsSize() {
        assertThat(DesignDraftRules.refusal(3, 0, 1)).isNull();
        assertThat(DesignDraftRules.refusal(4, 0, 1)).contains("at most 4 design files");
        assertThat(DesignDraftRules.refusal(6, 0, 1)).as("an order already over the limit takes nothing new").isNotNull();
    }

    @Test
    void theTwentyFiveMegabytesAreATotalAcrossTheOrdersFiles() {
        assertThat(DesignDraftRules.refusal(2, 20 * MB, 5 * MB)).as("exactly 25 MB in total").isNull();
        assertThat(DesignDraftRules.refusal(2, 20 * MB, 5 * MB + 1)).contains("25 MB");
        assertThat(DesignDraftRules.refusal(0, 0, 25 * MB + 1)).contains("25 MB");
    }

    @Test
    void pdfCorelDrawPngAndJpegAreTakenButWebpIsNot() {
        assertThat(DesignDraftRules.acceptedType("application/pdf")).isTrue();
        assertThat(DesignDraftRules.acceptedType("application/vnd.corel-draw")).isTrue();
        assertThat(DesignDraftRules.acceptedType("image/png")).isTrue();
        assertThat(DesignDraftRules.acceptedType("image/jpeg")).isTrue();
        assertThat(DesignDraftRules.acceptedType("image/webp")).isFalse();
    }

    @Test
    void theKindMatchesTheDatabaseConstraint() {
        assertThat(DesignDraftRules.KIND).isEqualTo("DESIGN_DRAFT");
    }
}
