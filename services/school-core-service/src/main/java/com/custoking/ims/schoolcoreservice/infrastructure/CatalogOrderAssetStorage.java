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
    /** No seeded rule may raise the cap past what the assets table itself accepts. */
    public static final long ABSOLUTE_MAX_BYTES = 10L * 1024 * 1024;
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
        return validate(bytes, originalFilename, assetKind, MAX_BYTES);
    }

    /**
     * The cap is per upload rather than global: the flex prototype accepts a 10 MB PDF where every
     * image field accepts 5 MB, and the seeded rule carries the limit for its own category.
     */
    public ValidatedAsset validate(byte[] bytes, String originalFilename, String assetKind, long maxBytes) {
        long cap = Math.min(maxBytes > 0 ? maxBytes : MAX_BYTES, ABSOLUTE_MAX_BYTES);
        if (bytes == null || bytes.length == 0 || bytes.length > cap) {
            throw bad("Upload a nonempty file of " + (cap / (1024 * 1024)) + " MB or smaller");
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
        if (filename.isBlank() || filename.equals(".") || filename.equals("..")) filename = "attachment";
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
                Files.write(file, asset.bytes(), StandardOpenOption.CREATE_NEW, java.nio.file.LinkOption.NOFOLLOW_LINKS);
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
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
                try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    byte[] bytes = input.readNBytes((int) ABSOLUTE_MAX_BYTES + 1);
                    if (bytes.length > ABSOLUTE_MAX_BYTES) throw bad("Attachment exceeds the size limit");
                    return validateStoredDocument(bytes);
                }
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load the order attachment", ex);
            }
        }
        try {
            var blob = storage().get(bucket, key);
            if (blob == null || !blob.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
            if (blob.getSize() == null || blob.getSize() > ABSOLUTE_MAX_BYTES) throw bad("Attachment exceeds the size limit");
            return validateStoredDocument(blob.getContent());
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
        try (var budget = MediaWorkBudget.acquire();
             ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
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
        try (var budget = MediaWorkBudget.acquire(); PdfReader reader = new PdfReader(bytes)) {
            if (reader.isEncrypted() || reader.getNumberOfPages() < 1 || reader.getNumberOfPages() > 200) {
                throw bad("Upload an unencrypted PDF with 1 to 200 pages");
            }
            if (reader.getJavaScript() != null && !reader.getJavaScript().isBlank()) throw bad("PDF scripts are not permitted");
            var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<com.lowagie.text.pdf.PdfObject, Boolean>());
            for (int index = 1; index < reader.getXrefSize(); index++) inspectPdfObject(reader.getPdfObject(index), visited, 0);
        } catch (IOException | IllegalArgumentException ex) {
            throw bad("Upload a valid, unencrypted PDF");
        }
    }

    private byte[] validateStoredDocument(byte[] bytes) {
        if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) validatePdf(bytes);
        return bytes;
    }

    private void inspectPdfObject(com.lowagie.text.pdf.PdfObject input, java.util.Set<com.lowagie.text.pdf.PdfObject> visited, int depth) {
        var object = PdfReader.getPdfObject(input);
        if (object == null || !visited.add(object)) return;
        if (depth > 100 || visited.size() > 100_000) throw bad("PDF structure exceeds the complexity limit");
        var forbidden = java.util.Set.of("JavaScript", "JS", "OpenAction", "AA", "Launch", "EmbeddedFiles", "EF", "Filespec", "RichMedia", "XFA", "SubmitForm", "ImportData", "GoToR", "Rendition", "RichMediaExecute");
        if (object instanceof com.lowagie.text.pdf.PdfName name && forbidden.contains(com.lowagie.text.pdf.PdfName.decodeName(name.toString())))
            throw bad("PDF active content or attachments are not permitted");
        if (object instanceof com.lowagie.text.pdf.PdfDictionary dictionary) {
            for (var key : dictionary.getKeys()) {
                if (forbidden.contains(com.lowagie.text.pdf.PdfName.decodeName(key.toString()))) throw bad("PDF active content or attachments are not permitted");
                inspectPdfObject(dictionary.get(key), visited, depth + 1);
            }
        } else if (object instanceof com.lowagie.text.pdf.PdfArray array) {
            for (int index = 0; index < array.size(); index++) inspectPdfObject(array.getPdfObject(index), visited, depth + 1);
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
        if (key == null || key.indexOf('\0') >= 0 || key.contains("%") || key.contains("\\")) throw bad("Invalid attachment path");
        Path target = localDirectory.resolve(key).normalize();
        if (!target.startsWith(localDirectory) || target.equals(localDirectory)) throw bad("Invalid attachment path");
        // Local mode requires an isolated directory owned only by this process. Reject every
        // existing symbolic parent and final link, including links leading back inside the root.
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw bad("Invalid attachment path");
        }
        try {
            if (Files.exists(localDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Path realRoot = localDirectory.toRealPath();
                for (Path current = target; current != null && current.startsWith(localDirectory); current = current.getParent()) {
                    if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().startsWith(realRoot))
                        throw bad("Invalid attachment path"); // Also rejects Windows junction/reparse parent escapes.
                }
            }
        } catch (IOException ex) { throw bad("Invalid attachment path"); }
        return target;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record ValidatedAsset(byte[] bytes, String contentType, String filename, String checksumSha256) {}
}
