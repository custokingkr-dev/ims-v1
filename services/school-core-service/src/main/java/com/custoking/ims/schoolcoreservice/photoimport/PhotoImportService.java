package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
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
import java.util.HashSet;
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

    public PhotoImportService(
            PhotoImportRepository repository,
            GoogleDrivePhotoImportClient drive,
            PhotoImportWorkbookParser parser,
            StudentPhotoStorage photoStorage) {
        this.repository = repository;
        this.drive = drive;
        this.parser = parser;
        this.photoStorage = photoStorage;
    }

    public Map<String, Object> context() {
        requireAccess();
        return Map.of(
                "driveConfigured", drive.isEnabled(),
                "schools", repository.allowedSchools(),
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
        var folder = drive.readFolder(driveFolderUrl);
        return repository.createBatch(
                schoolId,
                academicYearId.trim(),
                folder.id(),
                folder.name(),
                TenantContext.get().userId());
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
                "rows", repository.rows(id, schoolId));
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
        var parsed = parser.parse(drive.download(workbook, PhotoImportWorkbookParser.MAX_WORKBOOK_BYTES),
                workbook.name());

        Map<String, List<DriveFile>> imagesByNumber = new LinkedHashMap<>();
        for (DriveFile file : files) {
            DscImageNumber.fromFileName(file.name()).ifPresent(imageNo ->
                    imagesByNumber.computeIfAbsent(imageNo, ignored -> new ArrayList<>()).add(file));
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
                                : (DscImageNumber.fromFileName(file.name()).isPresent() ? "IMAGE" : "OTHER"),
                        DscImageNumber.fromFileName(file.name()).orElse(null)))
                .toList();
        return repository.replaceScan(
                id,
                schoolId,
                workbook.id(),
                workbook.name(),
                drive.snapshotHash(files),
                sources,
                rows);
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
        byte[] normalized = photoStorage.normalizePortrait(drive.download(source, MAX_IMAGE_BYTES), source.mimeType());
        return new Preview(normalized, "image/jpeg");
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
}
