package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.DriveFolderBinding;
import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.SchoolDriveScope;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveFolderProvisioningServiceTest {
    private final DriveFolderProvisioningRepository repository = mock(DriveFolderProvisioningRepository.class);
    private final GoogleDrivePhotoImportClient drive = mock(GoogleDrivePhotoImportClient.class);
    private final DriveFolderProvisioningService service =
            new DriveFolderProvisioningService(repository, drive);

    @Test
    void provisionsManagedHierarchyAndPersistsImmutableFolderIds() {
        SchoolDriveScope scope = scope();
        var folders = new GoogleDrivePhotoImportClient.ProvisionedFolders(
                "school-folder", "year-folder", "intake-folder",
                "Student Photo Intake", "https://drive.google.com/drive/folders/intake-folder");
        DriveFolderBinding ready = binding("READY", null);
        when(repository.currentScope(7L)).thenReturn(scope);
        when(drive.isProvisioningEnabled()).thenReturn(true);
        when(drive.rootFolderId()).thenReturn("root-folder");
        when(repository.find(7L, "ay_2026_27")).thenReturn(Optional.empty());
        when(drive.provisionSchoolFolders(
                scope.schoolUid(), scope.shortCode(), scope.schoolName(),
                scope.academicYearId(), scope.academicYearLabel())).thenReturn(folders);
        when(repository.markReady(scope, "root-folder", folders)).thenReturn(ready);

        var result = service.ensureForSchool(7L);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.folderId()).isEqualTo("intake-folder");
        verify(repository).markProvisioning(scope, "root-folder");
        verify(repository).markReady(scope, "root-folder", folders);
    }

    @Test
    void returnsExistingReadyBindingWithoutCreatingDuplicateDriveFolders() {
        SchoolDriveScope scope = scope();
        DriveFolderBinding ready = binding("READY", null);
        when(repository.currentScope(7L)).thenReturn(scope);
        when(drive.isProvisioningEnabled()).thenReturn(true);
        when(drive.rootFolderId()).thenReturn("root-folder");
        when(repository.find(7L, "ay_2026_27")).thenReturn(Optional.of(ready));

        var result = service.ensureForSchool(7L);

        assertThat(result.status()).isEqualTo("READY");
        verify(drive, never()).provisionSchoolFolders(
                scope.schoolUid(), scope.shortCode(), scope.schoolName(),
                scope.academicYearId(), scope.academicYearLabel());
        verify(repository, never()).markProvisioning(scope, "root-folder");
    }

    @Test
    void recordsDriveFailureWithoutUndoingSchoolOnboarding() {
        SchoolDriveScope scope = scope();
        DriveFolderBinding failed = binding("FAILED", "Shared Drive access denied");
        when(repository.currentScope(7L)).thenReturn(scope);
        when(drive.isProvisioningEnabled()).thenReturn(true);
        when(drive.rootFolderId()).thenReturn("root-folder");
        when(repository.find(7L, "ay_2026_27")).thenReturn(Optional.empty());
        when(drive.provisionSchoolFolders(
                scope.schoolUid(), scope.shortCode(), scope.schoolName(),
                scope.academicYearId(), scope.academicYearLabel()))
                .thenThrow(new DrivePhotoImportException("drive_access_denied", "Shared Drive access denied"));
        when(repository.markFailed(scope, "root-folder", "Shared Drive access denied")).thenReturn(failed);

        var result = service.ensureForSchool(7L);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).isEqualTo("Shared Drive access denied");
        verify(repository).markFailed(scope, "root-folder", "Shared Drive access denied");
    }

    private static SchoolDriveScope scope() {
        return new SchoolDriveScope(
                7L,
                "11111111-1111-4111-8111-111111111111",
                "Green Valley School",
                "GVS",
                "ay_2026_27",
                "2026-27");
    }

    private static DriveFolderBinding binding(String status, String error) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-31T00:00:00Z");
        return new DriveFolderBinding(
                7L,
                "11111111-1111-4111-8111-111111111111",
                "ay_2026_27",
                "root-folder",
                "school-folder",
                "year-folder",
                "intake-folder",
                "Student Photo Intake",
                "https://drive.google.com/drive/folders/intake-folder",
                status,
                error,
                now,
                now);
    }
}
