package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.photoimport.DriveFolderProvisioningRepository.DriveFolderBinding;
import com.custoking.ims.schoolcoreservice.photoimport.GoogleDrivePhotoImportClient.DriveFile;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.Batch;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.ImportRow;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PhotoImportService {
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int EXECUTION_CHUNK_SIZE = 10;
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
                "fileNameRule", "DSC5236.jpg or DSC_05236.JPG");
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
                "access", repository.accessState(id, schoolId));
    }

    public Batch scan(UUID id) {
        long schoolId = authorizedBatchSchool(id);
        Batch batch = repository.batch(id, schoolId);
        List<DriveFile> files = drive.listFiles(batch.driveFolderId());
        List<DriveFile> workbooks = files.stream().filter(DriveFile::isXlsx).toList();
        if (workbooks.size() != 1) {
            throw new IllegalArgumentException(workbooks.isEmpty()
                    ? "The Drive folder must contain one .xlsx mapping workbook"
                    : "The Drive folder contains multiple .xlsx files; keep exactly one mapping workbook");
        }
        DriveFile workbook = workbooks.getFirst();
        String snapshotHash = drive.snapshotHash(files);
        if (repository.terminalSnapshotExists(
                id, schoolId, batch.driveFolderId(), snapshotHash)) {
            throw new DrivePhotoImportException(
                    "source_already_imported",
                    "This exact Drive folder snapshot was already processed; replace the workbook or photos before starting another job");
        }
        byte[] workbookBytes = drive.download(workbook, PhotoImportWorkbookParser.MAX_WORKBOOK_BYTES);
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
                String currentChecksum = source.md5Checksum();
                if (row.sourceChecksum() != null && currentChecksum != null
                        && !row.sourceChecksum().equals(currentChecksum)) {
                    throw new IllegalArgumentException("Source image changed after review");
                }
                byte[] bytes = drive.download(source, MAX_IMAGE_BYTES);
                repository.applyPhoto(batch, row, bytes, source.mimeType());
            } catch (Exception ex) {
                repository.markRowFailed(row.id(), schoolId, ex.getMessage());
            }
        }
        return repository.finishExecution(id, schoolId);
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
                drive.download(source, MAX_IMAGE_BYTES),
                source.mimeType(),
                row.cropX(),
                row.cropY());
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
        if (image.size() != null && image.size() > MAX_IMAGE_BYTES) {
            return input(row, canonicalImageNo, image, null, "ERROR",
                    image.name() + " is larger than 5 MB", null);
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
                image == null ? null : image.md5Checksum());
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
