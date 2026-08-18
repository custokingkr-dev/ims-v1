package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.DriveFolderBinding;
import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.SchoolDriveScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DriveFolderProvisioningService {
    /**
     * Provisioning deliberately fails soft so that a Drive outage cannot block school creation. That made
     * it silent: the failure reached only the returned result and the database column, never Cloud
     * Logging, so no metric or alert could see it and folders could fail for every new school unnoticed.
     */
    private static final Logger log = LoggerFactory.getLogger(DriveFolderProvisioningService.class);

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
        Optional<DriveFolderBinding> binding = repository.find(schoolId, academicYearId);
        if (binding.isEmpty() || !drive.isProvisioningEnabled()) {
            return Optional.empty();
        }
        try {
            String rootFolderId = drive.rootFolderId();
            return binding.filter(candidate -> rootFolderId.equals(candidate.rootFolderId()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public ProvisioningResult ensureForSchool(long schoolId) {
        SchoolDriveScope scope = repository.currentScope(schoolId);
        if (!drive.isProvisioningEnabled()) {
            return ProvisioningResult.notConfigured(scope);
        }

        String rootFolderId;
        try {
            rootFolderId = drive.rootFolderId();
        } catch (RuntimeException ex) {
            log.warn("drive.folder.provisioning.failed schoolId={} academicYearId={} reason={}",
                    scope.schoolId(), scope.academicYearId(), ex.getMessage());
            return ProvisioningResult.failed(scope, ex.getMessage());
        }

        Optional<DriveFolderBinding> current = repository.find(schoolId, scope.academicYearId());
        boolean replaceReadyBinding = false;
        if (current.filter(binding -> "READY".equals(binding.status())
                && rootFolderId.equals(binding.rootFolderId())).isPresent()) {
            DriveFolderBinding ready = current.get();
            try {
                if (drive.managedHierarchyMatches(
                        rootFolderId,
                        ready.schoolFolderId(),
                        ready.academicYearFolderId(),
                        ready.intakeFolderId(),
                        scope.schoolUid(),
                        scope.academicYearId())) {
                    return ProvisioningResult.from(scope, ready);
                }
                replaceReadyBinding = true;
            } catch (DrivePhotoImportException ex) {
                if (!"drive_access_denied".equals(ex.code()) && !"not_a_folder".equals(ex.code())) {
                    log.warn("drive.folder.provisioning.failed schoolId={} academicYearId={} reason={}",
                            scope.schoolId(), scope.academicYearId(), ex.getMessage());
                    return ProvisioningResult.failed(scope, ex.getMessage());
                }
                replaceReadyBinding = true;
            } catch (RuntimeException ex) {
                log.warn("drive.folder.provisioning.failed schoolId={} academicYearId={} reason={}",
                        scope.schoolId(), scope.academicYearId(), ex.getMessage());
                return ProvisioningResult.failed(scope, ex.getMessage());
            }
        }

        Optional<DriveFolderBinding> claimed = repository.claimProvisioning(
                scope, rootFolderId, replaceReadyBinding);
        if (claimed.isEmpty()) {
            return repository.find(schoolId, scope.academicYearId())
                    .map(binding -> ProvisioningResult.from(scope, binding))
                    .orElseGet(() -> ProvisioningResult.inProgress(scope));
        }
        DriveFolderBinding claim = claimed.get();
        try {
            var folders = drive.provisionSchoolFolders(
                    scope.schoolUid(),
                    scope.shortCode(),
                    scope.schoolName(),
                    scope.academicYearId(),
                    scope.academicYearLabel());
            return ProvisioningResult.from(
                    scope,
                    repository.markReady(scope, rootFolderId, claim.version(), folders));
        } catch (RuntimeException ex) {
            DriveFolderBinding failed = repository.markFailed(
                    scope, rootFolderId, claim.version(), ex.getMessage());
            return ProvisioningResult.from(scope, failed);
        }
    }

    public ProvisioningResult statusForSchool(long schoolId) {
        SchoolDriveScope scope = repository.currentScope(schoolId);
        if (!drive.isProvisioningEnabled()) return ProvisioningResult.notConfigured(scope);
        String rootFolderId;
        try {
            rootFolderId = drive.rootFolderId();
        } catch (RuntimeException ex) {
            log.warn("drive.folder.provisioning.failed schoolId={} academicYearId={} reason={}",
                    scope.schoolId(), scope.academicYearId(), ex.getMessage());
            return ProvisioningResult.failed(scope, ex.getMessage());
        }
        return repository.find(schoolId, scope.academicYearId())
                .map(binding -> rootFolderId.equals(binding.rootFolderId())
                        ? ProvisioningResult.from(scope, binding)
                        : ProvisioningResult.failed(scope, "Drive binding belongs to a different configured root; reprovision it"))
                .orElseGet(() -> ProvisioningResult.pending(scope));
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
                    "Connect a personal Google Drive account and configure its intake root folder");
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

        static ProvisioningResult inProgress(SchoolDriveScope scope) {
            return new ProvisioningResult(
                    scope.schoolId(),
                    scope.schoolUid(),
                    scope.schoolName(),
                    scope.shortCode(),
                    scope.academicYearId(),
                    scope.academicYearLabel(),
                    "PROVISIONING",
                    null,
                    null,
                    null,
                    "Drive folder provisioning is already in progress; retry shortly");
        }

        static ProvisioningResult pending(SchoolDriveScope scope) {
            return new ProvisioningResult(
                    scope.schoolId(), scope.schoolUid(), scope.schoolName(), scope.shortCode(),
                    scope.academicYearId(), scope.academicYearLabel(), "PENDING",
                    null, null, null, "Drive intake folder has not been provisioned");
        }
    }
}
