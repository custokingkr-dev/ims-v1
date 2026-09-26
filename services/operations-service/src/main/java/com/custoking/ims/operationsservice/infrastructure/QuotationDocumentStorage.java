package com.custoking.ims.operationsservice.infrastructure;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.http.HttpTransportOptions;
import com.google.api.gax.retrying.RetrySettings;
import com.lowagie.text.pdf.PdfReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.time.Duration;

/** Private, immutable blobs. This class never issues public or signed URLs. */
@Component
public class QuotationDocumentStorage {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final List<String> CONTENT_TYPES = List.of("application/pdf", "image/jpeg", "image/png");
    private final String bucket;
    private volatile Storage storage;

    @Autowired
    public QuotationDocumentStorage(@Value("${firefighting.quotation-documents.bucket:}") String bucket) {
        this.bucket = bucket == null ? "" : bucket.trim();
    }

    QuotationDocumentStorage(String bucket, Storage storage) { this(bucket); this.storage = storage; }

    public boolean configured() { return !bucket.isBlank(); }

    /** Check actual bucket privacy before every object operation; fail closed on IAM/config errors. */
    public void requireAvailable() {
        if (!configured()) throw unavailable("Private quotation storage is not configured");
        try {
            var info = client().get(bucket);
            var iam = info == null ? null : info.getIamConfiguration();
            if (iam == null || !Boolean.TRUE.equals(iam.isUniformBucketLevelAccessEnabled())
                    || iam.getPublicAccessPrevention() != BucketInfo.PublicAccessPrevention.ENFORCED) {
                throw unavailable("Quotation storage requires uniform bucket access and enforced public access prevention");
            }
        } catch (ResponseStatusException ex) { throw ex; }
        catch (RuntimeException ex) { throw unavailable("Private quotation storage is unavailable"); }
    }

    public ValidatedDocument validate(byte[] bytes, String filename, String claimedType) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw bad("Choose a nonempty quotation file no larger than 5 MB");
        String type;
        String extension;
        if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) { type = "application/pdf"; extension = ".pdf"; }
        else if (starts(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) { type = "image/png"; extension = ".png"; }
        else if (starts(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) { type = "image/jpeg"; extension = ".jpg"; }
        else throw bad("Only valid PDF, PNG, and JPEG quotation files are accepted");
        if (claimedType != null && !claimedType.isBlank() && !"application/octet-stream".equals(claimedType)
                && !type.equalsIgnoreCase(claimedType)) throw bad("The file content does not match its declared type");
        if ("application/pdf".equals(type)) validatePdf(bytes); else validateImage(bytes);
        String safe = filename == null ? "quotation" : filename.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("^[. ]+", "");
        if (safe.isBlank()) safe = "quotation";
        // The download extension follows validated content, never an untrusted browser filename.
        safe = safe.replaceFirst("\\.[^.]*$", "");
        if (safe.length() > 180) safe = safe.substring(0, 180);
        safe += extension;
        try {
            return new ValidatedDocument(bytes, safe, type,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), extension);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public void write(String key, ValidatedDocument document) {
        requireKey(key);
        requireAvailable();
        try {
            client().create(BlobInfo.newBuilder(bucket, key).setContentType(document.contentType())
                    .setCacheControl("private, no-store").build(), document.bytes(), Storage.BlobTargetOption.doesNotExist());
        } catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Quotation file storage did not confirm the upload", ex); }
    }

    public byte[] read(String key) {
        requireKey(key);
        requireAvailable();
        try {
            var blob = client().get(bucket, key);
            if (blob == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quotation file not found");
            if (blob.getSize() == null || blob.getSize() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stored quotation file exceeds the allowed size");
            return blob.getContent();
        } catch (ResponseStatusException ex) { throw ex; }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Quotation file could not be loaded", ex); }
    }

    public void delete(String key) {
        requireKey(key);
        requireAvailable();
        client().delete(bucket, key); // Missing objects also mean cleanup is complete.
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("schools/[0-9]+/firefighting/quotations/[a-f0-9-]{36}\\.(pdf|png|jpg)")) {
            throw bad("Invalid quotation object key");
        }
    }

    private void validateImage(byte[] bytes) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw bad("The quotation image cannot be decoded");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 20_000_000L) throw bad("Quotation images must be at most 20 megapixels");
                if (reader.read(0) == null) throw bad("The quotation image cannot be decoded");
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException ex) { throw bad("Choose a valid PNG or JPEG quotation image"); }
    }

    private void validatePdf(byte[] bytes) {
        try (var reader = new PdfReader(bytes)) {
            if (reader.isEncrypted() || reader.getNumberOfPages() < 1 || reader.getNumberOfPages() > 200
                    || (reader.getJavaScript() != null && !reader.getJavaScript().isBlank())) {
                throw bad("Choose an unencrypted PDF with 1–200 pages and no scripts");
            }
        } catch (IOException | IllegalArgumentException ex) { throw bad("Choose a valid, unencrypted quotation PDF"); }
    }

    private Storage client() {
        if (storage == null) synchronized (this) {
            if (storage == null) storage = StorageOptions.http()
                    .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(3000).setReadTimeout(5000).build())
                    .setRetrySettings(RetrySettings.newBuilder()
                            .setTotalTimeoutDuration(Duration.ofSeconds(10)).setMaxAttempts(2)
                            .setInitialRpcTimeoutDuration(Duration.ofSeconds(5)).setMaxRpcTimeoutDuration(Duration.ofSeconds(5)).setRpcTimeoutMultiplier(1)
                            .setInitialRetryDelayDuration(Duration.ofMillis(200)).setMaxRetryDelayDuration(Duration.ofSeconds(1)).setRetryDelayMultiplier(2)
                            .build()).build().getService();
        }
        return storage;
    }
    private static boolean starts(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException unavailable(String message) { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message); }
    public record ValidatedDocument(byte[] bytes, String filename, String contentType, String checksumSha256, String extension) {}
}
