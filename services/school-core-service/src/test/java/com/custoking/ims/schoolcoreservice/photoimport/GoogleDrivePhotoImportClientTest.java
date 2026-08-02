package com.custoking.ims.schoolcoreservice.photoimport;

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

    private static GoogleDrivePhotoImportClient client(
            boolean enabled,
            String rootFolder,
            String clientId,
            String clientSecret,
            String refreshToken) {
        return new GoogleDrivePhotoImportClient(
                new ObjectMapper(),
                enabled,
                rootFolder,
                clientId,
                clientSecret,
                refreshToken);
    }
}
