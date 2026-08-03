package com.custoking.ims.schoolcoreservice.infrastructure;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores student photos in a private Cloud Storage bucket and serves them via short-lived V4
 * signed URLs. Photos are faces of minors (sensitive PII), so the bucket stays private-only.
 *
 * <p>Uploads are resized/compressed to a small JPEG (the cost + latency lever); objects are
 * content-addressed and written with an immutable long cache header, so browsers cache them and
 * only the signed URL's TTL gates the first fetch. Cloud Run service accounts have no local
 * private key, so URLs are signed via the IAM SignBlob API using {@link ImpersonatedCredentials}
 * self-impersonation (the runtime SA needs {@code roles/iam.serviceAccountTokenCreator} on itself).
 *
 * <p>Degrades gracefully when no bucket is configured (local/tests): {@link #toDisplayUrl} returns
 * the stored value unchanged and {@link #upload} fails with a clear 503.
 */
@Component
public class StudentPhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(StudentPhotoStorage.class);
    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";
    private static final long MAX_DECODED_PIXELS = 40_000_000L;

    private final String bucket;
    private final int ttlMinutes;
    private final int dimension;
    private final long maxBytes;
    private final String configuredSignerSa;

    private volatile Storage storage;
    private volatile ImpersonatedCredentials signer;
    private volatile String signerSa;

    public StudentPhotoStorage(
            @Value("${student.photo.bucket:}") String bucket,
            @Value("${student.photo.signed-url-ttl-minutes:60}") int ttlMinutes,
            @Value("${student.photo.dimension:512}") int dimension,
            @Value("${student.photo.max-bytes:5242880}") long maxBytes,
            @Value("${student.photo.signer-sa:}") String signerSa) {
        this.bucket = bucket == null ? "" : bucket.trim();
        this.ttlMinutes = ttlMinutes > 0 ? ttlMinutes : 60;
        this.dimension = dimension > 0 ? dimension : 512;
        this.maxBytes = maxBytes > 0 ? maxBytes : 2L * 1024 * 1024;
        this.configuredSignerSa = signerSa == null ? "" : signerSa.trim();
    }

    public boolean isEnabled() {
        return StringUtils.hasText(bucket);
    }

    /** Validate + resize + store the image; returns the GCS object key to persist. */
    public String upload(String schoolStorageId, long studentId, byte[] data, String contentType) {
        return upload(schoolStorageId, studentId, data, contentType, 0.5, 0.5);
    }

    public String upload(
            String schoolStorageId,
            long studentId,
            byte[] data,
            String contentType,
            double cropX,
            double cropY) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured");
        }
        String folder = requireStorageFolder(schoolStorageId);
        byte[] resized = normalizePortrait(data, contentType, cropX, cropY);
        return uploadNormalizedPortrait(folder, studentId, resized);
    }

    public String uploadNormalizedPortrait(String schoolStorageId, long studentId, byte[] jpegData) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured");
        }
        String folder = requireStorageFolder(schoolStorageId);
        if (jpegData == null || jpegData.length == 0) {
            throw new IllegalArgumentException("The normalized photo file is empty");
        }
        if (jpegData.length > maxBytes) {
            throw new IllegalArgumentException(
                    "Normalized photo must be " + (maxBytes / (1024 * 1024)) + " MB or smaller");
        }
        String key = studentPhotoObjectKey(folder, studentId, jpegData);
        try {
            BlobInfo blob = BlobInfo.newBuilder(bucket, key)
                    .setContentType("image/jpeg")
                    .setCacheControl(IMMUTABLE_CACHE)
                    .build();
            storage().create(blob, jpegData);
        } catch (RuntimeException ex) {
            log.error("Failed to store student photo (bucket={}, key={})", bucket, key, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not store the photo: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
        return key;
    }

    /**
     * Stores the original student-import file for auditability. Local/test environments may not
     * configure the private bucket; in that case the import still proceeds and returns null while
     * the DB keeps row-level import evidence.
     */
    public String uploadImportFile(String schoolStorageId, String batchId, byte[] data, String contentType, String fileName) {
        if (!isEnabled() || data == null || data.length == 0) {
            return null;
        }
        String folder = requireStorageFolder(schoolStorageId);
        String key = importFileObjectKey(folder, batchId, data, fileName);
        try {
            BlobInfo blob = BlobInfo.newBuilder(bucket, key)
                    .setContentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream")
                    .setCacheControl("private, max-age=0, no-store")
                    .build();
            storage().create(blob, data);
            return key;
        } catch (RuntimeException ex) {
            log.error("Failed to store student import file (bucket={}, key={})", bucket, key, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not store the import file: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Convert a stored value to something an {@code <img src>} can load: null stays null, an
     * existing {@code http(s)} URL (legacy/external) is returned as-is, and a GCS object key is
     * turned into a fresh signed URL. Returns null if signing fails (so the UI shows a placeholder).
     */
    public String toDisplayUrl(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        if (stored.startsWith("http://") || stored.startsWith("https://") || !isEnabled()) {
            return stored;
        }
        try {
            URL url = storage().signUrl(
                    BlobInfo.newBuilder(bucket, stored).build(),
                    ttlMinutes, TimeUnit.MINUTES,
                    Storage.SignUrlOption.signWith(signer()),
                    Storage.SignUrlOption.withV4Signature());
            return url.toString();
        } catch (Exception ex) {
            log.warn("Failed to sign student photo URL for key {}: {}", stored, ex.toString());
            return null;
        }
    }

    public Optional<StoredPhoto> readStoredPhoto(String stored) {
        if (!StringUtils.hasText(stored) || stored.startsWith("http://") || stored.startsWith("https://")) {
            return Optional.empty();
        }
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            var blob = storage().get(bucket, stored);
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            String contentType = StringUtils.hasText(blob.getContentType()) ? blob.getContentType() : "image/jpeg";
            return Optional.of(new StoredPhoto(blob.getContent(), contentType));
        } catch (RuntimeException ex) {
            log.warn("Failed to read student photo for key {}: {}", stored, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * Validates orientation/decoded size and produces a centered square JPEG portrait.
     * Exposed for the review preview so the operator sees the exact crop that execution stores.
     */
    public byte[] normalizePortrait(byte[] data, String contentType) {
        return normalizePortrait(data, contentType, 0.5, 0.5);
    }

    public byte[] normalizePortrait(
            byte[] data,
            String contentType,
            double cropX,
            double cropY) {
        return normalizePortrait(data, contentType, cropX, cropY, maxBytes);
    }

    public byte[] normalizePortrait(
            byte[] data,
            String contentType,
            double cropX,
            double cropY,
            long maxInputBytes) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("The photo file is empty");
        }
        long effectiveMaxBytes = maxInputBytes > 0 ? maxInputBytes : maxBytes;
        if (data.length > effectiveMaxBytes) {
            throw new IllegalArgumentException(
                    "Photo must be " + (effectiveMaxBytes / (1024 * 1024)) + " MB or smaller");
        }
        if (!isSupportedImage(contentType)) {
            throw new IllegalArgumentException("Only JPG, PNG, or WEBP images are allowed");
        }
        requireCropCoordinate(cropX, "cropX");
        requireCropCoordinate(cropY, "cropY");
        validatePixelCount(data);
        try {
            BufferedImage oriented = Thumbnails.of(new ByteArrayInputStream(data))
                    .useExifOrientation(true)
                    .scale(1)
                    .asBufferedImage();
            int side = Math.min(oriented.getWidth(), oriented.getHeight());
            int left = cropOrigin(cropX, oriented.getWidth(), side);
            int top = cropOrigin(cropY, oriented.getHeight(), side);
            BufferedImage cropped = oriented.getSubimage(left, top, side, side);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(cropped)
                    .size(dimension, dimension)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Could not read the image; upload a valid JPG, PNG, or WEBP", ex);
        }
    }

    private boolean isSupportedImage(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return true; // some clients omit it; rely on the decoder to reject non-images
        }
        String ct = contentType.toLowerCase();
        return ct.startsWith("image/jpeg") || ct.startsWith("image/jpg")
                || ct.startsWith("image/pjpeg")
                || ct.startsWith("image/png")
                || ct.startsWith("image/webp");
    }

    private Storage storage() {
        Storage s = storage;
        if (s == null) {
            synchronized (this) {
                s = storage;
                if (s == null) {
                    s = StorageOptions.getDefaultInstance().getService();
                    storage = s;
                }
            }
        }
        return s;
    }

    private ImpersonatedCredentials signer() {
        ImpersonatedCredentials s = signer;
        if (s == null) {
            synchronized (this) {
                s = signer;
                if (s == null) {
                    try {
                        s = ImpersonatedCredentials.create(
                                GoogleCredentials.getApplicationDefault(),
                                resolveSignerSa(), List.of(),
                                List.of("https://www.googleapis.com/auth/cloud-platform"), 3600);
                    } catch (IOException ex) {
                        throw new IllegalStateException("Cannot build the photo URL signer", ex);
                    }
                    signer = s;
                }
            }
        }
        return s;
    }

    private String resolveSignerSa() {
        if (StringUtils.hasText(configuredSignerSa)) {
            return configuredSignerSa;
        }
        String cached = signerSa;
        if (cached != null) {
            return cached;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Metadata-Flavor", "Google")
                    .GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && StringUtils.hasText(response.body())) {
                signerSa = response.body().trim();
                return signerSa;
            }
        } catch (IOException ex) {
            // fall through
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("Cannot resolve the signer service account (set student.photo.signer-sa)");
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int cropOrigin(double focus, int dimension, int side) {
        int origin = (int) Math.round(focus * dimension - side / 2.0);
        return Math.max(0, Math.min(dimension - side, origin));
    }

    private static void requireCropCoordinate(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    private void validatePixelCount(byte[] data) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) {
                throw new IllegalArgumentException("Could not read the image");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Could not read the image; upload a valid JPG, PNG, or WEBP");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = Math.multiplyExact((long) reader.getWidth(0), (long) reader.getHeight(0));
                if (pixels > MAX_DECODED_PIXELS) {
                    throw new IllegalArgumentException("Photo dimensions are too large");
                }
            } finally {
                reader.dispose();
            }
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Photo dimensions are too large", ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read the image; upload a valid JPG, PNG, or WEBP", ex);
        }
    }

    public static String sha256Hex(byte[] data) {
        return sha256(data == null ? new byte[0] : data);
    }

    public record StoredPhoto(byte[] data, String contentType) {}

    static String studentPhotoObjectKey(String schoolStorageId, long studentId, byte[] resized) {
        String folder = requireStorageFolder(schoolStorageId);
        return "schools/" + folder + "/students/" + studentId + "/photos/" + sha256(resized) + ".jpg";
    }

    static String importFileObjectKey(String schoolStorageId, String batchId, byte[] data, String fileName) {
        String folder = requireStorageFolder(schoolStorageId);
        return "schools/" + folder + "/student-imports/" + batchId + "/" + sha256(data) + "-"
                + sanitizeFileName(fileName);
    }

    private static String requireStorageFolder(String schoolStorageId) {
        String folder = schoolStorageId == null ? "" : schoolStorageId.trim();
        if (!StringUtils.hasText(folder)) {
            throw new IllegalArgumentException("School storage id is required");
        }
        if (!folder.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("School storage id contains invalid characters");
        }
        return folder;
    }

    private static String sanitizeFileName(String fileName) {
        String raw = StringUtils.hasText(fileName) ? fileName.trim() : "students-import";
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 160) {
            safe = safe.substring(safe.length() - 160);
        }
        return safe.isBlank() ? "students-import" : safe;
    }
}
