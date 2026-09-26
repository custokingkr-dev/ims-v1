package com.custoking.ims.operationsservice.infrastructure;

import com.google.cloud.storage.*;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotationDocumentStorageTest {
    static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
    @Test void validatesBytesRatherThanTrustingMimeOrFilename() throws Exception {
        var storage = new QuotationDocumentStorage("");
        var file = storage.validate(png(), "../../vendor.exe", "image/png");
        assertThat(file.filename()).isEqualTo("vendor.png");
        assertThat(file.contentType()).isEqualTo("image/png");
        assertThat(file.checksumSha256()).hasSize(64);
        assertThatThrownBy(() -> storage.validate("<script/>".getBytes(), "quote.pdf", "application/pdf")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.validate(png(), "quote.pdf", "application/pdf")).hasMessageContaining("declared type");
        assertThatThrownBy(() -> storage.validate("%PDF-invalid".getBytes(StandardCharsets.US_ASCII), "quote.pdf", "application/pdf")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.validate(new byte[0], "quote.pdf", null)).hasMessageContaining("nonempty");
        assertThatThrownBy(() -> storage.validate(new byte[(int) QuotationDocumentStorage.MAX_BYTES + 1], "quote.pdf", null)).hasMessageContaining("5 MB");
    }
    @Test void acceptsValidPdfButRejectsEncryptedAndScriptedPdfs() throws Exception {
        var storage = new QuotationDocumentStorage("");
        assertThat(storage.validate(pdf(false, false), "vendor.pdf", "application/pdf").contentType()).isEqualTo("application/pdf");
        assertThatThrownBy(() -> storage.validate(pdf(true, false), "protected.pdf", "application/pdf"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("unencrypted");
        assertThatThrownBy(() -> storage.validate(pdf(false, true), "scripted.pdf", "application/pdf"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no scripts");
    }
    private byte[] pdf(boolean encrypted, boolean scripted) throws Exception {
        var output = new ByteArrayOutputStream();
        var document = new Document();
        var writer = PdfWriter.getInstance(document, output);
        if (encrypted) writer.setEncryption("reader".getBytes(StandardCharsets.UTF_8), "owner".getBytes(StandardCharsets.UTF_8),
                PdfWriter.ALLOW_PRINTING, PdfWriter.ENCRYPTION_AES_128);
        document.open();
        document.add(new Paragraph("Quotation"));
        if (scripted) writer.addJavaScript("app.alert('Quotation');");
        document.close();
        return output.toByteArray();
    }
    @Test void failsClosedForMissingOrPublicBucketAndNeverAcceptsCallerPaths() {
        var cloud = mock(Storage.class);
        var storage = new QuotationDocumentStorage("private-quotations", cloud);
        var bucket = mock(Bucket.class);
        when(cloud.get("private-quotations")).thenReturn(bucket);
        when(bucket.getIamConfiguration()).thenReturn(BucketInfo.IamConfiguration.newBuilder()
                .setIsUniformBucketLevelAccessEnabled(true).setPublicAccessPrevention(BucketInfo.PublicAccessPrevention.INHERITED).build());
        assertThatThrownBy(storage::requireAvailable).hasMessageContaining("public access prevention");
        assertThatThrownBy(() -> new QuotationDocumentStorage("").requireAvailable()).hasMessageContaining("not configured");
        assertThatThrownBy(() -> storage.read("../../secrets")).hasMessageContaining("Invalid quotation object key");
        verify(cloud, never()).get(eq("private-quotations"), anyString());
    }
    @Test void storesImmutablePrivateObjectsAndStreamsOnlyKnownKeys() throws Exception {
        var cloud = mock(Storage.class);
        var storage = new QuotationDocumentStorage("private-quotations", cloud);
        var bucket = mock(Bucket.class);
        when(cloud.get("private-quotations")).thenReturn(bucket);
        when(bucket.getIamConfiguration()).thenReturn(BucketInfo.IamConfiguration.newBuilder()
                .setIsUniformBucketLevelAccessEnabled(true).setPublicAccessPrevention(BucketInfo.PublicAccessPrevention.ENFORCED).build());
        var file = storage.validate(png(), "quote.png", "image/png");
        String key = "schools/7/firefighting/quotations/12345678-1234-1234-1234-123456789abc.png";
        storage.write(key, file);
        verify(cloud).create(argThat(info -> info.getName().equals(key) && "private, no-store".equals(info.getCacheControl())
                && "image/png".equals(info.getContentType())), eq(file.bytes()), eq(Storage.BlobTargetOption.doesNotExist()));
        var blob = mock(Blob.class);
        when(cloud.get("private-quotations", key)).thenReturn(blob);
        when(blob.getSize()).thenReturn((long) file.bytes().length);
        when(blob.getContent()).thenReturn(file.bytes());
        assertThat(storage.read(key)).isEqualTo(file.bytes());
        storage.delete(key);
        verify(cloud).delete("private-quotations", key);
    }
}
