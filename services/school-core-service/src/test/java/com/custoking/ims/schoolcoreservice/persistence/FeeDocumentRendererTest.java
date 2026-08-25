package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FeeDocumentRendererTest {

    private final FeeDocumentRenderer renderer = new FeeDocumentRenderer();

    @Test
    void rendersValidSinglePagePdfAndEscapesPdfText() {
        String pdf = new String(renderer.render("Receipt (A\\B) – paid"), StandardCharsets.US_ASCII);

        assertThat(pdf)
                .startsWith("%PDF-1.4\n")
                .contains("(Receipt \\(A\\\\B\\)   paid) Tj")
                .contains("xref\n0 6")
                .contains("trailer<< /Size 6 /Root 1 0 R >>")
                .endsWith("%%EOF");
    }

    @Test
    void rendersBlankContentWithoutNullText() {
        String pdf = new String(renderer.render(null), StandardCharsets.US_ASCII);

        assertThat(pdf).contains("BT /F1 12 Tf 36 740 Td () Tj ET");
    }
}
