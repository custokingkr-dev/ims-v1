package com.custoking.ims.schoolcoreservice.photoimport;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class GoogleDrivePhotoImportClient {
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final boolean enabled;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private volatile GoogleCredentials credentials;

    public GoogleDrivePhotoImportClient(
            ObjectMapper objectMapper,
            @Value("${student.photo-import.drive-enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public DriveFolder readFolder(String rawFolder) {
        requireEnabled();
        String folderId = DriveFolderId.parse(rawFolder);
        DriveFile folder = metadata(folderId);
        if (!FOLDER_MIME.equals(folder.mimeType())) {
            throw new DrivePhotoImportException("not_a_folder", "The Drive link does not point to a folder");
        }
        return new DriveFolder(folder.id(), folder.name());
    }

    public List<DriveFile> listFiles(String folderId) {
        requireEnabled();
        List<DriveFile> result = new ArrayList<>();
        String pageToken = null;
        do {
            String query = "'" + folderId + "' in parents and trashed = false";
            StringBuilder uri = new StringBuilder(API)
                    .append("?q=").append(encode(query))
                    .append("&pageSize=1000")
                    .append("&supportsAllDrives=true")
                    .append("&includeItemsFromAllDrives=true")
                    .append("&fields=").append(encode("nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime,trashed)"));
            if (pageToken != null) {
                uri.append("&pageToken=").append(encode(pageToken));
            }
            Map<String, Object> payload = jsonGet(uri.toString());
            Object files = payload.get("files");
            if (files instanceof List<?> values) {
                for (Object value : values) {
                    if (value instanceof Map<?, ?> row) {
                        result.add(toDriveFile(row));
                    }
                }
            }
            pageToken = string(payload.get("nextPageToken"));
        } while (pageToken != null && !pageToken.isBlank());
        return result.stream().sorted(Comparator.comparing(DriveFile::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public byte[] download(DriveFile file, long maxBytes) {
        requireEnabled();
        long effectiveMax = maxBytes > 0 ? maxBytes : MAX_IMAGE_BYTES;
        if (file.size() != null && file.size() > effectiveMax) {
            throw new DrivePhotoImportException("file_too_large",
                    file.name() + " is larger than " + (effectiveMax / (1024 * 1024)) + " MB");
        }
        HttpRequest request = request(API + "/" + encode(file.id()) + "?alt=media&supportsAllDrives=true");
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw driveFailure(response.statusCode(), response.body());
            }
            if (response.body().length > effectiveMax) {
                throw new DrivePhotoImportException("file_too_large", file.name() + " exceeds the allowed size");
            }
            return response.body();
        } catch (DrivePhotoImportException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DrivePhotoImportException("drive_unavailable", "Drive request was interrupted", ex);
        } catch (IOException ex) {
            throw new DrivePhotoImportException("drive_unavailable", "Could not download " + file.name(), ex);
        }
    }

    public String snapshotHash(List<DriveFile> files) {
        String manifest = files.stream()
                .sorted(Comparator.comparing(DriveFile::id))
                .map(file -> String.join("|", file.id(), file.name(), string(file.size()),
                        string(file.md5Checksum()), string(file.modifiedTime())))
                .reduce("", (left, right) -> left + right + "\n");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private DriveFile metadata(String id) {
        Map<String, Object> row = jsonGet(API + "/" + encode(id)
                + "?supportsAllDrives=true&fields=" + encode("id,name,mimeType,size,md5Checksum,modifiedTime,trashed"));
        return toDriveFile(row);
    }

    private Map<String, Object> jsonGet(String uri) {
        try {
            HttpResponse<String> response = http.send(request(uri), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw driveFailure(response.statusCode(), response.body().getBytes(StandardCharsets.UTF_8));
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (DrivePhotoImportException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DrivePhotoImportException("drive_unavailable", "Drive request was interrupted", ex);
        } catch (IOException ex) {
            throw new DrivePhotoImportException("drive_unavailable", "Could not read from Google Drive", ex);
        }
    }

    private HttpRequest request(String uri) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private String accessToken() {
        try {
            GoogleCredentials current = credentials;
            if (current == null) {
                synchronized (this) {
                    current = credentials;
                    if (current == null) {
                        current = GoogleCredentials.getApplicationDefault().createScoped(List.of(DRIVE_SCOPE));
                        credentials = current;
                    }
                }
            }
            current.refreshIfExpired();
            AccessToken token = current.getAccessToken();
            if (token == null) {
                current.refresh();
                token = current.getAccessToken();
            }
            return token.getTokenValue();
        } catch (IOException ex) {
            throw new DrivePhotoImportException("drive_auth_failed",
                    "Google Drive credentials are not available to the service", ex);
        }
    }

    private DrivePhotoImportException driveFailure(int status, byte[] body) {
        String detail = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        String lower = detail.toLowerCase(Locale.ROOT);
        if (status == 403 || status == 404) {
            return new DrivePhotoImportException("drive_access_denied",
                    "The folder or file is not shared with the Custoking Drive reader");
        }
        if (lower.contains("ratelimit") || status == 429) {
            return new DrivePhotoImportException("drive_rate_limited", "Google Drive rate limit reached; retry shortly");
        }
        return new DrivePhotoImportException("drive_unavailable", "Google Drive returned HTTP " + status);
    }

    private DriveFile toDriveFile(Map<?, ?> row) {
        return new DriveFile(
                string(row.get("id")),
                string(row.get("name")),
                string(row.get("mimeType")),
                longValue(row.get("size")),
                string(row.get("md5Checksum")),
                string(row.get("modifiedTime")));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new DrivePhotoImportException("drive_not_configured",
                    "Google Drive photo import is not configured in this environment");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record DriveFolder(String id, String name) {
    }

    public record DriveFile(
            String id,
            String name,
            String mimeType,
            Long size,
            String md5Checksum,
            String modifiedTime) {
        public boolean isXlsx() {
            return name != null && name.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                    && !"application/vnd.google-apps.folder".equals(mimeType);
        }
    }
}
