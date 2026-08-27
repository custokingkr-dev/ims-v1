package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.photoimport.GoogleDrivePhotoImportClient.DriveFile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDrivePhotoImportClientTest {

    @Test
    void enablesDriveOnlyWithCompletePersonalOauthCredentials() {
        var configured = client(true, "root-folder", "client-id", "client-secret", "refresh-token");
        var missingRefreshToken = client(true, "root-folder", "client-id", "client-secret", "");

        assertThat(configured.isEnabled()).isTrue();
        assertThat(configured.isProvisioningEnabled()).isTrue();
        assertThat(missingRefreshToken.isEnabled()).isFalse();
        assertThat(missingRefreshToken.isProvisioningEnabled()).isFalse();
    }

    @Test
    void requiresRootFolderInAdditionToPersonalOauthCredentialsForProvisioning() {
        var configuredWithoutRoot = client(true, "", "client-id", "client-secret", "refresh-token");

        assertThat(configuredWithoutRoot.isEnabled()).isTrue();
        assertThat(configuredWithoutRoot.isProvisioningEnabled()).isFalse();
    }

    @Test
    void validatesManagedFolderUsingParentAndImmutableProperties() {
        Map<String, Object> metadata = Map.of(
                "mimeType", "application/vnd.google-apps.folder",
                "trashed", false,
                "parents", List.of("year-folder"),
                "appProperties", Map.of(
                        "custokingType", "student-photo-intake",
                        "custokingSchoolUid", "school-uid",
                        "custokingAcademicYearId", "ay-2026"));
        Map<String, String> expected = Map.of(
                "custokingType", "student-photo-intake",
                "custokingSchoolUid", "school-uid",
                "custokingAcademicYearId", "ay-2026");

        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                metadata, "year-folder", expected)).isTrue();
        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                metadata, "different-parent", expected)).isFalse();
        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                metadata, "year-folder", Map.of(
                        "custokingType", "student-photo-intake",
                        "custokingSchoolUid", "school-uid",
                        "custokingAcademicYearId", "ay-2025"))).isFalse();
    }

    @Test
    void acceptsAFolderWhoseAppPropertiesAnotherCredentialWrote() {
        // Drive appProperties are private to the application that wrote them, so after a credential
        // change the same folder comes back with none visible. The id came from our own database and the
        // structural checks still hold, so it must be recognised -- otherwise provisioning creates a
        // duplicate beside the folder photographers are already uploading to.
        Map<String, Object> invisibleProperties = Map.of(
                "mimeType", "application/vnd.google-apps.folder",
                "trashed", false,
                "parents", List.of("year-folder"));

        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                invisibleProperties, "year-folder", Map.of(
                        "custokingType", "student-photo-intake",
                        "custokingSchoolUid", "school-uid"))).isTrue();

        // An empty map is the same situation and must behave identically.
        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                Map.of(
                        "mimeType", "application/vnd.google-apps.folder",
                        "trashed", false,
                        "parents", List.of("year-folder"),
                        "appProperties", Map.of()),
                "year-folder", Map.of("custokingType", "student-photo-intake"))).isTrue();

        // Structure is still enforced: a wrong parent is rejected even with no appProperties to check.
        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                invisibleProperties, "some-other-parent", Map.of(
                        "custokingType", "student-photo-intake"))).isFalse();
    }

    @Test
    void stillRejectsVisibleAppPropertiesThatDisagree() {
        // When appProperties ARE visible they remain authoritative, so a folder belonging to a different
        // school or year is not silently accepted.
        Map<String, Object> wrongSchool = Map.of(
                "mimeType", "application/vnd.google-apps.folder",
                "trashed", false,
                "parents", List.of("year-folder"),
                "appProperties", Map.of("custokingSchoolUid", "a-different-school"));

        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                wrongSchool, "year-folder", Map.of("custokingSchoolUid", "school-uid"))).isFalse();
    }

    @Test
    void rejectsTrashedFoldersAndRenamedNonImageFiles() {
        Map<String, Object> trashed = Map.of(
                "mimeType", "application/vnd.google-apps.folder",
                "trashed", true,
                "parents", List.of("year-folder"),
                "appProperties", Map.of("custokingType", "student-photo-intake"));

        assertThat(GoogleDrivePhotoImportClient.matchesManagedFolderMetadata(
                trashed, "year-folder", Map.of("custokingType", "student-photo-intake"))).isFalse();
        assertThat(new GoogleDrivePhotoImportClient.DriveFile(
                "file-1", "DSC5236.jpg", "application/pdf", 100L, null, null)
                .isSupportedImage()).isFalse();
        assertThat(new GoogleDrivePhotoImportClient.DriveFile(
                "file-2", "DSC5236.jpg", "image/jpeg", 100L, null, null)
                .isSupportedImage()).isTrue();
        assertThat(new GoogleDrivePhotoImportClient.DriveFile(
                "file-3", "_DSC5236.jpeg", "image/pjpeg", 100L, null, null)
                .isSupportedImage()).isTrue();
        assertThat(new GoogleDrivePhotoImportClient.DriveFile(
                "file-4", "_DSC5236.webp", "image/webp", 100L, null, null)
                .isSupportedImage()).isTrue();
    }

    @Test
    void recognizesOnlySupportedMappingFileExtensions() {
        for (String name : List.of("mapping.xlsx", "mapping.XLS", "mapping.csv", "mapping.tsv")) {
            assertThat(new GoogleDrivePhotoImportClient.DriveFile(
                    "file-1", name, "application/octet-stream", 100L, null, null)
                    .isMappingFile()).as(name).isTrue();
        }
        assertThat(new GoogleDrivePhotoImportClient.DriveFile(
                "file-2", "mapping.ods", "application/octet-stream", 100L, null, null)
                .isMappingFile()).isFalse();
        assertThat(new GoogleDrivePhotoImportClient.DriveFile(
                "folder", "mapping.csv", "application/vnd.google-apps.folder", null, null, null)
                .isMappingFile()).isFalse();
    }

    @Test
    void snapshotFingerprintUsesDriveSha256EvenWhenLegacyMd5MetadataMatches() {
        var client = client(true, "root-folder", "client-id", "client-secret", "refresh-token");
        DriveFile original = new DriveFile(
                "file-1", "DSC5236.jpg", "image/jpeg", 100L,
                "same-legacy-md5", "a".repeat(64), "2026-07-31T00:00:00Z");
        DriveFile collisionAttempt = new DriveFile(
                original.id(), original.name(), original.mimeType(), original.size(),
                original.md5Checksum(), "b".repeat(64), original.modifiedTime());

        assertThat(client.snapshotHash(List.of(original)))
                .isNotEqualTo(client.snapshotHash(List.of(collisionAttempt)));
    }

    @Test
    void snapshotFingerprintTreatsLegacyMd5AsDiagnosticOnly() {
        var client = client(true, "root-folder", "client-id", "client-secret", "refresh-token");
        DriveFile original = new DriveFile(
                "file-1", "DSC5236.jpg", "image/jpeg", 100L,
                "legacy-md5-one", "a".repeat(64), "2026-07-31T00:00:00Z");
        DriveFile diagnosticMetadataChange = new DriveFile(
                original.id(), original.name(), original.mimeType(), original.size(),
                "legacy-md5-two", original.sha256Checksum(), original.modifiedTime());

        assertThat(client.snapshotHash(List.of(original)))
                .isEqualTo(client.snapshotHash(List.of(diagnosticMetadataChange)));
    }

    @Test
    void snapshotFingerprintUsesDriveRevisionWhenSha256IsUnavailable() {
        var client = client(true, "root-folder", "client-id", "client-secret", "refresh-token");
        DriveFile original = new DriveFile(
                "file-1", "DSC5236.jpg", "image/jpeg", 100L,
                "same-legacy-md5", null, "revision-1", "15", "2026-08-27T11:28:19Z");
        DriveFile changedRevision = new DriveFile(
                original.id(), original.name(), original.mimeType(), original.size(),
                original.md5Checksum(), null, "revision-2", "16", original.modifiedTime());

        assertThat(client.snapshotHash(List.of(original)))
                .isNotEqualTo(client.snapshotHash(List.of(changedRevision)));
    }

    private static GoogleDrivePhotoImportClient client(
            boolean enabled,
            String rootFolder,
            String clientId,
            String clientSecret,
            String refreshToken) {
        return client(enabled, rootFolder, clientId, clientSecret, refreshToken, "user");
    }

    private static GoogleDrivePhotoImportClient client(
            boolean enabled,
            String rootFolder,
            String clientId,
            String clientSecret,
            String refreshToken,
            String credentialMode) {
        return new GoogleDrivePhotoImportClient(
                new ObjectMapper(),
                enabled,
                rootFolder,
                clientId,
                clientSecret,
                refreshToken,
                credentialMode);
    }

    @Test
    void serviceAccountModeNeedsNoPersonalOauthCredentials() {
        // In service-account mode the runtime identity supplies the credential, so the three personal
        // OAuth settings are irrelevant and their absence must not disable Drive.
        GoogleDrivePhotoImportClient serviceAccount =
                client(true, "root-folder", "", "", "", "service-account");
        assertThat(serviceAccount.isEnabled()).isTrue();
        assertThat(serviceAccount.isProvisioningEnabled()).isTrue();
        assertThat(serviceAccount.credentialMode()).isEqualTo("service-account");
    }

    @Test
    void defaultsToPersonalOauthSoExistingDeploymentsAreUnaffected() {
        GoogleDrivePhotoImportClient defaulted = client(true, "root-folder", "id", "secret", "token");
        assertThat(defaulted.credentialMode()).isEqualTo("user");
        assertThat(defaulted.isEnabled()).isTrue();

        // Same settings but with the personal credentials missing must still disable Drive in user mode.
        assertThat(client(true, "root-folder", "", "", "").isEnabled()).isFalse();
    }
}
