package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.DriveFolderBinding;
import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.SchoolDriveScope;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DriveFolderProvisioningService {
    private final DriveFolderProvisioningRepository repository;
    private final GoogleDrivePhotoImportClient drive;

    public DriveFolderProvisioningService(
            DriveFolderProvisioningRepository repository,
            GoogleDrivePhotoImportClient drive) {
        this.repository = repository;
        this.drive = drive;
    }

    public boolean isConfigured() {
        return drive.isProvisioningEnabled();
    }

    public Optional<DriveFolderBinding> binding(long schoolId, String academicYearId) {
        return repository.find(schoolId, academicYearId);
    }

    public ProvisioningResult ensureForSchool(long schoolId) {
        SchoolDriveScope scope = repository.currentScope(schoolId);
        if (!drive.isProvisioningEnabled()) {
            return ProvisioningResult.notConfigured(scope);
        }

        Optional<DriveFolderBinding> current = repository.find(schoolId, scope.academicYearId());
        if (current.filter(binding -> "READY".equals(binding.status())
                && drive.rootFolderId().equals(binding.rootFolderId())).isPresent()) {
            return ProvisioningResult.from(scope, current.get());
        }

        String rootFolderId;
        try {
            rootFolderId = drive.rootFolderId();
        } catch (RuntimeException ex) {
            return ProvisioningResult.failed(scope, ex.getMessage());
        }
        repository.markProvisioning(scope, rootFolderId);
        try {
            var folders = drive.provisionSchoolFolders(
                    scope.schoolUid(),
                    scope.shortCode(),
                    scope.schoolName(),
                    scope.academicYearId(),
                    scope.academicYearLabel());
            return ProvisioningResult.from(scope, repository.markReady(scope, rootFolderId, folders));
        } catch (RuntimeException ex) {
            DriveFolderBinding failed = repository.markFailed(scope, rootFolderId, ex.getMessage());
            return ProvisioningResult.from(scope, failed);
        }
    }

    public record ProvisioningResult(
            long schoolId,
            String schoolUid,
            String schoolName,
            String shortCode,
            String academicYearId,
            String academicYearLabel,
            String status,
            String folderId,
            String folderName,
            String folderUrl,
            String error) {
        static ProvisioningResult from(SchoolDriveScope scope, DriveFolderBinding binding) {
            return new ProvisioningResult(
                    scope.schoolId(),
                    scope.schoolUid(),
                    scope.schoolName(),
                    scope.shortCode(),
                    scope.academicYearId(),
                    scope.academicYearLabel(),
                    binding.status(),
                    binding.intakeFolderId(),
                    binding.intakeFolderName(),
                    binding.intakeFolderUrl(),
                    binding.lastError());
        }

        static ProvisioningResult notConfigured(SchoolDriveScope scope) {
            return new ProvisioningResult(
                    scope.schoolId(),
                    scope.schoolUid(),
                    scope.schoolName(),
                    scope.shortCode(),
                    scope.academicYearId(),
                    scope.academicYearLabel(),
                    "NOT_CONFIGURED",
                    null,
                    null,
                    null,
                    "A Shared Drive root folder has not been configured for this environment");
        }

        static ProvisioningResult failed(SchoolDriveScope scope, String error) {
            return new ProvisioningResult(
                    scope.schoolId(),
                    scope.schoolUid(),
                    scope.schoolName(),
                    scope.shortCode(),
                    scope.academicYearId(),
                    scope.academicYearLabel(),
                    "FAILED",
                    null,
                    null,
                    null,
                    error == null || error.isBlank() ? "Drive folder provisioning failed" : error);
        }
    }
}
