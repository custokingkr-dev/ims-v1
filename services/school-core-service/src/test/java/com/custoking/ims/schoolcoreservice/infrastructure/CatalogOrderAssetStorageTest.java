package com.custoking.ims.schoolcoreservice.infrastructure;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogOrderAssetStorageTest {
    @TempDir Path directory;

    @Test
    void detectsContentSanitizesNamesAndReadsPrivateLocalObjects() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var asset = storage.validate(bytes.toByteArray(), "../drawing<script>.jpg", "DESIGN");
        assertThat(asset.contentType()).isEqualTo("image/png");
        assertThat(asset.filename()).doesNotContain("/", "<", ">");
        assertThat(asset.checksumSha256()).hasSize(64);
        String key = storage.store(42, "CK-12", asset);
        assertThat(key).startsWith("schools/42/catalog-orders/CK-12/");
        assertThat(storage.read(key)).isEqualTo(bytes.toByteArray());
        storage.deleteUncommitted(key);
        assertThatThrownBy(() -> storage.read(key)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void rejectsSpoofedTruncatedAndOversizedFiles() {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        assertThatThrownBy(() -> storage.validate("<script>bad</script>".getBytes(StandardCharsets.UTF_8), "photo.png", "DESIGN"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.validate(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "photo.jpg", "DESIGN"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.validate("%PDF-1.4\ninvalid".getBytes(StandardCharsets.US_ASCII), "design.pdf", "DESIGN"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.validate(new byte[5 * 1024 * 1024 + 1], "design.png", "DESIGN"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsParsedPdfArtworkButRequiresImageForDelivery() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        var output = new ByteArrayOutputStream();
        try (Document document = new Document()) {
            PdfWriter.getInstance(document, output);
            document.open();
            document.add(new Paragraph("Notebook design"));
        }
        assertThat(storage.validate(output.toByteArray(), "design.pdf", "DESIGN").contentType()).isEqualTo("application/pdf");
        assertThatThrownBy(() -> storage.validate(output.toByteArray(), "delivery.pdf", "PRE_DELIVERY_PHOTO"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("photo");
    }

    static final String DAMAGED = "This file is damaged or is not a valid PDF or image.";

    /** A PDF that cannot be parsed answers 400 with one plain message, never a 500 from the parser (a page-less PDF used to NPE). */
    @Test
    void damagedPdfIs400() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        var valid = new ByteArrayOutputStream();
        try (Document document = new Document()) {
            PdfWriter.getInstance(document, valid);
            document.open();
            document.add(new Paragraph("Notebook design"));
        }
        byte[] whole = valid.toByteArray();
        var damaged = new java.util.LinkedHashMap<String, byte[]>();
        damaged.put("no page tree", """
                %PDF-1.4
                1 0 obj << /Type /Catalog >> endobj
                trailer << /Root 1 0 R >>
                %%EOF
                """.getBytes(StandardCharsets.US_ASCII));
        damaged.put("truncated", java.util.Arrays.copyOf(whole, whole.length / 2));
        damaged.put("garbage after the header", "%PDF-1.4\ninvalid".getBytes(StandardCharsets.US_ASCII));
        damaged.forEach((name, bytes) -> assertThatThrownBy(() -> storage.validate(bytes, "design.pdf", "DESIGN"), name)
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode().value()).isEqualTo(400);
                    assertThat(refused.getReason()).isEqualTo(DAMAGED);
                }));
    }

    @Test
    void truncatedImageIs400() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        var png = new ByteArrayOutputStream();
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) image.setRGB(x, y, x * 4099 + y * 131);
        ImageIO.write(image, "png", png);
        byte[] whole = png.toByteArray();
        // Cut inside the image data. (A PNG missing only its trailing checksum and end chunk still decodes in full, and is not damaged.)
        for (int keep : new int[]{40, whole.length / 2, whole.length - 200}) {
            byte[] truncated = java.util.Arrays.copyOf(whole, keep);
            assertThatThrownBy(() -> storage.validate(truncated, "photo.png", "DESIGN"), "kept " + keep + " bytes")
                    .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                        assertThat(refused.getStatusCode().value()).isEqualTo(400);
                        assertThat(refused.getReason()).isEqualTo(DAMAGED);
                    });
        }
    }

    @Test
    void rejectsTraversalAndMissingCloudConfiguration() {
        var local = new CatalogOrderAssetStorage("", "local", directory.toString());
        assertThatThrownBy(() -> local.read("../../outside.txt")).isInstanceOf(ResponseStatusException.class);
        var cloud = new CatalogOrderAssetStorage("", "gcs", directory.toString());
        assertThatThrownBy(() -> cloud.read("schools/42/catalog-orders/CK-12/design.pdf"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
    }

    @Test
    void decodesWebpWithoutReencodingTheArtwork() {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        byte[] webp = java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        var asset = storage.validate(webp, "artwork.webp", "DESIGN");
        assertThat(asset.contentType()).isEqualTo("image/webp");
        assertThat(asset.bytes()).isEqualTo(webp);
    }

    @Test void rejectsPdfOpenActionsEvenWithoutDocumentJavaScript() throws Exception {
        var output = new ByteArrayOutputStream();
        try (Document document = new Document()) {
            var writer = PdfWriter.getInstance(document, output);
            document.open(); document.add(new Paragraph("Harmless fixture"));
            writer.setOpenAction(com.lowagie.text.pdf.PdfAction.javaScript("void(0)", writer));
        }
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        assertThatThrownBy(() -> storage.validate(output.toByteArray(), "action.pdf", "DESIGN"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("permitted");
    }

    @Test void rejectsSymbolicParentsAndEncodedTraversal() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        assertThatThrownBy(() -> storage.read("%2e%2e/outside")).isInstanceOf(ResponseStatusException.class);
        var outside = java.nio.file.Files.createDirectory(directory.resolveSibling(directory.getFileName() + "-outside"));
        try {
            java.nio.file.Files.writeString(outside.resolve("sensitive.txt"), "harmless");
            try { java.nio.file.Files.createSymbolicLink(directory.resolve("linked"), outside); }
            catch (java.nio.file.FileSystemException ex) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "OS symlink privilege unavailable"); }
            assertThatThrownBy(() -> storage.read("linked/sensitive.txt")).isInstanceOf(ResponseStatusException.class);
        } finally {
            java.nio.file.Files.deleteIfExists(outside.resolve("sensitive.txt"));
            java.nio.file.Files.deleteIfExists(outside);
        }
    }

    @Test void rejectsNestedPdfActionsAndLegacyActiveDocumentsOnRead() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var document = new Document()) {
            var writer = PdfWriter.getInstance(document, output);
            document.open(); document.add(new Paragraph("Harmless nested fixture"));
            var action = new com.lowagie.text.pdf.PdfDictionary();
            action.put(com.lowagie.text.pdf.PdfName.S, new com.lowagie.text.pdf.PdfName("Launch"));
            writer.getExtraCatalog().put(new com.lowagie.text.pdf.PdfName("NestedTest"), action);
        }
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        assertThatThrownBy(() -> storage.validate(output.toByteArray(), "nested.pdf", "DESIGN"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("permitted");
        java.nio.file.Files.write(directory.resolve("legacy.pdf"), output.toByteArray());
        assertThatThrownBy(() -> storage.read("legacy.pdf")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("permitted");
    }
}
