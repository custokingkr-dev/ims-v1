package com.custoking.ims.schoolcoreservice.photoimport;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
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
    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final long DEFAULT_MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024;
    /**
     * The importer lists and downloads files that photographers uploaded, which this application did not
     * create, so the narrower drive.file scope cannot see them.
     */
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    static final String CREDENTIAL_MODE_USER = "user";
    static final String CREDENTIAL_MODE_SERVICE_ACCOUNT = "service-account";

    private final boolean enabled;
    private final String configuredRootFolder;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final String oauthRefreshToken;
    private final String credentialMode;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private volatile GoogleCredentials credentials;

    public GoogleDrivePhotoImportClient(
            ObjectMapper objectMapper,
            @Value("${student.photo-import.drive-enabled:false}") boolean enabled,
            @Value("${student.photo-import.root-folder-id:}") String rootFolderId,
            @Value("${student.photo-import.oauth.client-id:}") String oauthClientId,
            @Value("${student.photo-import.oauth.client-secret:}") String oauthClientSecret,
            @Value("${student.photo-import.oauth.refresh-token:}") String oauthRefreshToken,
            @Value("${student.photo-import.credential-mode:user}") String credentialMode) {
        this.enabled = enabled;
        this.configuredRootFolder = setting(rootFolderId);
        this.oauthClientId = setting(oauthClientId);
        this.oauthClientSecret = setting(oauthClientSecret);
        this.oauthRefreshToken = setting(oauthRefreshToken);
        this.credentialMode = setting(credentialMode).isBlank()
                ? CREDENTIAL_MODE_USER
                : setting(credentialMode).toLowerCase(Locale.ROOT);
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isEnabled() {
        return enabled && hasUsableCredentials();
    }

    /** Which credential this client authenticates with. Defaults to the personal OAuth user. */
    public String credentialMode() {
        return credentialMode;
    }

    private boolean usingServiceAccount() {
        return CREDENTIAL_MODE_SERVICE_ACCOUNT.equals(credentialMode);
    }

    /**
     * In service-account mode there is nothing to configure beyond the runtime identity, which Cloud Run
     * supplies; the folders simply have to be shared with that identity's address.
     */
    private boolean hasUsableCredentials() {
        return usingServiceAccount() || hasPersonalOauthCredentials();
    }

    public boolean isProvisioningEnabled() {
        return isEnabled() && !configuredRootFolder.isBlank();
    }

    public String rootFolderId() {
        if (configuredRootFolder.isBlank()) {
            return "";
        }
        return DriveFolderId.parse(configuredRootFolder);
    }

    public ProvisionedFolders provisionSchoolFolders(
            String schoolUid,
            String shortCode,
            String schoolName,
            String academicYearId,
            String academicYearLabel) {
        requireProvisioningEnabled();
        String rootId = rootFolderId();
        DriveFolder root = readFolder(rootId);

        ManagedFolder schoolFolder = ensureManagedFolder(
                root.id(),
                folderName(shortCode + " - " + schoolName),
                Map.of(
                        "custokingType", "school",
                        "custokingSchoolUid", schoolUid));
        ManagedFolder yearFolder = ensureManagedFolder(
                schoolFolder.id(),
                folderName(academicYearLabel),
                Map.of(
                        "custokingType", "academic-year",
                        "custokingSchoolUid", schoolUid,
                        "custokingAcademicYearId", academicYearId));
        ManagedFolder intakeFolder = ensureManagedFolder(
                yearFolder.id(),
                "Student Photo Intake",
                Map.of(
                        "custokingType", "student-photo-intake",
                        "custokingSchoolUid", schoolUid,
                        "custokingAcademicYearId", academicYearId));
        String folderUrl = intakeFolder.webViewLink() == null || intakeFolder.webViewLink().isBlank()
                ? "https://drive.google.com/drive/folders/" + intakeFolder.id()
                : intakeFolder.webViewLink();
        return new ProvisionedFolders(
                schoolFolder.id(),
                yearFolder.id(),
                intakeFolder.id(),
                intakeFolder.name(),
                folderUrl);
    }

    public DriveFolder readFolder(String rawFolder) {
        requireEnabled();
        String folderId = DriveFolderId.parse(rawFolder);
        Map<String, Object> metadata = fileMetadata(
                folderId, "id,name,mimeType,size,md5Checksum,sha256Checksum,headRevisionId,version,modifiedTime,trashed");
        DriveFile folder = toDriveFile(metadata);
        if (booleanValue(metadata.get("trashed")) || !FOLDER_MIME.equals(folder.mimeType())) {
            throw new DrivePhotoImportException("not_a_folder", "The Drive link does not point to a folder");
        }
        return new DriveFolder(folder.id(), folder.name());
    }

    public boolean managedHierarchyMatches(
            String rootFolderId,
            String schoolFolderId,
            String academicYearFolderId,
            String intakeFolderId,
            String schoolUid,
            String academicYearId) {
        requireProvisioningEnabled();
        return managedFolderMatches(
                        schoolFolderId,
                        rootFolderId,
                        Map.of("custokingType", "school", "custokingSchoolUid", schoolUid))
                && managedFolderMatches(
                        academicYearFolderId,
                        schoolFolderId,
                        Map.of(
                                "custokingType", "academic-year",
                                "custokingSchoolUid", schoolUid,
                                "custokingAcademicYearId", academicYearId))
                && managedFolderMatches(
                        intakeFolderId,
                        academicYearFolderId,
                        Map.of(
                                "custokingType", "student-photo-intake",
                                "custokingSchoolUid", schoolUid,
                                "custokingAcademicYearId", academicYearId));
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
                    .append("&fields=").append(encode(
                            "nextPageToken,files(id,name,mimeType,size,md5Checksum,sha256Checksum,headRevisionId,version,modifiedTime,trashed)"));
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
        long effectiveMax = maxBytes > 0 ? maxBytes : DEFAULT_MAX_DOWNLOAD_BYTES;
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
                        string(file.sha256Checksum()), string(file.headRevisionId()),
                        string(file.driveVersion()), string(file.modifiedTime())))
                .reduce("", (left, right) -> left + right + "\n");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> fileMetadata(String id, String fields) {
        return jsonGet(API + "/" + encode(id)
                + "?supportsAllDrives=true&fields=" + encode(fields));
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

    private Map<String, Object> jsonPost(String uri, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw driveFailure(response.statusCode(), response.body().getBytes(StandardCharsets.UTF_8));
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (DrivePhotoImportException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DrivePhotoImportException("drive_unavailable", "Drive request was interrupted", ex);
        } catch (IOException ex) {
            throw new DrivePhotoImportException("drive_unavailable", "Could not write to Google Drive", ex);
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
                        current = usingServiceAccount()
                                ? GoogleCredentials.getApplicationDefault().createScoped(DRIVE_SCOPE)
                                : UserCredentials.newBuilder()
                                        .setClientId(oauthClientId)
                                        .setClientSecret(oauthClientSecret)
                                        .setRefreshToken(oauthRefreshToken)
                                        .build();
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
            throw new DrivePhotoImportException("drive_auth_failed", usingServiceAccount()
                    ? "The Drive service-account credential is unavailable; confirm the folders are shared "
                            + "with the runtime service account"
                    : "The personal Google Drive connection is unavailable or expired; reconnect the account",
                    ex);
        }
    }

    private DrivePhotoImportException driveFailure(int status, byte[] body) {
        String detail = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        String lower = detail.toLowerCase(Locale.ROOT);
        if (status == 401 || lower.contains("invalid_grant")) {
            return new DrivePhotoImportException("drive_auth_failed",
                    "The personal Google Drive connection is unavailable or expired; reconnect the account");
        }
        if (status == 403 || status == 404) {
            return new DrivePhotoImportException("drive_access_denied",
                    "The Drive item is unavailable or the connected Google account lacks access");
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
                string(row.get("sha256Checksum")),
                string(row.get("headRevisionId")),
                string(row.get("version")),
                string(row.get("modifiedTime")));
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new DrivePhotoImportException("drive_not_configured",
                    "A personal Google Drive account has not been connected in this environment");
        }
    }

    private void requireProvisioningEnabled() {
        requireEnabled();
        if (configuredRootFolder.isBlank()) {
            throw new DrivePhotoImportException("drive_not_configured",
                    "A root folder in the connected personal Google Drive is required for automatic provisioning");
        }
    }

    private boolean hasPersonalOauthCredentials() {
        return !oauthClientId.isBlank() && !oauthClientSecret.isBlank() && !oauthRefreshToken.isBlank();
    }

    private ManagedFolder ensureManagedFolder(
            String parentId,
            String name,
            Map<String, String> appProperties) {
        List<ManagedFolder> existing = managedFolders(parentId, appProperties);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", name);
        payload.put("mimeType", FOLDER_MIME);
        payload.put("parents", List.of(parentId));
        payload.put("appProperties", appProperties);
        return toManagedFolder(jsonPost(
                API + "?supportsAllDrives=true&fields="
                        + encode("id,name,mimeType,webViewLink,createdTime,appProperties"),
                payload));
    }

    private List<ManagedFolder> managedFolders(String parentId, Map<String, String> appProperties) {
        StringBuilder query = new StringBuilder("'")
                .append(driveQueryLiteral(parentId))
                .append("' in parents and trashed = false and mimeType = '")
                .append(FOLDER_MIME)
                .append("'");
        appProperties.forEach((key, value) -> query.append(" and appProperties has { key='")
                .append(driveQueryLiteral(key))
                .append("' and value='")
                .append(driveQueryLiteral(value))
                .append("' }"));
        Map<String, Object> payload = jsonGet(API
                + "?q=" + encode(query.toString())
                + "&pageSize=100"
                + "&supportsAllDrives=true"
                + "&includeItemsFromAllDrives=true"
                + "&fields=" + encode("files(id,name,mimeType,webViewLink,createdTime,appProperties)"));
        if (!(payload.get("files") instanceof List<?> values)) {
            return List.of();
        }
        List<ManagedFolder> folders = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> row) {
                folders.add(toManagedFolder(row));
            }
        }
        return folders.stream()
                .sorted(Comparator.comparing(
                        ManagedFolder::createdTime,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(ManagedFolder::id))
                .toList();
    }

    private boolean managedFolderMatches(
            String folderId,
            String expectedParentId,
            Map<String, String> expectedProperties) {
        if (folderId == null || folderId.isBlank()
                || expectedParentId == null || expectedParentId.isBlank()) {
            return false;
        }
        String parsedFolderId;
        try {
            parsedFolderId = DriveFolderId.parse(folderId);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        Map<String, Object> row = fileMetadata(
                parsedFolderId, "id,mimeType,trashed,parents,appProperties");
        return matchesManagedFolderMetadata(row, expectedParentId, expectedProperties);
    }

    static boolean matchesManagedFolderMetadata(
            Map<String, Object> row,
            String expectedParentId,
            Map<String, String> expectedProperties) {
        if (row == null || booleanValue(row.get("trashed"))
                || !FOLDER_MIME.equals(string(row.get("mimeType")))) {
            return false;
        }
        if (!(row.get("parents") instanceof List<?> parents)
                || parents.stream().map(GoogleDrivePhotoImportClient::string)
                        .noneMatch(expectedParentId::equals)) {
            return false;
        }
        // Drive appProperties are PRIVATE to the application that wrote them, so a different credential
        // identity cannot see the ones an earlier client stamped. Requiring them here meant that changing
        // credential-mode made every existing folder look unrecognised, and provisioning created a
        // duplicate beside it -- with photographers still uploading to the original while the importer
        // read the new empty one, and nothing erroring.
        //
        // The folder id being checked came from our own database, and the checks above already establish
        // that it resolves to a live, untrashed folder under the expected parent. Treat appProperties as
        // corroborating evidence instead: when they are visible they must still match exactly, and when
        // they are absent the structural checks stand on their own.
        if (!(row.get("appProperties") instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return true;
        }
        return expectedProperties.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(string(properties.get(entry.getKey()))));
    }

    private ManagedFolder toManagedFolder(Map<?, ?> row) {
        return new ManagedFolder(
                string(row.get("id")),
                string(row.get("name")),
                string(row.get("webViewLink")),
                string(row.get("createdTime")));
    }

    private static String folderName(String value) {
        String normalized = value == null ? "" : value.trim()
                .replaceAll("[\\\\/:*?\"<>|]+", "-")
                .replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return "School";
        }
        return normalized.length() <= 180 ? normalized : normalized.substring(0, 180).trim();
    }

    private static String driveQueryLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String setting(String value) {
        return value == null ? "" : value.trim();
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

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(string(value));
    }

    public record DriveFolder(String id, String name) {
    }

    public record ProvisionedFolders(
            String schoolFolderId,
            String academicYearFolderId,
            String intakeFolderId,
            String intakeFolderName,
            String intakeFolderUrl) {
    }

    private record ManagedFolder(String id, String name, String webViewLink, String createdTime) {
    }

    public record DriveFile(
            String id,
            String name,
            String mimeType,
            Long size,
            String md5Checksum,
            String sha256Checksum,
            String headRevisionId,
            String driveVersion,
            String modifiedTime) {
        public DriveFile(
                String id,
                String name,
                String mimeType,
                Long size,
                String md5Checksum,
                String sha256Checksum,
                String modifiedTime) {
            this(id, name, mimeType, size, md5Checksum, sha256Checksum, null, null, modifiedTime);
        }

        public DriveFile(
                String id,
                String name,
                String mimeType,
                Long size,
                String md5Checksum,
                String modifiedTime) {
            this(id, name, mimeType, size, md5Checksum, null, null, null, modifiedTime);
        }

        public boolean isMappingFile() {
            return name != null
                    && name.toLowerCase(Locale.ROOT).matches(".*\\.(xlsx|xls|csv|tsv)$")
                    && !"application/vnd.google-apps.folder".equals(mimeType);
        }

        public boolean isSupportedImage() {
            if (name == null || !name.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$")) {
                return false;
            }
            if (mimeType == null || mimeType.isBlank()) {
                return true;
            }
            String normalizedMime = mimeType.toLowerCase(Locale.ROOT);
            return normalizedMime.startsWith("image/jpeg")
                    || normalizedMime.startsWith("image/jpg")
                    || normalizedMime.startsWith("image/pjpeg")
                    || normalizedMime.startsWith("image/png")
                    || normalizedMime.startsWith("image/webp");
        }
    }
}
