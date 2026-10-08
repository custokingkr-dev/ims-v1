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
 * content-addressed and served with private no-store cache policy. Signed capabilities expire
 * within five minutes; previously downloaded copies cannot be revoked. Cloud Run service accounts have no local
 * private key, so URLs are signed via the IAM SignBlob API using {@link ImpersonatedCredentials}
 * self-impersonation (the runtime SA needs {@code roles/iam.serviceAccountTokenCreator} on itself).
 *
 * <p>Degrades gracefully when no bucket is configured (local/tests): {@link #toDisplayUrl} returns
 * null without owner context and {@link #upload} fails with a clear 503.
 */
@Component
public class StudentPhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(StudentPhotoStorage.class);
    private static final String PRIVATE_CACHE = "private, max-age=0, no-store";
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
            @Value("${student.photo.signed-url-ttl-minutes:5}") int ttlMinutes,
            @Value("${student.photo.dimension:512}") int dimension,
            @Value("${student.photo.max-bytes:5242880}") long maxBytes,
            @Value("${student.photo.signer-sa:}") String signerSa) {
        this.bucket = bucket == null ? "" : bucket.trim();
        this.ttlMinutes = ttlMinutes > 0 ? Math.min(ttlMinutes, 5) : 5;
        this.dimension = dimension > 0 ? dimension : 512;
        this.maxBytes = maxBytes > 0 ? maxBytes : 2L * 1024 * 1024;
        this.configuredSignerSa = signerSa == null ? "" : signerSa.trim();
    }

    public boolean isEnabled() {
        return StringUtils.hasText(bucket);
    }

    /** Validate + resize without cropping + store the image; returns the GCS object key to persist. */
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
                    .setCacheControl(PRIVATE_CACHE)
                    .build();
            storage().create(blob, jpegData);
        } catch (RuntimeException ex) {
            log.error("Student photo storage unavailable");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not store the photo");
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
        return uploadPrivateFile(importFileObjectKey(schoolStorageId, batchId, data, fileName), data, contentType);
    }

    /** Stores resumable photo-import source evidence under the bucket's temporary lifecycle prefix. */
    public String uploadTemporaryPhotoImportFile(
            String schoolStorageId,
            String batchId,
            byte[] data,
            String contentType,
            String fileName) {
        if (!isEnabled() || data == null || data.length == 0) {
            return null;
        }
        return uploadPrivateFile(temporaryPhotoImportObjectKey(schoolStorageId, batchId, data, fileName), data, contentType);
    }

    private String uploadPrivateFile(String key, byte[] data, String contentType) {
        try {
            BlobInfo blob = BlobInfo.newBuilder(bucket, key)
                    .setContentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream")
                    .setCacheControl("private, max-age=0, no-store")
                    .build();
            storage().create(blob, data);
            return key;
        } catch (RuntimeException ex) {
            log.error("Student import storage unavailable");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not store the import file");
        }
    }

    /** Unscoped callers must never mint capabilities or read shared-bucket objects. */
    @Deprecated
    public String toDisplayUrl(String stored) { return null; }

    public String toDisplayUrl(String stored, String schoolUid, long studentId) {
        String key = ownedPhotoKey(stored, schoolUid, studentId, true);
        if (key == null || !isEnabled()) return null;
        try {
            return storage().signUrl(BlobInfo.newBuilder(bucket, key).build(), ttlMinutes, TimeUnit.MINUTES,
                    Storage.SignUrlOption.signWith(signer()), Storage.SignUrlOption.withV4Signature()).toString();
        } catch (RuntimeException failure) {
            log.warn("Student photo signing unavailable"); return null;
        }
    }

    private static final int STORED_PHOTO_HARD_LIMIT = 16 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService PHOTO_READS = new java.util.concurrent.ThreadPoolExecutor(
            8, 8, 0, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), runnable -> {
                var thread = new Thread(runnable, "owned-photo-read"); thread.setDaemon(true); return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** Compatibility signature fails closed: ownership must come from the owner repository. */
    @Deprecated
    public Optional<StoredPhoto> readStoredPhoto(String stored) { return Optional.empty(); }

    public Optional<StoredPhoto> readStoredPhoto(String stored, String schoolUid, long studentId) {
        String key = ownedPhotoKey(stored, schoolUid, studentId, false);
        if (key == null || !isEnabled()) return Optional.empty();
        java.util.concurrent.Future<Optional<StoredPhoto>> task = null;
        try {
            task = PHOTO_READS.submit(() -> readOwnedGeneration(key));
            return task.get(8, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return Optional.empty();
        } catch (Exception unconfirmed) {
            log.warn("Student photo read unavailable"); return Optional.empty();
        } finally { if (task != null && !task.isDone()) task.cancel(true); }
    }

    private Optional<StoredPhoto> readOwnedGeneration(String key) throws IOException {
        var client = cleanupStorage(); // bounded SDK connect/read/total timeout, one attempt
        var blob = client.get(com.google.cloud.storage.BlobId.of(bucket, key),
                Storage.BlobGetOption.fields(Storage.BlobField.GENERATION, Storage.BlobField.SIZE, Storage.BlobField.CONTENT_TYPE));
        if (blob == null) return Optional.empty();
        Long generation = blob.getGeneration(), size = blob.getSize();
        int limit = (int) Math.min(maxBytes, STORED_PHOTO_HARD_LIMIT);
        if (generation == null || generation <= 0 || size == null || size <= 0 || size > limit) return Optional.empty();
        String type = blob.getContentType();
        type = StringUtils.hasText(type) ? type.toLowerCase(java.util.Locale.ROOT) : "image/jpeg";
        if (!java.util.Set.of("image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp").contains(type)) return Optional.empty();
        try (var reader = client.reader(com.google.cloud.storage.BlobId.of(bucket, key, generation),
                Storage.BlobSourceOption.generationMatch(generation))) {
            reader.setChunkSize(8192);
            var buffer = java.nio.ByteBuffer.allocate(8192);
            var out = new ByteArrayOutputStream((int) Math.min(size, 8192));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (true) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return Optional.empty();
                buffer.clear(); int read = reader.read(buffer);
                if (read == 0) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); continue; }
                if (read < 0) break;
                if (read > limit - out.size() || read > size - out.size()) return Optional.empty();
                out.write(buffer.array(), 0, read);
            }
            if (out.size() != size || System.nanoTime() >= deadline) return Optional.empty();
            return Optional.of(new StoredPhoto(out.toByteArray(), type));
        }
    }

    /** Exact owner UID/student prefix; no encoded URL paths, traversal or other object classes. */
    private String ownedPhotoKey(String stored, String schoolUid, long studentId, boolean allowOwnedLegacyUrl) {
        if (!StringUtils.hasText(stored) || studentId <= 0 || schoolUid == null || stored.length() > 2048) return null;
        try {
            if (!java.util.UUID.fromString(schoolUid).toString().equals(schoolUid)) return null;
            String key = stored;
            if (stored.startsWith("http:") || stored.startsWith("https:")) {
                if (!allowOwnedLegacyUrl) return null;
                var uri = URI.create(stored);
                if (!"https".equals(uri.getScheme()) || !"storage.googleapis.com".equals(uri.getHost())
                        || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                        || !uri.getRawPath().equals(uri.getPath()) || uri.getFragment() != null
                        || !uri.getPath().startsWith("/" + bucket + "/")) return null;
                key = uri.getPath().substring(bucket.length() + 2);
            }
            String prefix = "schools/" + schoolUid + "/students/" + studentId + "/photos/";
            if (!key.startsWith(prefix) || !key.matches("[A-Za-z0-9._/-]+")
                    || java.util.Arrays.stream(key.split("/", -1)).anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))) return null;
            if (!key.substring(prefix.length()).matches("(?i)[A-Za-z0-9._-]+\\.(jpg|jpeg|png|webp)")) return null;
            return key;
        } catch (IllegalArgumentException malformed) { return null; }
    }

    /**
     * Validates orientation/decoded size and produces an aspect-ratio-preserving JPEG portrait.
     * The longest edge is bounded by {@code dimension}; no part of the source frame is discarded.
     * Legacy crop coordinates remain accepted and validated for API compatibility, but are no
     * longer used to destructively crop the stored photo.
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
        try (var budget = MediaWorkBudget.acquire()) {
        String detectedFormat = validatePixelCount(data);
        try {
            // EXIF orientation is needed for JPEG only. Other readers must not parse
            // optional metadata when decoding the already dimension-checked pixels.
            BufferedImage oriented = "JPEG".equalsIgnoreCase(detectedFormat)
                    || "JPG".equalsIgnoreCase(detectedFormat)
                    ? Thumbnails.of(new ByteArrayInputStream(data))
                            .useExifOrientation(true).scale(1).asBufferedImage()
                    : decodePixelsWithoutMetadata(data);
            if (oriented == null) throw new IllegalArgumentException("Could not read the image");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(oriented)
                    .size(dimension, dimension)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Could not read the image; upload a valid JPG, PNG, or WEBP", ex);
        }
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

    private static void requireCropCoordinate(double value, String field) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    private static BufferedImage decodePixelsWithoutMetadata(byte[] data) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) throw new IOException("Image input unavailable");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Image reader unavailable");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private String validatePixelCount(byte[] data) {
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
                return reader.getFormatName();
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

    /**
     * Permanently removes a student-owned object after its database records commit. External
     * legacy URLs and non-student object prefixes are deliberately ignored.
     */
    public record CleanupTarget(String bucket, String key) {}

    public Optional<CleanupTarget> cleanupTarget(String stored, String schoolStorageId, long studentId) {
        if (!StringUtils.hasText(stored) || stored.startsWith("http://") || stored.startsWith("https://")) return Optional.empty();
        String prefix = "schools/" + requireStorageFolder(schoolStorageId) + "/students/" + studentId + "/";
        if (!stored.startsWith(prefix) || !stored.matches("[A-Za-z0-9._/-]+")
                || java.util.Arrays.stream(stored.split("/")).anyMatch(part -> part.isBlank() || part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Stored photo does not belong to the erased student");
        if (!isEnabled()) throw new IllegalStateException("Photo cleanup bucket is not configured");
        return Optional.of(new CleanupTarget(bucket, stored));
    }

    private volatile Storage cleanupStorage;
    private Storage cleanupStorage() {
        Storage result = cleanupStorage;
        if (result == null) synchronized (this) {
            result = cleanupStorage;
            if (result == null) {
                var options = StorageOptions.getDefaultInstance();
                result = options.toBuilder()
                        .setTransportOptions(com.google.cloud.http.HttpTransportOptions.newBuilder()
                                .setConnectTimeout(2000).setReadTimeout(5000).build())
                        .setRetrySettings(options.getRetrySettings().toBuilder().setMaxAttempts(1)
                                .setInitialRpcTimeoutDuration(Duration.ofSeconds(5))
                                .setMaxRpcTimeoutDuration(Duration.ofSeconds(5))
                                .setTotalTimeoutDuration(Duration.ofSeconds(5)).build())
                        .build().getService();
                cleanupStorage = result;
            }
        }
        return result;
    }

    private void validateCleanupTarget(CleanupTarget target) {
        if (!isEnabled() || target == null || !bucket.equals(target.bucket())
                || !target.key().matches("schools/[A-Za-z0-9._-]+/students/[0-9]+/[A-Za-z0-9._/-]+")
                || java.util.Arrays.stream(target.key().split("/")).anyMatch(part -> part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Photo cleanup target is not configured/owned");
    }

    /** Read only this exact object's metadata; callers persist the generation before any deletion. */
    public Long photoCleanupGeneration(CleanupTarget target) {
        validateCleanupTarget(target);
        var blob = cleanupStorage().get(com.google.cloud.storage.BlobId.of(target.bucket(), target.key()),
                Storage.BlobGetOption.fields(Storage.BlobField.GENERATION));
        return blob == null ? null : blob.getGeneration();
    }

    /** Missing original generation is success; replacement generations are never deleted. */
    public void deletePhotoGeneration(CleanupTarget target, long generation) {
        validateCleanupTarget(target);
        if (generation <= 0) throw new IllegalArgumentException("Photo generation is invalid");
        try {
            cleanupStorage().delete(com.google.cloud.storage.BlobId.of(target.bucket(), target.key(), generation),
                    Storage.BlobSourceOption.generationMatch(generation));
        } catch (com.google.cloud.storage.StorageException error) {
            if (error.getCode() != 404 && error.getCode() != 412) throw error;
        }
    }

    public void deleteStoredPhoto(String stored) {
        if (!StringUtils.hasText(stored)
                || stored.startsWith("http://")
                || stored.startsWith("https://")
                || !stored.startsWith("schools/")
                || !stored.contains("/students/")
                || !isEnabled()) {
            return;
        }
        try {
            storage().delete(bucket, stored);
        } catch (RuntimeException ex) {
            // The database deletion is authoritative. A failed object cleanup is observable and
            // safe to retry, but must not resurrect or partially restore the student record.
            log.warn("Student photo cleanup unavailable");
        }
    }

    static String temporaryPhotoImportObjectKey(
            String schoolStorageId,
            String batchId,
            byte[] data,
            String fileName) {
        String folder = requireStorageFolder(schoolStorageId);
        return "temporary/photo-imports/" + folder + "/" + batchId + "/" + sha256(data) + "-"
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
