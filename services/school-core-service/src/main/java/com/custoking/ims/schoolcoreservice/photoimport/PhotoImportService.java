package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.DriveFolderBinding;
import com.custoking.ims.schoolcoreservice.photoimport.GoogleDrivePhotoImportClient.DriveFile;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.Batch;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.ImportRow;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RecoveryPreparation;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RecoveryProgress;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RecoveryResult;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RowInput;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.SourceInput;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.StudentMatch;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportWorkbookParser.WorkbookRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PhotoImportService {
    private static final long MAX_SOURCE_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final String PHOTO_RECOVERY_VERSION = "fit-without-crop-v1";
    private static final int EXECUTION_CHUNK_SIZE = 1;
    private static final Map<String, Integer> ROMAN_CLASSES = Map.ofEntries(
            Map.entry("I", 1), Map.entry("II", 2), Map.entry("III", 3),
            Map.entry("IV", 4), Map.entry("V", 5), Map.entry("VI", 6),
            Map.entry("VII", 7), Map.entry("VIII", 8), Map.entry("IX", 9),
            Map.entry("X", 10), Map.entry("XI", 11), Map.entry("XII", 12));

    private final PhotoImportRepository repository;
    private final GoogleDrivePhotoImportClient drive;
    private final PhotoImportWorkbookParser parser;
    private final StudentPhotoStorage photoStorage;
    private final DriveFolderProvisioningService folderProvisioning;

    public PhotoImportService(
            PhotoImportRepository repository,
            GoogleDrivePhotoImportClient drive,
            PhotoImportWorkbookParser parser,
            StudentPhotoStorage photoStorage,
            DriveFolderProvisioningService folderProvisioning) {
        this.repository = repository;
        this.drive = drive;
        this.parser = parser;
        this.photoStorage = photoStorage;
        this.folderProvisioning = folderProvisioning;
    }

    public Map<String, Object> context() {
        requireAccess();
        return Map.of(
                "driveConfigured", drive.isEnabled(),
                "managedDriveConfigured", folderProvisioning.isConfigured(),
                "schools", repository.allowedSchools().stream().map(this::schoolContext).toList(),
                "mappingColumns", List.of("AdmissionNo", "Name", "Class", "Section", "ImageNo"),
                "mappingFileFormats", List.of("XLSX", "XLS", "CSV", "TSV"),
                "mappingRowLimit", PhotoImportWorkbookParser.MAX_ROWS,
                "imageFileLimit", "20 MB per image; stored portraits are reduced to JPEG",
                "fileNameRule", "DSC5236.jpg, DSC_05236.JPG, or _DSC4521.jpeg");
    }

    public Batch create(long requestedSchoolId, String academicYearId, String driveFolderUrl) {
        requireAccess();
        long schoolId = TenantScope.resolveOperationsWriteScope(requestedSchoolId);
        requireStudentsModule(schoolId);
        if (academicYearId == null || academicYearId.isBlank()) {
            throw new IllegalArgumentException("academicYearId is required");
        }
        String yearId = academicYearId.trim();
        String source = driveFolderUrl == null ? "" : driveFolderUrl.trim();
        if (folderProvisioning.isConfigured()) {
            var managed = folderProvisioning.ensureForSchool(schoolId);
            if (!yearId.equals(managed.academicYearId())) {
                throw new IllegalArgumentException(
                        "The selected academic year is no longer current for this school");
            }
            if (!"READY".equals(managed.status()) || managed.folderId() == null) {
                throw new IllegalArgumentException(managed.error() == null
                        ? "The managed Drive intake folder is not ready for this school and academic year"
                        : managed.error());
            }
            if (!source.isBlank() && !managed.folderId().equals(DriveFolderId.parse(source))) {
                throw new IllegalArgumentException(
                        "Use the managed Drive intake folder assigned to this school and academic year");
            }
            source = managed.folderId();
        } else if (source.isBlank()) {
            throw new IllegalArgumentException("A Google Drive folder link is required");
        }
        var folder = drive.readFolder(source);
        return repository.createBatch(
                schoolId,
                yearId,
                folder.id(),
                folder.name(),
                TenantContext.get().userId());
    }

    public DriveFolderProvisioningService.ProvisioningResult provision(long requestedSchoolId) {
        requireAccess();
        long schoolId = TenantScope.resolveOperationsWriteScope(requestedSchoolId);
        requireStudentsModule(schoolId);
        return folderProvisioning.ensureForSchool(schoolId);
    }

    public List<Batch> list(long requestedSchoolId) {
        requireAccess();
        long schoolId = TenantScope.resolveOperationsWriteScope(requestedSchoolId);
        return repository.listBatches(schoolId);
    }

    public Batch get(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        return repository.batch(id, schoolId);
    }

    public Map<String, Object> detail(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        return Map.of(
                "batch", repository.batch(id, schoolId),
                "rows", repository.rows(id, schoolId),
                "access", repository.accessState(id, schoolId),
                "recoveryProgress", repository.photoRecoveryProgress(
                        id, schoolId, PHOTO_RECOVERY_VERSION));
    }

    public RecoveryProgress recoveryProgress(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        return repository.photoRecoveryProgress(id, schoolId, PHOTO_RECOVERY_VERSION);
    }

    public Batch scan(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        Batch batch = repository.batch(id, schoolId);
        List<DriveFile> files = drive.listFiles(batch.driveFolderId());
        List<DriveFile> workbooks = files.stream().filter(DriveFile::isMappingFile).toList();
        if (workbooks.size() != 1) {
            throw new IllegalArgumentException(workbooks.isEmpty()
                    ? "The Drive folder must contain one XLSX, XLS, CSV, or TSV mapping file"
                    : "The Drive folder contains multiple mapping files; keep exactly one XLSX, XLS, CSV, or TSV file");
        }
        DriveFile workbook = workbooks.getFirst();
        files.stream()
                .filter(file -> file.isMappingFile() || file.isSupportedImage())
                .forEach(PhotoImportService::requireDriveSha256);
        String snapshotHash = drive.snapshotHash(files);
        if (repository.terminalSnapshotExists(
                id, schoolId, batch.driveFolderId(), snapshotHash)) {
            throw new DrivePhotoImportException(
                    "source_already_imported",
                    "This exact Drive folder snapshot was already processed; replace the workbook or photos before starting another job");
        }
        byte[] workbookBytes = drive.download(workbook, PhotoImportWorkbookParser.MAX_WORKBOOK_BYTES);
        requireDownloadedSha256(workbook.sha256Checksum(), workbookBytes);
        var parsed = parser.parse(workbookBytes, workbook.name());
        String workbookObjectKey = photoStorage.uploadImportFile(
                batch.schoolUid(),
                "photo-import-" + id,
                workbookBytes,
                workbook.mimeType(),
                workbook.name());

        Map<String, List<DriveFile>> imagesByNumber = new LinkedHashMap<>();
        for (DriveFile file : files) {
            if (file.isSupportedImage()) {
                DscImageNumber.fromFileName(file.name()).ifPresent(imageNo ->
                        imagesByNumber.computeIfAbsent(imageNo, ignored -> new ArrayList<>()).add(file));
            }
        }

        Map<String, Long> workbookAdmissions = parsed.rows().stream()
                .map(WorkbookRow::admissionNo)
                .filter(value -> value != null && !value.isBlank())
                .map(PhotoImportService::normalizedIdentifier)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Long> workbookImages = parsed.rows().stream()
                .map(WorkbookRow::imageNo)
                .filter(value -> value != null && !value.isBlank() && value.trim().matches("[0-9]+"))
                .map(DscImageNumber::canonical)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<RowInput> rows = new ArrayList<>();
        for (WorkbookRow workbookRow : parsed.rows()) {
            rows.add(validateRow(batch, workbookRow, imagesByNumber, workbookAdmissions, workbookImages));
        }

        List<SourceInput> sources = files.stream()
                .map(file -> new SourceInput(
                        file.id(),
                        file.name(),
                        file.mimeType(),
                        file.size(),
                        file.md5Checksum(),
                        normalizedOptionalSha256(file.sha256Checksum()),
                        file.modifiedTime(),
                        file.id().equals(workbook.id()) ? "WORKBOOK"
                                : (file.isSupportedImage()
                                        && DscImageNumber.fromFileName(file.name()).isPresent()
                                        ? "IMAGE" : "OTHER"),
                        file.isSupportedImage()
                                ? DscImageNumber.fromFileName(file.name()).orElse(null)
                                : null))
                .toList();
        return repository.replaceScan(
                id,
                schoolId,
                workbook.id(),
                workbook.name(),
                workbookObjectKey,
                snapshotHash,
                sources,
                rows);
    }

    public Map<String, Object> updateRow(UUID batchId, UUID rowId, RowReviewUpdate update) {
        long schoolId = authorizedBatchSchool(batchId);
        Batch batch = repository.batch(batchId, schoolId);
        if (!"REVIEW".equals(batch.status())) {
            throw new IllegalArgumentException("Rows can only be changed while the batch is in review");
        }
        List<ImportRow> rows = repository.rows(batchId, schoolId);
        ImportRow current = rows.stream()
                .filter(row -> row.id().equals(rowId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Photo import row not found"));
        double cropX = cropCoordinate(update.cropX(), current.cropX(), "cropX");
        double cropY = cropCoordinate(update.cropY(), current.cropY(), "cropY");

        RowInput reviewed;
        if (Boolean.TRUE.equals(update.excluded())) {
            reviewed = new RowInput(
                    current.excelRow(),
                    current.admissionNo(),
                    current.workbookName(),
                    current.className(),
                    current.sectionName(),
                    current.imageNo(),
                    null,
                    null,
                    null,
                    "EXCLUDED",
                    "Excluded by operator",
                    current.priorPhotoKey(),
                    null);
        } else {
            String admissionNo = update.admissionNo() == null
                    ? current.admissionNo() : clean(update.admissionNo());
            String imageNo = update.imageNo() == null ? current.imageNo() : clean(update.imageNo());
            Map<String, List<DriveFile>> imagesByNumber = imageFilesByNumber(
                    repository.sourceFiles(batchId, schoolId));
            Map<String, Long> workbookAdmissions = effectiveCounts(
                    rows, current.id(), admissionNo, ImportRow::admissionNo, PhotoImportService::normalizedIdentifier);
            Map<String, Long> workbookImages = effectiveCounts(
                    rows, current.id(), imageNo, ImportRow::imageNo, PhotoImportService::canonicalImageIdentifier);
            reviewed = validateRow(
                    batch,
                    new WorkbookRow(
                            current.excelRow(), admissionNo, current.workbookName(),
                            current.className(), current.sectionName(), imageNo),
                    imagesByNumber,
                    workbookAdmissions,
                    workbookImages);
        }
        ImportRow saved = repository.updateReviewRow(
                batchId, schoolId, rowId, reviewed, cropX, cropY);
        return Map.of(
                "batch", repository.batch(batchId, schoolId),
                "row", saved);
    }

    public Batch freeze(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        Batch batch = repository.batch(id, schoolId);
        List<DriveFile> currentFiles = drive.listFiles(batch.driveFolderId());
        String currentHash = drive.snapshotHash(currentFiles);
        if (!currentHash.equals(batch.snapshotHash())) {
            throw new DrivePhotoImportException("source_changed",
                    "The Drive folder changed after scanning. Scan the batch again before freezing it");
        }
        return repository.freeze(id, schoolId, currentHash);
    }

    public Batch cancel(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        return repository.cancel(id, schoolId, TenantContext.get().userId());
    }

    public PhotoImportRepository.AccessState markAccessRevoked(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        return repository.markAccessRevoked(id, schoolId);
    }

    public Batch execute(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        Batch reviewed = repository.batch(id, schoolId);
        if (!Set.of("FROZEN", "EXECUTING", "PARTIAL", "FAILED").contains(reviewed.status())) {
            throw new IllegalArgumentException("Only a frozen, executing or retryable batch can be executed");
        }
        String currentYear = repository.currentAcademicYearId(schoolId);
        if (!reviewed.academicYearId().equals(currentYear)) {
            throw new IllegalArgumentException(
                    "The school's current academic year changed; this batch can no longer be executed");
        }
        List<DriveFile> currentFiles = drive.listFiles(reviewed.driveFolderId());
        if (!drive.snapshotHash(currentFiles).equals(reviewed.snapshotHash())) {
            throw new DrivePhotoImportException("source_changed",
                    "The Drive folder changed after review. Execution was stopped before any writes");
        }
        Batch batch = repository.startExecution(id, schoolId, TenantContext.get().userId());
        Map<String, DriveFile> currentById = currentFiles.stream()
                .collect(Collectors.toMap(DriveFile::id, Function.identity()));
        List<ImportRow> readyRows = repository.rows(id, schoolId).stream()
                .filter(row -> "READY".equals(row.status()))
                .limit(EXECUTION_CHUNK_SIZE)
                .toList();
        for (ImportRow row : readyRows) {
            try {
                DriveFile source = currentById.get(row.driveFileId());
                if (source == null) {
                    throw new IllegalArgumentException("Source image is no longer present");
                }
                String expectedSha256 = requireSha256(
                        row.sourceSha256(), "The reviewed source has no certified SHA-256 checksum");
                String currentSha256 = requireDriveSha256(source);
                if (!expectedSha256.equals(currentSha256)) {
                    throw new IllegalArgumentException("Source image changed after review");
                }
                byte[] sourceBytes = drive.download(source, MAX_SOURCE_IMAGE_BYTES);
                requireDownloadedSha256(expectedSha256, sourceBytes);
                byte[] normalized = photoStorage.normalizePortrait(
                        sourceBytes,
                        source.mimeType(),
                        row.cropX(),
                        row.cropY(),
                        MAX_SOURCE_IMAGE_BYTES);
                repository.applyPhoto(batch, row, sourceBytes, source.mimeType(), normalized);
            } catch (Exception ex) {
                repository.markRowFailed(row.id(), schoolId, ex.getMessage());
            }
        }
        return repository.finishExecution(id, schoolId);
    }

    /**
     * Rebuilds selected, previously applied portraits from the immutable Drive file ids retained
     * by the import scan. Recovery is deliberately explicit and versioned: it never touches rows
     * that were not selected, and it refuses to overwrite a photo changed after the original
     * import. Repeating the same request returns the completed audit record without another write.
     */
    public RecoveryBatchResult recoverAppliedRows(UUID id, List<UUID> requestedRowIds) {
        long schoolId = authorizedBatchSchool(id);
        Batch batch = repository.batch(id, schoolId);
        if (requestedRowIds == null || requestedRowIds.isEmpty()) {
            throw new IllegalArgumentException("At least one photo-import row id is required");
        }
        if (requestedRowIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Photo-import row ids cannot be null");
        }
        List<UUID> rowIds = requestedRowIds.stream().distinct().toList();
        if (rowIds.size() > 100) {
            throw new IllegalArgumentException("At most 100 photo-import rows can be recovered per request");
        }

        List<RecoveryResult> results = new ArrayList<>();
        List<RecoveryPreparation> ready = new ArrayList<>();
        for (UUID rowId : rowIds) {
            try {
                RecoveryPreparation preparation = repository.beginPhotoRecovery(
                        id, schoolId, rowId, PHOTO_RECOVERY_VERSION, TenantContext.get().userId());
                switch (preparation.status()) {
                    case "READY" -> ready.add(preparation);
                    case "ALREADY_RECOVERED" -> results.add(new RecoveryResult(
                            rowId, "ALREADY_RECOVERED", preparation.target().finalPhotoKey(),
                            preparation.message()));
                    case "IN_PROGRESS" -> results.add(new RecoveryResult(
                            rowId, "IN_PROGRESS", null, preparation.message()));
                    case "PROTECTED" -> results.add(new RecoveryResult(
                            rowId, "PROTECTED", null, preparation.message()));
                    default -> results.add(new RecoveryResult(
                            rowId, "FAILED", null, preparation.message()));
                }
            } catch (Exception ex) {
                results.add(new RecoveryResult(rowId, "FAILED", null, safeRecoveryError(ex)));
            }
        }
        if (ready.isEmpty()) {
            return recoverySummary(id, schoolId, results);
        }

        Map<String, DriveFile> currentById;
        try {
            currentById = drive.listFiles(batch.driveFolderId()).stream()
                    .collect(Collectors.toMap(DriveFile::id, Function.identity(), (left, right) -> left));
        } catch (Exception ex) {
            for (RecoveryPreparation preparation : ready) {
                results.add(repository.failPhotoRecovery(
                        preparation.recoveryId(), preparation.target().rowId(), schoolId,
                        "Retained Drive originals are not accessible: " + safeRecoveryError(ex)));
            }
            return recoverySummary(id, schoolId, results);
        }

        for (RecoveryPreparation preparation : ready) {
            UUID rowId = preparation.target().rowId();
            try {
                DriveFile retained = repository.sourceFile(
                        id, schoolId, preparation.target().driveFileId());
                DriveFile current = currentById.get(preparation.target().driveFileId());
                if (current == null) {
                    throw new IllegalArgumentException("The retained Drive original is no longer present");
                }
                String certifiedSha256 = requireSha256(
                        preparation.target().sourceSha256(),
                        "This historical import has no certified SHA-256 checksum; manual recertification is required");
                if (!certifiedSha256.equals(requireSha256(
                        retained.sha256Checksum(),
                        "The retained Drive original has no certified SHA-256 checksum; manual recertification is required"))) {
                    throw new IllegalArgumentException("The retained Drive original changed after import");
                }
                requireUnchangedRecoverySource(retained, current);
                byte[] sourceBytes = drive.download(current, MAX_SOURCE_IMAGE_BYTES);
                requireDownloadedSha256(certifiedSha256, sourceBytes);
                byte[] normalized = photoStorage.normalizePortrait(
                        sourceBytes, current.mimeType(), 0.5, 0.5, MAX_SOURCE_IMAGE_BYTES);
                results.add(repository.completePhotoRecovery(
                        preparation, PHOTO_RECOVERY_VERSION, normalized));
            } catch (Exception ex) {
                String message = safeRecoveryError(ex);
                results.add(isPhotoProtectionDecision(message)
                        ? repository.protectPhotoRecovery(
                                preparation.recoveryId(), rowId, schoolId, message)
                        : repository.failPhotoRecovery(
                                preparation.recoveryId(), rowId, schoolId, message));
            }
        }
        return recoverySummary(id, schoolId, results);
    }

    public Preview preview(UUID batchId, UUID rowId) {
        long schoolId = authorizedBatchSchool(batchId);
        ImportRow row = repository.rows(batchId, schoolId).stream()
                .filter(candidate -> candidate.id().equals(rowId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Photo import row not found"));
        if (row.driveFileId() == null) {
            throw new IllegalArgumentException("This row has no matched Drive image");
        }
        DriveFile source = repository.sourceFile(batchId, schoolId, row.driveFileId());
        byte[] normalized = photoStorage.normalizePortrait(
                drive.download(source, MAX_SOURCE_IMAGE_BYTES),
                source.mimeType(),
                row.cropX(),
                row.cropY(),
                MAX_SOURCE_IMAGE_BYTES);
        return new Preview(normalized, "image/jpeg");
    }

    public String resultCsv(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        Batch batch = repository.batch(id, schoolId);
        StringBuilder csv = new StringBuilder(
                "School,AcademicYear,BatchId,ExcelRow,AdmissionNo,StudentName,ImageNo,DriveFile,Status,Message,AppliedAt,SourceEvidenceRetained\r\n");
        for (ImportRow row : repository.rows(id, schoolId)) {
            csv.append(csv(batch.schoolName())).append(',')
                    .append(csv(batch.academicYearLabel())).append(',')
                    .append(csv(batch.id().toString())).append(',')
                    .append(row.excelRow()).append(',')
                    .append(csv(row.admissionNo())).append(',')
                    .append(csv(row.workbookName())).append(',')
                    .append(csv(row.imageNo())).append(',')
                    .append(csv(row.driveFileName())).append(',')
                    .append(csv(row.status())).append(',')
                    .append(csv(row.message())).append(',')
                    .append(csv(row.appliedAt() == null ? null : row.appliedAt().toString())).append(',')
                    .append(row.sourceObjectKey() != null)
                    .append("\r\n");
        }
        return csv.toString();
    }

    private RowInput validateRow(
            Batch batch,
            WorkbookRow row,
            Map<String, List<DriveFile>> imagesByNumber,
            Map<String, Long> workbookAdmissions,
            Map<String, Long> workbookImages) {
        String admissionNo = clean(row.admissionNo());
        String imageNo = clean(row.imageNo());
        if (admissionNo.isBlank()) {
            return input(row, null, null, null, "ERROR", "AdmissionNo is required", null);
        }
        if (workbookAdmissions.getOrDefault(normalizedIdentifier(admissionNo), 0L) > 1) {
            return input(row, null, null, null, "ERROR", "Duplicate AdmissionNo in workbook", null);
        }
        if (imageNo.isBlank()) {
            return input(row, null, null, null, "HELD", "ImageNo is blank; no photo will be changed", null);
        }
        String canonicalImageNo;
        try {
            canonicalImageNo = DscImageNumber.canonical(imageNo);
        } catch (IllegalArgumentException ex) {
            return input(row, null, null, null, "ERROR", ex.getMessage(), null);
        }
        if (workbookImages.getOrDefault(canonicalImageNo, 0L) > 1) {
            return input(row, canonicalImageNo, null, null, "ERROR", "Duplicate ImageNo in workbook", null);
        }
        List<DriveFile> images = imagesByNumber.getOrDefault(canonicalImageNo, List.of());
        if (images.isEmpty()) {
            return input(row, canonicalImageNo, null, null, "ERROR",
                    "No DSC image in Drive matches ImageNo " + canonicalImageNo, null);
        }
        if (images.size() > 1) {
            return input(row, canonicalImageNo, null, null, "ERROR",
                    "Multiple Drive images match ImageNo " + canonicalImageNo, null);
        }
        DriveFile image = images.getFirst();
        if (image.size() != null && image.size() > MAX_SOURCE_IMAGE_BYTES) {
            return input(row, canonicalImageNo, image, null, "ERROR",
                    image.name() + " is larger than 20 MB", null);
        }
        StudentMatch student = repository.studentByAdmission(
                        batch.schoolId(), batch.academicYearId(), admissionNo)
                .orElse(null);
        if (student == null) {
            return input(row, canonicalImageNo, image, null, "ERROR",
                    "No active student with this AdmissionNo in the selected school and academic year", null);
        }

        List<String> warnings = new ArrayList<>();
        if (!normalizedText(row.name()).equals(normalizedText(student.fullName()))) {
            warnings.add("workbook name differs from student record");
        }
        if (!classMatches(row.className(), student)) {
            warnings.add("workbook class differs from student record");
        }
        if (!normalizedText(row.sectionName()).equals(normalizedText(student.sectionName()))) {
            warnings.add("workbook section differs from student record");
        }
        String message = warnings.isEmpty()
                ? "Matched by school, academic year and AdmissionNo"
                : "Matched by AdmissionNo; verify " + String.join(", ", warnings);
        return input(row, canonicalImageNo, image, student, "READY", message, student.photoKey());
    }

    private RowInput input(WorkbookRow row, String imageNo, DriveFile image, StudentMatch student,
                           String status, String message, String priorPhotoKey) {
        return new RowInput(
                row.excelRow(),
                clean(row.admissionNo()),
                clean(row.name()),
                clean(row.className()),
                clean(row.sectionName()),
                imageNo,
                image == null ? null : image.id(),
                image == null ? null : image.name(),
                student == null ? null : student.id(),
                status,
                message,
                priorPhotoKey,
                image == null ? null : image.md5Checksum(),
                image == null ? null : requireDriveSha256(image));
    }

    private static Map<String, List<DriveFile>> imageFilesByNumber(List<DriveFile> files) {
        Map<String, List<DriveFile>> result = new LinkedHashMap<>();
        for (DriveFile file : files) {
            if (file.isSupportedImage()) {
                DscImageNumber.fromFileName(file.name()).ifPresent(imageNo ->
                        result.computeIfAbsent(imageNo, ignored -> new ArrayList<>()).add(file));
            }
        }
        return result;
    }

    private static Map<String, Long> effectiveCounts(
            List<ImportRow> rows,
            UUID currentRowId,
            String replacement,
            Function<ImportRow, String> extractor,
            Function<String, String> normalizer) {
        Map<String, Long> counts = new HashMap<>();
        for (ImportRow row : rows) {
            String value;
            if (row.id().equals(currentRowId)) {
                value = replacement;
            } else {
                if ("EXCLUDED".equals(row.status())) {
                    continue;
                }
                value = extractor.apply(row);
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = normalizer.apply(value);
            if (normalized != null) {
                counts.merge(normalized, 1L, Long::sum);
            }
        }
        return counts;
    }

    private static String canonicalImageIdentifier(String value) {
        String cleaned = clean(value);
        return cleaned.matches("[0-9]+") ? DscImageNumber.canonical(cleaned) : null;
    }

    private static void requireUnchangedRecoverySource(DriveFile retained, DriveFile current) {
        if (!current.isSupportedImage()) {
            throw new IllegalArgumentException("The retained Drive source is no longer a supported image");
        }
        if (retained.size() != null && current.size() != null
                && !retained.size().equals(current.size())) {
            throw new IllegalArgumentException("The retained Drive original changed after import");
        }
        String retainedSha256 = requireSha256(
                retained.sha256Checksum(),
                "The retained Drive original has no certified SHA-256 checksum; manual recertification is required");
        String currentSha256 = requireSha256(
                current.sha256Checksum(),
                "The retained Drive original cannot be verified safely with SHA-256");
        if (!retainedSha256.equals(currentSha256)) {
            throw new IllegalArgumentException("The retained Drive original changed after import");
        }
    }

    private static void requireDownloadedSha256(String expectedSha256, byte[] sourceBytes) {
        String expected = requireSha256(
                expectedSha256, "The Drive source has no certified SHA-256 checksum");
        try {
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(sourceBytes));
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException("The downloaded Drive original failed checksum verification");
            }
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 checksum support is unavailable", ex);
        }
    }

    private static String requireDriveSha256(DriveFile file) {
        return requireSha256(
                file.sha256Checksum(),
                file.name() + " has no Google Drive SHA-256 checksum and cannot be imported safely");
    }

    private static String requireSha256(String value, String missingMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(missingMessage);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("The Google Drive SHA-256 checksum is malformed");
        }
        return normalized;
    }

    private static String normalizedOptionalSha256(String value) {
        return value == null || value.isBlank()
                ? null
                : requireSha256(value, "The Google Drive SHA-256 checksum is missing");
    }

    private static String safeRecoveryError(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank()
                ? "Photo recovery failed"
                : message.replaceAll("[\\r\\n]+", " ");
    }

    private RecoveryBatchResult recoverySummary(
            UUID batchId, long schoolId, List<RecoveryResult> results) {
        return new RecoveryBatchResult(
                batchId,
                schoolId,
                results.size(),
                countRecoveryStatus(results, "RECOVERED"),
                countRecoveryStatus(results, "ALREADY_RECOVERED"),
                countRecoveryStatus(results, "IN_PROGRESS"),
                countRecoveryStatus(results, "PROTECTED"),
                countRecoveryStatus(results, "FAILED"),
                List.copyOf(results),
                repository.photoRecoveryProgress(batchId, schoolId, PHOTO_RECOVERY_VERSION));
    }

    private static boolean isPhotoProtectionDecision(String message) {
        return message != null && message.startsWith("Student photo changed");
    }

    private static long countRecoveryStatus(List<RecoveryResult> results, String status) {
        return results.stream().filter(result -> status.equals(result.status())).count();
    }

    private static double cropCoordinate(Double requested, double current, String field) {
        double value = requested == null ? current : requested;
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private long authorizedBatchSchool(UUID id) {
        requireAccess();
        long actualSchoolId = repository.batchSchoolId(id);
        long schoolId = TenantScope.resolveOperationsWriteScope(actualSchoolId);
        requireStudentsModule(schoolId);
        return schoolId;
    }

    private void requireStudentsModule(long schoolId) {
        if (!repository.studentsModuleEnabled(schoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "STUDENTS module is not enabled for this school");
        }
    }

    private void requireAccess() {
        TenantScope.requireOperationsOrSuperAdmin();
        TenantScope.requirePermissionIfAuthenticated("student:photo-import");
    }

    private SchoolPhotoImportContext schoolContext(PhotoImportRepository.SchoolContext school) {
        DriveFolderBinding folder = folderProvisioning.binding(school.id(), school.academicYearId())
                .orElse(null);
        return new SchoolPhotoImportContext(
                school.id(),
                school.schoolUid(),
                school.name(),
                school.shortCode(),
                school.academicYearId(),
                school.academicYearLabel(),
                folder == null ? "NOT_PROVISIONED" : folder.status(),
                folder == null ? null : folder.intakeFolderId(),
                folder == null ? null : folder.intakeFolderName(),
                folder == null ? null : folder.intakeFolderUrl(),
                folder == null ? null : folder.lastError());
    }

    private static boolean classMatches(String workbookClass, StudentMatch student) {
        String normalized = clean(workbookClass).toUpperCase(Locale.ROOT);
        Integer roman = ROMAN_CLASSES.get(normalized);
        if (roman != null && student.classSortOrder() != null) {
            // Numeric classes are offset by three in the catalog because Nursery/LKG/UKG precede them.
            return roman.equals(student.classSortOrder()) || roman + 3 == student.classSortOrder();
        }
        String numeric = normalized.replaceFirst("^(CLASS|GRADE)\\s*", "");
        return normalizedText(numeric).equals(normalizedText(student.className()));
    }

    private static String normalizedIdentifier(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizedText(String value) {
        String normalized = Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public record Preview(byte[] bytes, String contentType) {
    }

    public record RecoveryBatchResult(
            UUID batchId,
            long schoolId,
            int selectedCount,
            long recoveredCount,
            long alreadyRecoveredCount,
            long inProgressCount,
            long protectedCount,
            long failedCount,
            List<RecoveryResult> rows,
            RecoveryProgress progress) {
    }

    public record RowReviewUpdate(
            String admissionNo,
            String imageNo,
            Boolean excluded,
            Double cropX,
            Double cropY) {
    }

    public record SchoolPhotoImportContext(
            long id,
            String schoolUid,
            String name,
            String shortCode,
            String academicYearId,
            String academicYearLabel,
            String driveFolderStatus,
            String driveFolderId,
            String driveFolderName,
            String driveFolderUrl,
            String driveFolderError) {
    }
}
