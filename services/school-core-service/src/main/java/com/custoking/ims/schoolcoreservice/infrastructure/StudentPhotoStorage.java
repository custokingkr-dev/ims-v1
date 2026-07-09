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
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
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
    public String upload(long schoolId, long studentId, byte[] data, String contentType) {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("The photo file is empty");
        }
        if (data.length > maxBytes) {
            throw new IllegalArgumentException("Photo must be " + (maxBytes / (1024 * 1024)) + " MB or smaller");
        }
        if (!isSupportedImage(contentType)) {
            throw new IllegalArgumentException("Only JPG, PNG or WEBP images are allowed");
        }
        byte[] resized = resize(data);
        String key = "students/" + schoolId + "/" + studentId + "/" + sha256(resized) + ".jpg";
        try {
            BlobInfo blob = BlobInfo.newBuilder(bucket, key)
                    .setContentType("image/jpeg")
                    .setCacheControl(IMMUTABLE_CACHE)
                    .build();
            storage().create(blob, resized);
        } catch (RuntimeException ex) {
            log.error("Failed to store student photo (bucket={}, key={})", bucket, key, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not store the photo: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
        return key;
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

    private byte[] resize(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // Fit within dimension x dimension, preserving aspect ratio (the client already crops
            // to the intended square before upload), then compress to JPEG.
            Thumbnails.of(new ByteArrayInputStream(data))
                    .size(dimension, dimension)
                    .outputFormat("jpg")
                    .outputQuality(0.82)
                    .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Could not read the image; upload a valid JPG or PNG", ex);
        }
    }

    private boolean isSupportedImage(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return true; // some clients omit it; rely on the decoder to reject non-images
        }
        String ct = contentType.toLowerCase();
        return ct.startsWith("image/jpeg") || ct.startsWith("image/jpg")
                || ct.startsWith("image/png") || ct.startsWith("image/webp");
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
}
