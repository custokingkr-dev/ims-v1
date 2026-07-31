package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.photoimport.GoogleDrivePhotoImportClient.DriveFile;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.Batch;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.ImportRow;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.RowInput;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    void flagsOversizedCameraImageDuringScanBeforeStudentLookup() {
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
                6L * 1024 * 1024, "photo-checksum", "2026-07-31T00:00:00Z");
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
            assertThat(row.message()).contains("larger than 5 MB");
        });
        verify(repository, never()).studentByAdmission(anyLong(), any(), any());
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
    void executeProcessesAtMostTenReadyRowsPerRequest() {
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
        when(repository.applyPhoto(eq(executing), any(ImportRow.class), any(byte[].class), eq("image/jpeg")))
                .thenReturn("photo-key");
        when(repository.finishExecution(batchId, schoolId)).thenReturn(executing);

        Batch result = service.execute(batchId);

        assertThat(result.status()).isEqualTo("EXECUTING");
        verify(drive, org.mockito.Mockito.times(10)).download(any(DriveFile.class), anyLong());
        verify(repository, org.mockito.Mockito.times(10))
                .applyPhoto(eq(executing), any(ImportRow.class), any(byte[].class), eq("image/jpeg"));
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
