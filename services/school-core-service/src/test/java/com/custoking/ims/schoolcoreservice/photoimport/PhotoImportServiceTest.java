package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.photoimport.GoogleDrivePhotoImportClient.DriveFile;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.Batch;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.ImportRow;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RecoveryPreparation;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RecoveryProgress;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RecoveryResult;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RecoveryTarget;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RowInput;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoImportServiceTest {
    private final PhotoImportRepository repository = mock(PhotoImportRepository.class);
    private final GoogleDrivePhotoImportClient drive = mock(GoogleDrivePhotoImportClient.class);
    private final PhotoImportWorkbookParser parser = mock(PhotoImportWorkbookParser.class);
    private final StudentPhotoStorage storage = mock(StudentPhotoStorage.class);
    private final DriveFolderProvisioningService folderProvisioning = mock(DriveFolderProvisioningService.class);
    private final PhotoImportService service =
            new PhotoImportService(repository, drive, parser, storage, folderProvisioning);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createUsesProvisionedFolderWhenNoDriveLinkIsSupplied() {
        long schoolId = 7L;
        TenantContext.set(new TenantContext(
                42L,
                "operations@example.com",
                "OPERATIONS",
                null,
                null,
                Set.of(schoolId),
                Set.of("student:photo-import")));
        var managed = new DriveFolderProvisioningService.ProvisioningResult(
                schoolId,
                "11111111-1111-4111-8111-111111111111",
                "Green Valley School",
                "GVS",
                "ay_2026_27",
                "2026-27",
                "READY",
                "intake-folder",
                "Student Photo Intake",
                "https://drive.google.com/drive/folders/intake-folder",
                null);
        Batch created = batch(UUID.randomUUID(), schoolId, "DRAFT", 0);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(folderProvisioning.isConfigured()).thenReturn(true);
        when(folderProvisioning.ensureForSchool(schoolId)).thenReturn(managed);
        when(drive.readFolder("intake-folder"))
                .thenReturn(new GoogleDrivePhotoImportClient.DriveFolder("intake-folder", "Student Photo Intake"));
        when(repository.createBatch(
                schoolId, "ay_2026_27", "intake-folder", "Student Photo Intake", 42L))
                .thenReturn(created);

        Batch result = service.create(schoolId, "ay_2026_27", null);

        assertThat(result).isSameAs(created);
        verify(folderProvisioning).ensureForSchool(schoolId);
        verify(drive).readFolder("intake-folder");
    }

    @Test
    void rejectsAlternateDriveFolderWhenManagedSchoolYearBindingIsEnabled() {
        long schoolId = 7L;
        TenantContext.set(new TenantContext(
                42L,
                "operations@example.com",
                "OPERATIONS",
                null,
                null,
                Set.of(schoolId),
                Set.of("student:photo-import")));
        var managed = new DriveFolderProvisioningService.ProvisioningResult(
                schoolId,
                "11111111-1111-4111-8111-111111111111",
                "Green Valley School",
                "GVS",
                "ay_2026_27",
                "2026-27",
                "READY",
                "managed-folder",
                "Student Photo Intake",
                "https://drive.google.com/drive/folders/managed-folder",
                null);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(folderProvisioning.isConfigured()).thenReturn(true);
        when(folderProvisioning.ensureForSchool(schoolId)).thenReturn(managed);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                        schoolId,
                        "ay_2026_27",
                        "https://drive.google.com/drive/folders/foreign-folder"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managed Drive intake folder");

        verify(drive, never()).readFolder("foreign-folder");
        verify(repository, never()).createBatch(
                anyLong(), any(), any(), any(), any());
    }

    @Test
    void rejectsStaleAcademicYearBeforeBindingManagedDriveFolder() {
        long schoolId = 7L;
        TenantContext.set(new TenantContext(
                42L,
                "operations@example.com",
                "OPERATIONS",
                null,
                null,
                Set.of(schoolId),
                Set.of("student:photo-import")));
        var managed = new DriveFolderProvisioningService.ProvisioningResult(
                schoolId,
                "11111111-1111-4111-8111-111111111111",
                "Green Valley School",
                "GVS",
                "ay_2026_27",
                "2026-27",
                "READY",
                "managed-folder",
                "Student Photo Intake",
                "https://drive.google.com/drive/folders/managed-folder",
                null);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(folderProvisioning.isConfigured()).thenReturn(true);
        when(folderProvisioning.ensureForSchool(schoolId)).thenReturn(managed);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                        schoolId, "ay_2025_26", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer current");

        verify(drive, never()).readFolder(any());
        verify(repository, never()).createBatch(
                anyLong(), any(), any(), any(), any());
    }

    @Test
    void flagsImagesOverImportSourceLimitDuringScanBeforeStudentLookup() {
        UUID batchId = UUID.randomUUID();
        long schoolId = 7L;
        TenantContext.set(new TenantContext(
                42L,
                "operations@example.com",
                "OPERATIONS",
                null,
                null,
                Set.of(schoolId),
                Set.of("student:photo-import")));
        Batch draft = batch(batchId, schoolId, "DRAFT", 0);
        Batch review = batch(batchId, schoolId, "REVIEW", 0);
        DriveFile workbook = new DriveFile(
                "workbook-file", "mapping.csv", "text/csv",
                100L, "workbook-checksum", "2026-07-31T00:00:00Z");
        DriveFile oversized = new DriveFile(
                "photo-file", "DSC5236.jpg", "image/jpeg",
                21L * 1024 * 1024, "photo-checksum", "2026-07-31T00:00:00Z");
        var parsed = new PhotoImportWorkbookParser.ParsedWorkbook(
                "Sheet1",
                List.of(new PhotoImportWorkbookParser.WorkbookRow(
                        2, "ADM-1", "Student One", "I", "A", "5236")),
                List.of("AdmissionNo", "Name", "Class", "Section", "ImageNo"));
        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(draft);
        when(drive.listFiles("folder-1")).thenReturn(List.of(workbook, oversized));
        when(drive.download(workbook, PhotoImportWorkbookParser.MAX_WORKBOOK_BYTES))
                .thenReturn(new byte[]{1});
        when(parser.parse(any(byte[].class), eq("mapping.csv"))).thenReturn(parsed);
        when(drive.snapshotHash(List.of(workbook, oversized))).thenReturn("snapshot-1");
        when(repository.replaceScan(
                eq(batchId), eq(schoolId), eq("workbook-file"), eq("mapping.csv"),
                isNull(), eq("snapshot-1"), any(), any())).thenReturn(review);

        service.scan(batchId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RowInput>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceScan(
                eq(batchId), eq(schoolId), eq("workbook-file"), eq("mapping.csv"),
                isNull(), eq("snapshot-1"), any(), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("ERROR");
            assertThat(row.message()).contains("larger than 20 MB");
        });
        verify(repository, never()).studentByAdmission(anyLong(), any(), any());
    }

    @Test
    void acceptsCameraImagesAboveStandardUploadLimitDuringScan() {
        UUID batchId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch draft = batch(batchId, schoolId, "DRAFT", 0);
        Batch review = batch(batchId, schoolId, "REVIEW", 1);
        DriveFile workbook = new DriveFile(
                "workbook-file", "mapping.csv", "text/csv",
                100L, "workbook-checksum", "2026-07-31T00:00:00Z");
        DriveFile cameraImage = new DriveFile(
                "photo-file", "DSC5236.jpg", "image/jpeg",
                6L * 1024 * 1024, "photo-checksum", "2026-07-31T00:00:00Z");
        var parsed = new PhotoImportWorkbookParser.ParsedWorkbook(
                "Sheet1",
                List.of(new PhotoImportWorkbookParser.WorkbookRow(
                        2, "ADM-1", "Student One", "I", "A", "5236")),
                List.of("AdmissionNo", "Name", "Class", "Section", "ImageNo"));
        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(draft);
        when(drive.listFiles("folder-1")).thenReturn(List.of(workbook, cameraImage));
        when(drive.download(workbook, PhotoImportWorkbookParser.MAX_WORKBOOK_BYTES))
                .thenReturn(new byte[]{1});
        when(parser.parse(any(byte[].class), eq("mapping.csv"))).thenReturn(parsed);
        when(drive.snapshotHash(List.of(workbook, cameraImage))).thenReturn("snapshot-1");
        when(repository.studentByAdmission(schoolId, "ay-2026", "ADM-1"))
                .thenReturn(java.util.Optional.of(new PhotoImportRepository.StudentMatch(
                        101L, "ADM-1", "Student One", "I", 1, "A", null)));
        when(repository.replaceScan(
                eq(batchId), eq(schoolId), eq("workbook-file"), eq("mapping.csv"),
                isNull(), eq("snapshot-1"), any(), any())).thenReturn(review);

        service.scan(batchId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RowInput>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceScan(
                eq(batchId), eq(schoolId), eq("workbook-file"), eq("mapping.csv"),
                isNull(), eq("snapshot-1"), any(), rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("READY");
            assertThat(row.driveFileId()).isEqualTo("photo-file");
        });
    }

    @Test
    void rejectsPreviouslyProcessedDriveSnapshotBeforeDownloadingAnything() {
        UUID batchId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch draft = batch(batchId, schoolId, "DRAFT", 0);
        DriveFile workbook = new DriveFile(
                "workbook-file", "mapping.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                100L, "workbook-checksum", "2026-07-31T00:00:00Z");
        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(draft);
        when(drive.listFiles("folder-1")).thenReturn(List.of(workbook));
        when(drive.snapshotHash(List.of(workbook))).thenReturn("snapshot-1");
        when(repository.terminalSnapshotExists(batchId, schoolId, "folder-1", "snapshot-1"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.scan(batchId))
                .isInstanceOf(DrivePhotoImportException.class)
                .hasMessageContaining("already processed");

        verify(drive, never()).download(any(), anyLong());
        verify(parser, never()).parse(any(), any());
        verify(storage, never()).uploadImportFile(any(), any(), any(), any(), any());
        verify(repository, never()).replaceScan(any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void operatorCanCorrectMappingAndCropDuringReview() {
        UUID batchId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch review = batch(batchId, schoolId, "REVIEW", 0);
        DriveFile correctedPhoto = new DriveFile(
                "photo-6001", "DSC6001.jpg", "image/jpeg", 100L,
                "checksum-6001", "2026-07-31T00:00:00Z");
        ImportRow current = new ImportRow(
                UUID.randomUUID(), batchId, schoolId, 2, "BAD-ADM", "Student One",
                "I", "A", "5001", null, null, null, "ERROR", "No match",
                null, null, null, 0.5, 0.5, false, null, null);
        ImportRow saved = new ImportRow(
                current.id(), batchId, schoolId, 2, "ADM-1", "Student One",
                "I", "A", "6001", correctedPhoto.id(), correctedPhoto.name(), 101L,
                "READY", "Matched by school, academic year and AdmissionNo", null, null,
                correctedPhoto.md5Checksum(), 0.25, 0.75, true, null, null);
        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(review);
        when(repository.rows(batchId, schoolId)).thenReturn(List.of(current));
        when(repository.sourceFiles(batchId, schoolId)).thenReturn(List.of(correctedPhoto));
        when(repository.studentByAdmission(schoolId, "ay-2026", "ADM-1"))
                .thenReturn(java.util.Optional.of(new PhotoImportRepository.StudentMatch(
                        101L, "ADM-1", "Student One", "I", 1, "A", null)));
        when(repository.updateReviewRow(
                eq(batchId), eq(schoolId), eq(current.id()), any(RowInput.class), eq(0.25), eq(0.75)))
                .thenReturn(saved);

        var result = service.updateRow(
                batchId,
                current.id(),
                new PhotoImportService.RowReviewUpdate("ADM-1", "6001", false, 0.25, 0.75));

        ArgumentCaptor<RowInput> row = ArgumentCaptor.forClass(RowInput.class);
        verify(repository).updateReviewRow(
                eq(batchId), eq(schoolId), eq(current.id()), row.capture(), eq(0.25), eq(0.75));
        assertThat(row.getValue().status()).isEqualTo("READY");
        assertThat(row.getValue().driveFileId()).isEqualTo("photo-6001");
        assertThat(result.get("row")).isSameAs(saved);
    }

    @Test
    void operationsUserCannotReadBatchFromAnotherSchool() {
        UUID batchId = UUID.randomUUID();
        setOperationsTenant(7L);
        when(repository.batchSchoolId(batchId)).thenReturn(8L);

        assertThatThrownBy(() -> service.detail(batchId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");

        verify(repository, never()).batch(batchId, 8L);
        verify(repository, never()).rows(batchId, 8L);
    }

    @Test
    void resultCsvNeutralizesSpreadsheetFormulasAndReportsEvidence() {
        UUID batchId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch completed = batch(batchId, schoolId, "COMPLETED", 0);
        DriveFile file = new DriveFile(
                "photo-5001", "DSC5001.jpg", "image/jpeg", 100L,
                "checksum-5001", "2026-07-31T00:00:00Z");
        ImportRow source = row(batchId, schoolId, 1, file);
        ImportRow applied = new ImportRow(
                source.id(), source.batchId(), source.schoolId(), source.excelRow(),
                source.admissionNo(), "=HYPERLINK(\"https://bad.example\")", source.className(),
                source.sectionName(), source.imageNo(), source.driveFileId(), source.driveFileName(),
                source.studentId(), "APPLIED", "Portrait imported", source.priorPhotoKey(),
                "final-key", source.sourceChecksum(), 0.5, 0.5, false,
                "source-evidence-key", OffsetDateTime.parse("2026-07-31T01:00:00Z"));
        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(completed);
        when(repository.rows(batchId, schoolId)).thenReturn(List.of(applied));

        String csv = service.resultCsv(batchId);

        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://bad.example\"\")\"");
        assertThat(csv).contains(",true\r\n");
    }

    @Test
    void executeProcessesOneReadyRowPerRequest() {
        UUID batchId = UUID.randomUUID();
        long schoolId = 7L;
        TenantContext.set(new TenantContext(
                42L,
                "operations@example.com",
                "OPERATIONS",
                null,
                null,
                Set.of(schoolId),
                Set.of("student:photo-import")));

        Batch frozen = batch(batchId, schoolId, "FROZEN", 12);
        Batch executing = batch(batchId, schoolId, "EXECUTING", 2);
        List<DriveFile> files = new ArrayList<>();
        List<ImportRow> rows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            DriveFile file = new DriveFile(
                    "file-" + index,
                    "DSC" + (5000 + index) + ".jpg",
                    "image/jpeg",
                    100L,
                    "checksum-" + index,
                    "2026-07-31T00:00:00Z");
            files.add(file);
            rows.add(row(batchId, schoolId, index, file));
        }

        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(frozen);
        when(repository.currentAcademicYearId(schoolId)).thenReturn("ay-2026");
        when(drive.listFiles("folder-1")).thenReturn(files);
        when(drive.snapshotHash(files)).thenReturn("snapshot-1");
        when(repository.startExecution(batchId, schoolId, 42L)).thenReturn(executing);
        when(repository.rows(batchId, schoolId)).thenReturn(rows);
        when(drive.download(any(DriveFile.class), anyLong())).thenReturn(new byte[]{1, 2, 3});
        when(storage.normalizePortrait(
                any(byte[].class), eq("image/jpeg"), eq(0.5), eq(0.5), eq(20L * 1024 * 1024)))
                .thenReturn(new byte[]{9, 8, 7});
        when(repository.applyPhoto(
                eq(executing), any(ImportRow.class), any(byte[].class), eq("image/jpeg"), any(byte[].class)))
                .thenReturn("photo-key");
        when(repository.finishExecution(batchId, schoolId)).thenReturn(executing);

        Batch result = service.execute(batchId);

        assertThat(result.status()).isEqualTo("EXECUTING");
        verify(drive, org.mockito.Mockito.times(1)).download(any(DriveFile.class), anyLong());
        verify(storage, org.mockito.Mockito.times(1)).normalizePortrait(
                any(byte[].class), eq("image/jpeg"), eq(0.5), eq(0.5), eq(20L * 1024 * 1024));
        verify(repository, org.mockito.Mockito.times(1))
                .applyPhoto(eq(executing), any(ImportRow.class), any(byte[].class), eq("image/jpeg"), any(byte[].class));
    }

    @Test
    void recoveryReprocessesTheRetainedUnchangedDriveOriginal() {
        UUID batchId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch completed = batch(batchId, schoolId, "COMPLETED", 0);
        byte[] original = new byte[]{1, 2, 3};
        String checksum = "5289df737df57326fcdd22597afb1fac";
        DriveFile retained = new DriveFile(
                "drive-photo-1", "DSC5001.jpg", "image/jpeg", 3L,
                checksum, "2026-07-31T00:00:00Z");
        RecoveryTarget target = new RecoveryTarget(
                rowId, batchId, schoolId, 101L, retained.id(), retained.name(), checksum,
                "cropped-photo-key", "cropped-photo-key", completed.schoolUid());
        RecoveryPreparation preparation = new RecoveryPreparation(
                UUID.randomUUID(), "READY", null, target);
        RecoveryResult recovered = new RecoveryResult(
                rowId, "RECOVERED", "uncropped-photo-key", "Recovered");

        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(completed);
        when(repository.beginPhotoRecovery(
                batchId, schoolId, rowId, "fit-without-crop-v1", 42L)).thenReturn(preparation);
        when(drive.listFiles("folder-1")).thenReturn(List.of(retained));
        when(repository.sourceFile(batchId, schoolId, retained.id())).thenReturn(retained);
        when(drive.download(retained, 20L * 1024 * 1024)).thenReturn(original);
        when(storage.normalizePortrait(
                original, "image/jpeg", 0.5, 0.5, 20L * 1024 * 1024))
                .thenReturn(new byte[]{9, 8, 7});
        when(repository.completePhotoRecovery(
                preparation, "fit-without-crop-v1", new byte[]{9, 8, 7})).thenReturn(recovered);

        var result = service.recoverAppliedRows(batchId, List.of(rowId));

        assertThat(result.recoveredCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(result.rows()).containsExactly(recovered);
        verify(drive).download(retained, 20L * 1024 * 1024);
        verify(repository).completePhotoRecovery(
                preparation, "fit-without-crop-v1", new byte[]{9, 8, 7});
    }

    @Test
    void completedRecoveryIsIdempotentAndDoesNotReadDriveAgain() {
        UUID batchId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch completed = batch(batchId, schoolId, "COMPLETED", 0);
        RecoveryTarget target = new RecoveryTarget(
                rowId, batchId, schoolId, 101L, "drive-photo-1", "DSC5001.jpg", null,
                "uncropped-photo-key", "uncropped-photo-key", completed.schoolUid());
        RecoveryPreparation preparation = new RecoveryPreparation(
                UUID.randomUUID(), "ALREADY_RECOVERED", "Already recovered", target);

        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(completed);
        when(repository.beginPhotoRecovery(
                batchId, schoolId, rowId, "fit-without-crop-v1", 42L)).thenReturn(preparation);

        var result = service.recoverAppliedRows(batchId, List.of(rowId, rowId));

        assertThat(result.selectedCount()).isEqualTo(1);
        assertThat(result.alreadyRecoveredCount()).isEqualTo(1);
        verify(drive, never()).listFiles(any());
        verify(drive, never()).download(any(), anyLong());
        verify(repository, never()).completePhotoRecovery(any(), any(), any());
    }

    @Test
    void newerStudentPhotoIsReportedAsProtectedAndPersistedProgressIsReturned() {
        UUID batchId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch completed = batch(batchId, schoolId, "COMPLETED", 0);
        RecoveryTarget target = new RecoveryTarget(
                rowId, batchId, schoolId, 101L, "drive-photo-1", "DSC5001.jpg", null,
                "import-photo-key", "newer-manual-photo-key", completed.schoolUid());
        RecoveryPreparation preparation = new RecoveryPreparation(
                UUID.randomUUID(), "PROTECTED",
                "Student photo changed after this import; recovery did not overwrite it", target);
        RecoveryProgress progress = new RecoveryProgress(
                batchId, schoolId, 1, 1, 0, 1, 0, 0, 0, 100, false,
                OffsetDateTime.parse("2026-08-24T00:00:00Z"));

        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(completed);
        when(repository.beginPhotoRecovery(
                batchId, schoolId, rowId, "fit-without-crop-v1", 42L)).thenReturn(preparation);
        when(repository.photoRecoveryProgress(
                batchId, schoolId, "fit-without-crop-v1")).thenReturn(progress);

        var result = service.recoverAppliedRows(batchId, List.of(rowId));

        assertThat(result.protectedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(result.progress()).isSameAs(progress);
        assertThat(result.rows().getFirst().status()).isEqualTo("PROTECTED");
        verify(drive, never()).listFiles(any());
        verify(drive, never()).download(any(), anyLong());
    }

    @Test
    void changedDriveSourceIsAuditedAsFailedWithoutDownloadingOrOverwriting() {
        UUID batchId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        UUID recoveryId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch completed = batch(batchId, schoolId, "COMPLETED", 0);
        DriveFile retained = new DriveFile(
                "drive-photo-1", "DSC5001.jpg", "image/jpeg", 3L,
                "5289df737df57326fcdd22597afb1fac", "2026-07-31T00:00:00Z");
        DriveFile changed = new DriveFile(
                retained.id(), retained.name(), retained.mimeType(), 4L,
                "74f10d03afa000a00e2f2552c7356bd1", "2026-08-01T00:00:00Z");
        RecoveryPreparation preparation = new RecoveryPreparation(
                recoveryId, "READY", null, new RecoveryTarget(
                        rowId, batchId, schoolId, 101L, retained.id(), retained.name(),
                        retained.md5Checksum(), "cropped-key", "cropped-key", completed.schoolUid()));
        RecoveryResult failed = new RecoveryResult(rowId, "FAILED", null, "Source changed");

        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(completed);
        when(repository.beginPhotoRecovery(
                batchId, schoolId, rowId, "fit-without-crop-v1", 42L)).thenReturn(preparation);
        when(drive.listFiles("folder-1")).thenReturn(List.of(changed));
        when(repository.sourceFile(batchId, schoolId, retained.id())).thenReturn(retained);
        when(repository.failPhotoRecovery(eq(recoveryId), eq(rowId), eq(schoolId), any()))
                .thenReturn(failed);

        var result = service.recoverAppliedRows(batchId, List.of(rowId));

        assertThat(result.failedCount()).isEqualTo(1);
        verify(drive, never()).download(any(), anyLong());
        verify(repository, never()).completePhotoRecovery(any(), any(), any());
        verify(repository).failPhotoRecovery(eq(recoveryId), eq(rowId), eq(schoolId),
                org.mockito.ArgumentMatchers.contains("changed after import"));
    }

    @Test
    void driveExecutionPreservesTheCompleteSourceFrame() throws Exception {
        UUID batchId = UUID.randomUUID();
        long schoolId = 7L;
        setOperationsTenant(schoolId);
        Batch frozen = batch(batchId, schoolId, "FROZEN", 1);
        Batch executing = batch(batchId, schoolId, "EXECUTING", 1);
        DriveFile file = new DriveFile(
                "file-1", "DSC5000.png", "image/png", 100L,
                "checksum-1", "2026-07-31T00:00:00Z");
        ImportRow importRow = row(batchId, schoolId, 0, file);

        BufferedImage landscape = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = landscape.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 200, 400);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(200, 0, 400, 400);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(600, 0, 200, 400);
        graphics.dispose();
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        ImageIO.write(landscape, "png", source);

        when(repository.batchSchoolId(batchId)).thenReturn(schoolId);
        when(repository.studentsModuleEnabled(schoolId)).thenReturn(true);
        when(repository.batch(batchId, schoolId)).thenReturn(frozen);
        when(repository.currentAcademicYearId(schoolId)).thenReturn("ay-2026");
        when(drive.listFiles("folder-1")).thenReturn(List.of(file));
        when(drive.snapshotHash(List.of(file))).thenReturn("snapshot-1");
        when(repository.startExecution(batchId, schoolId, 42L)).thenReturn(executing);
        when(repository.rows(batchId, schoolId)).thenReturn(List.of(importRow));
        when(drive.download(file, 20L * 1024 * 1024)).thenReturn(source.toByteArray());
        when(repository.applyPhoto(
                eq(executing), eq(importRow), any(byte[].class), eq("image/png"), any(byte[].class)))
                .thenReturn("photo-key");
        when(repository.finishExecution(batchId, schoolId)).thenReturn(executing);

        StudentPhotoStorage realStorage =
                new StudentPhotoStorage("", 60, 512, 5 * 1024 * 1024, "");
        PhotoImportService realService =
                new PhotoImportService(repository, drive, parser, realStorage, folderProvisioning);

        realService.execute(batchId);

        ArgumentCaptor<byte[]> normalized = ArgumentCaptor.forClass(byte[].class);
        verify(repository).applyPhoto(
                eq(executing), eq(importRow), eq(source.toByteArray()), eq("image/png"), normalized.capture());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(normalized.getValue()));
        assertThat(stored.getWidth()).isEqualTo(512);
        assertThat(stored.getHeight()).isEqualTo(256);
        assertThat(new Color(stored.getRGB(16, 128)).getRed()).isGreaterThan(200);
        assertThat(new Color(stored.getRGB(495, 128)).getBlue()).isGreaterThan(200);
    }

    private static Batch batch(UUID id, long schoolId, String status, int readyCount) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-31T00:00:00Z");
        return new Batch(
                id,
                schoolId,
                "11111111-1111-4111-8111-111111111111",
                "Green Valley School",
                "ay-2026",
                "2026-27",
                "folder-1",
                "Class I Photos",
                "workbook-1",
                "adm_no_imag_no_mapping.xlsx",
                status,
                "snapshot-1",
                12,
                readyCount,
                0,
                0,
                12 - readyCount,
                0,
                42L,
                42L,
                now,
                now,
                now,
                null,
                now,
                now.plusDays(14),
                null,
                1L);
    }

    private static void setOperationsTenant(long schoolId) {
        TenantContext.set(new TenantContext(
                42L,
                "operations@example.com",
                "OPERATIONS",
                null,
                null,
                Set.of(schoolId),
                Set.of("student:photo-import")));
    }

    private static ImportRow row(UUID batchId, long schoolId, int index, DriveFile file) {
        return new ImportRow(
                UUID.randomUUID(),
                batchId,
                schoolId,
                index + 2,
                "ADM-" + index,
                "Student " + index,
                "I",
                "A",
                String.valueOf(5000 + index),
                file.id(),
                file.name(),
                100L + index,
                "READY",
                "Matched",
                null,
                null,
                file.md5Checksum(),
                0.5,
                0.5,
                false,
                null,
                null);
    }
}
