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
}
