package com.custoking.ims.schoolcoreservice.photoimport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.photoimport.GoogleDrivePhotoImportClient.DriveFile;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.Batch;
import com.custoking.ims.schoolcoreservice.photoimport.PhotoImportRepository.ImportRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoImportServiceTest {
    private final PhotoImportRepository repository = mock(PhotoImportRepository.class);
    private final GoogleDrivePhotoImportClient drive = mock(GoogleDrivePhotoImportClient.class);
    private final PhotoImportWorkbookParser parser = mock(PhotoImportWorkbookParser.class);
    private final StudentPhotoStorage storage = mock(StudentPhotoStorage.class);
    private final PhotoImportService service = new PhotoImportService(repository, drive, parser, storage);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
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
                1L);
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
                null);
    }
}
