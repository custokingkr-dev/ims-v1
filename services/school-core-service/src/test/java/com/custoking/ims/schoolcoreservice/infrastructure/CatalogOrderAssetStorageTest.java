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

    /**
     * A truncated image answers 400. The JDK's JPEG reader decodes a truncated JPEG without an exception and only reports
     * "premature end" or "missing EOI" as warnings, so those count as damage too, for baseline and progressive JPEGs alike.
     */
    @Test
    void truncatedImageIs400() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        var picture = TestImages.picture(128);
        byte[] png = TestImages.png(picture);
        byte[] baseline = TestImages.jpeg(picture, false);
        byte[] progressive = TestImages.jpeg(picture, true);
        var truncated = new java.util.LinkedHashMap<String, byte[]>();
        // Cut inside the image data. (A PNG missing only its trailing checksum and end chunk still decodes in full, and is not damaged.)
        for (int keep : new int[]{40, png.length / 2, png.length - 200}) truncated.put("png kept " + keep, java.util.Arrays.copyOf(png, keep));
        for (int keep : new int[]{baseline.length / 2, baseline.length - 200, baseline.length - 2}) {
            truncated.put("baseline jpeg kept " + keep, java.util.Arrays.copyOf(baseline, keep));
        }
        for (int keep : new int[]{progressive.length / 2, progressive.length - 2}) {
            truncated.put("progressive jpeg kept " + keep, java.util.Arrays.copyOf(progressive, keep));
        }
        truncated.forEach((name, bytes) -> assertThatThrownBy(() -> storage.validate(bytes, "photo", "DESIGN"), name)
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode().value()).isEqualTo(400);
                    assertThat(refused.getReason()).isEqualTo(DAMAGED);
                }));
    }

    /** Whole files still pass: baseline and progressive JPEGs, a CMYK JPEG, and a JPEG whose bad colour profile the reader ignores. */
    @Test
    void validJpegsAreAccepted() {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        var picture = TestImages.picture(128);
        var valid = new java.util.LinkedHashMap<String, byte[]>();
        valid.put("baseline", TestImages.jpeg(picture, false));
        valid.put("progressive", TestImages.jpeg(picture, true));
        valid.put("cmyk", TestImages.cmykJpeg());
        valid.put("invalid colour profile", TestImages.withInvalidColourProfile(TestImages.jpeg(picture, false)));
        valid.forEach((name, bytes) -> assertThat(storage.validate(bytes, "photo.jpg", "DESIGN").contentType()).as(name).isEqualTo("image/jpeg"));
    }

    /** A valid image the decoder has no colour model for is not "damaged": the user is told what to change. */
    @Test
    void aValidImageInAnUnsupportedColourFormatIsToldSo() {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        assertThatThrownBy(() -> storage.validate(TestImages.twoComponentJpeg(), "photo.jpg", "DESIGN"))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode().value()).isEqualTo(400);
                    assertThat(refused.getReason()).isEqualTo(
                            "This image's colour format isn't supported. Save it as an RGB PNG, JPEG or WEBP and upload again.");
                });
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

    // ---- design files (item order workflows D26, D27) -----------------------------------------------------------------

    /** A CorelDRAW X3-and-earlier drawing: a RIFF container whose form type begins CDR (CDRD is X3, CDR9 is version 9). */
    static byte[] riffCorelDraw(String form) {
        byte[] bytes = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        bytes[4] = 56;
        System.arraycopy(form.getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        System.arraycopy("vrsn".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
        return bytes;
    }

    /** A ZIP container with the given entries, laid out as CorelDRAW X4 and later save a drawing. */
    static byte[] zip(String... entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(output)) {
            for (String entry : entries) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry));
                zip.write(("data of " + entry).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    @Test
    void corelDrawFilesOfBothGenerationsAreDesignFilesStoredAsCorelDraw() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        for (String form : new String[]{"CDRD", "CDR9", "cdr6"}) {
            var asset = storage.validate(riffCorelDraw(form), "artwork.cdr", "DESIGN_DRAFT", 25L * 1024 * 1024);
            assertThat(asset.contentType()).as(form).isEqualTo("application/vnd.corel-draw");
        }
        byte[] x7 = zip("mimetype", "META-INF/manifest.xml", "content/riffData.cdr", "content/data/colors.xml", "previews/thumbnail.png");
        var asset = storage.validate(x7, "Artwork Final.CDR", "DESIGN_DRAFT", 25L * 1024 * 1024);
        assertThat(asset.contentType()).isEqualTo("application/vnd.corel-draw");
        assertThat(asset.bytes()).isEqualTo(x7);
        String key = storage.store(1, "CK-9", asset);
        assertThat(storage.read(key)).as("stored and served as is, never rendered").isEqualTo(x7);
    }

    /**
     * A CorelDRAW file is checked by its header only (D27), so whatever follows the header is never looked at. Its stored
     * name must therefore end in .cdr: a RIFF/CDR header followed by a script, named .hta, .html or .bat, would otherwise be
     * saved under that name by whoever downloads it. The same holds for every detected type.
     */
    @Test
    void aStoredFileAlwaysCarriesTheExtensionOfItsDetectedType() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        long cap = 25L * 1024 * 1024;
        byte[] scripted = java.util.Arrays.copyOf(riffCorelDraw("CDR "), 200);
        byte[] script = "\r\n<script>alert(1)</script>\r\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(script, 0, scripted, 20, script.length);
        for (String[] named : new String[][]{{"Final_Artwork.exe", "Final_Artwork.exe.cdr"}, {"Final_Artwork.html", "Final_Artwork.html.cdr"},
                {"x.hta", "x.hta.cdr"}, {"invoice.pdf.bat", "invoice.pdf.bat.cdr"}, {"artwork.cdr.", "artwork.cdr"}, {"Artwork.CDR", "Artwork.CDR"},
                {"artwork", "artwork.cdr"}, {"..", "attachment.cdr"}}) {
            var asset = storage.validate(scripted, named[0], "DESIGN_DRAFT", cap);
            assertThat(asset.contentType()).isEqualTo(CatalogOrderAssetStorage.CORELDRAW);
            assertThat(asset.filename()).as(named[0]).isEqualTo(named[1]);
        }
        byte[] x7 = zip("content/riffData.cdr");
        assertThat(storage.validate(x7, "Artwork Final.cdr", "DESIGN_DRAFT", cap).filename()).isEqualTo("Artwork_Final.cdr");

        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        assertThat(storage.validate(png.toByteArray(), "proof.svg", "DESIGN_DRAFT", cap).filename()).isEqualTo("proof.svg.png");
        assertThat(storage.validate(png.toByteArray(), "proof.PNG", "DESIGN").filename()).isEqualTo("proof.PNG");
        assertThat(storage.validate(png.toByteArray(), null, "PRINT_REFERENCE").filename()).isEqualTo("attachment.png");
        var jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        assertThat(storage.validate(jpeg.toByteArray(), "photo.jpeg", "DESIGN").filename()).isEqualTo("photo.jpeg");
        assertThat(storage.validate(jpeg.toByteArray(), "photo.html", "DESIGN").filename()).isEqualTo("photo.html.jpg");
        String longName = "a".repeat(300) + ".exe";
        assertThat(storage.validate(scripted, longName, "DESIGN_DRAFT", cap).filename()).hasSize(200).endsWith(".exe.cdr");

        assertThat(CatalogOrderAssetStorage.typedFilename("legacy.html", "application/pdf")).as("served too").isEqualTo("legacy.html.pdf");
        assertThat(CatalogOrderAssetStorage.typedFilename("legacy.pdf", "application/pdf")).isEqualTo("legacy.pdf");
        assertThat(CatalogOrderAssetStorage.typedFilename(null, CatalogOrderAssetStorage.CORELDRAW)).isEqualTo("attachment.cdr");
    }

    @Test
    void aRenamedZipOrAFakeCdrIsRefusedWithAWayForward() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        long cap = 25L * 1024 * 1024;
        byte[] corelZip = zip("content/riffData.cdr");
        assertThatThrownBy(() -> storage.validate(corelZip, "artwork.zip", "DESIGN_DRAFT", cap))
                .as("a CorelDRAW-shaped ZIP not named .cdr").isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PDF export");
        byte[] plainZip = zip("readme.txt", "contents.txt");
        assertThatThrownBy(() -> storage.validate(plainZip, "artwork.cdr", "DESIGN_DRAFT", cap))
                .as("a ZIP renamed .cdr without content/").isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.validate("not a drawing at all".getBytes(StandardCharsets.UTF_8), "artwork.cdr", "DESIGN_DRAFT", cap))
                .as("a fake .cdr").isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.validate(riffCorelDraw("AVI "), "artwork.cdr", "DESIGN_DRAFT", cap))
                .as("another RIFF form").isInstanceOf(ResponseStatusException.class);
        byte[] truncated = java.util.Arrays.copyOf(corelZip, corelZip.length - 30);
        assertThatThrownBy(() -> storage.validate(truncated, "artwork.cdr", "DESIGN_DRAFT", cap))
                .as("a ZIP whose central directory is cut off").isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void webpIsNoLongerADesignFileButStaysAnArtworkUpload() {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        byte[] webp = java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        assertThatThrownBy(() -> storage.validate(webp, "draft.webp", "DESIGN_DRAFT", 25L * 1024 * 1024))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("PDF, CorelDRAW (.cdr), PNG or JPEG");
        assertThat(storage.validate(webp, "artwork.webp", "DESIGN").contentType()).isEqualTo("image/webp");
    }

    @Test
    void corelDrawIsOnlyADesignFile() {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        assertThatThrownBy(() -> storage.validate(riffCorelDraw("CDRD"), "artwork.cdr", "DESIGN"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("PNG, JPEG, WEBP, or PDF");
    }

    @Test
    void pngJpegAndPdfRemainDesignFiles() throws Exception {
        var storage = new CatalogOrderAssetStorage("", "local", directory.toString());
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        assertThat(storage.validate(png.toByteArray(), "draft.png", "DESIGN_DRAFT", 25L * 1024 * 1024).contentType()).isEqualTo("image/png");
        var pdf = new ByteArrayOutputStream();
        try (Document document = new Document()) {
            PdfWriter.getInstance(document, pdf);
            document.open();
            document.add(new Paragraph("Flier design"));
        }
        assertThat(storage.validate(pdf.toByteArray(), "draft.pdf", "DESIGN_DRAFT", 25L * 1024 * 1024).contentType()).isEqualTo("application/pdf");
    }
}
