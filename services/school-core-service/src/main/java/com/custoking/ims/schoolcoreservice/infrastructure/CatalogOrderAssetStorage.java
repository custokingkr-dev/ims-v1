package com.custoking.ims.schoolcoreservice.infrastructure;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.lowagie.text.pdf.PdfReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Private immutable order documents; authorization is checked before storage access. */
@Component
public class CatalogOrderAssetStorage {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private final String bucket;
    private final boolean local;
    private final Path localDirectory;
    private volatile Storage storage;

    @Autowired
    public CatalogOrderAssetStorage(@Value("${catalog.assets.bucket:${student.photo.bucket:}}") String bucket,
                                    @Value("${catalog.assets.mode:gcs}") String mode,
                                    @Value("${catalog.assets.local-directory:.data/catalog-assets}") String localDirectory) {
        this.bucket = bucket == null ? "" : bucket.trim();
        if (!"local".equals(mode) && !"gcs".equals(mode)) throw new IllegalArgumentException("Unsupported catalog asset storage mode");
        this.local = "local".equals(mode);
        this.localDirectory = Path.of(localDirectory).toAbsolutePath().normalize();
    }

    public ValidatedAsset validate(byte[] bytes, String originalFilename, String assetKind) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw bad("Upload a nonempty file of 5 MB or smaller");
        }
        String contentType;
        if (starts(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) contentType = "image/png";
        else if (starts(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) contentType = "image/jpeg";
        else if (bytes.length >= 12 && starts(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII))) contentType = "image/webp";
        else if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) contentType = "application/pdf";
        else throw bad("Only valid PNG, JPEG, WEBP, or PDF files are accepted");
        if ("PRE_DELIVERY_PHOTO".equals(assetKind) && "application/pdf".equals(contentType)) {
            throw bad("Pre-delivery evidence must be a PNG, JPEG, or WEBP photo");
        }
        if ("application/pdf".equals(contentType)) validatePdf(bytes);
        else validateImage(bytes);
        String filename = originalFilename == null ? "attachment" : originalFilename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (filename.length() > 200) filename = filename.substring(filename.length() - 200);
        if (filename.isBlank()) filename = "attachment";
        try {
            return new ValidatedAsset(bytes, contentType, filename,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String store(long schoolId, String orderId, ValidatedAsset asset) {
        requireConfigured();
        String key = "schools/" + schoolId + "/catalog-orders/" + orderId.replaceAll("[^A-Za-z0-9._-]", "_")
                + "/" + UUID.randomUUID() + "/" + asset.filename();
        if (local) {
            try {
                Path file = localPath(key);
                Files.createDirectories(file.getParent());
                Files.write(file, asset.bytes(), StandardOpenOption.CREATE_NEW);
                return key;
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not store the order attachment", ex);
            }
        }
        try {
            storage().create(BlobInfo.newBuilder(bucket, key).setContentType(asset.contentType())
                    .setCacheControl("private, no-store").build(), asset.bytes());
            return key;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not store the order attachment", ex);
        }
    }

    public byte[] read(String key) {
        requireConfigured();
        if (local) {
            try {
                Path file = localPath(key);
                if (!Files.isRegularFile(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
                return Files.readAllBytes(file);
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load the order attachment", ex);
            }
        }
        try {
            var blob = storage().get(bucket, key);
            if (blob == null || !blob.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
            return blob.getContent();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load the order attachment", ex);
        }
    }

    public void deleteUncommitted(String key) {
        if (local) {
            try { Files.deleteIfExists(localPath(key)); } catch (IOException ignored) { /* Cleanup is retryable. */ }
            return;
        }
        if (key != null && key.startsWith("schools/") && key.contains("/catalog-orders/") && !bucket.isBlank()) {
            try { storage().delete(bucket, key); } catch (RuntimeException ignored) { /* Orphan cleanup can retry. */ }
        }
    }

    private void validateImage(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw bad("The image cannot be decoded");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 40_000_000L) throw bad("Image dimensions exceed the 40 megapixel limit");
                if (reader.read(0) == null) throw bad("The image cannot be decoded");
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException ex) {
            throw bad("Upload a valid PNG, JPEG, or WEBP image");
        }
    }

    private void validatePdf(byte[] bytes) {
        try (PdfReader reader = new PdfReader(bytes)) {
            if (reader.isEncrypted() || reader.getNumberOfPages() < 1 || reader.getNumberOfPages() > 200) {
                throw bad("Upload an unencrypted PDF with 1 to 200 pages");
            }
            if (reader.getJavaScript() != null && !reader.getJavaScript().isBlank()) throw bad("PDF scripts are not permitted");
        } catch (IOException | IllegalArgumentException ex) {
            throw bad("Upload a valid, unencrypted PDF");
        }
    }

    private static boolean starts(byte[] input, byte[] signature) {
        if (input.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (input[i] != signature[i]) return false;
        return true;
    }

    private Storage storage() {
        if (storage == null) synchronized (this) {
            if (storage == null) storage = StorageOptions.getDefaultInstance().getService();
        }
        return storage;
    }

    private void requireConfigured() {
        if (!local && bucket.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order attachment storage is not configured");
    }

    private Path localPath(String key) {
        Path target = localDirectory.resolve(key).normalize();
        if (!target.startsWith(localDirectory) || target.equals(localDirectory)) throw bad("Invalid attachment path");
        return target;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ValidatedAsset(byte[] bytes, String contentType, String filename, String checksumSha256) {}
}
