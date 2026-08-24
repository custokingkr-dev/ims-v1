package com.custoking.ims.schoolcoreservice.studentexport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.StoredPhoto;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.ExportData;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.School;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.Student;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudentExportArchiveWriterTest {

    @Test
    void exportsPhotoBytesWithoutCroppingOrChangingAspectRatio() throws Exception {
        BufferedImage landscape = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = landscape.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 200, 400);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(200, 0, 400, 400);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(600, 0, 200, 400);
        graphics.dispose();
        ByteArrayOutputStream photo = new ByteArrayOutputStream();
        ImageIO.write(landscape, "png", photo);

        StudentPhotoStorage storage = mock(StudentPhotoStorage.class);
        when(storage.readStoredPhoto("landscape-photo"))
                .thenReturn(Optional.of(new StoredPhoto(photo.toByteArray(), "image/png")));
        ExportData data = new ExportData(
                new School(7L, "Green Valley School", "GVS"),
                List.of(student(1L, "ADM-001", "Aarav Rao", "landscape-photo")));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new StudentExportArchiveWriter(storage).write(data, output);

        byte[] exported = unzip(output.toByteArray()).get("photos/ADM-001.png");
        assertThat(exported).isEqualTo(photo.toByteArray());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(exported));
        assertThat(decoded.getWidth()).isEqualTo(800);
        assertThat(decoded.getHeight()).isEqualTo(400);
        assertThat(new Color(decoded.getRGB(20, 200))).isEqualTo(Color.RED);
        assertThat(new Color(decoded.getRGB(780, 200))).isEqualTo(Color.BLUE);
    }

    @Test
    void writesPlainWorkbookAndAdmissionNumberPhotoMapping() throws Exception {
        StudentPhotoStorage storage = mock(StudentPhotoStorage.class);
        when(storage.readStoredPhoto("photo-1"))
                .thenReturn(Optional.of(new StoredPhoto("jpeg-one".getBytes(), "image/jpeg")));
        when(storage.readStoredPhoto("photo-2"))
                .thenReturn(Optional.of(new StoredPhoto("png-two".getBytes(), "image/png")));
        when(storage.readStoredPhoto(null)).thenReturn(Optional.empty());

        ExportData data = new ExportData(
                new School(7L, "Green Valley School", "GVS"),
                List.of(
                        student(1L, "ADM-001", "Aarav Rao", "photo-1"),
                        student(2L, "A/B:2", "Diya Shah", "photo-2"),
                        student(3L, "CON", "Kabir Ali", null)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StudentExportArchiveWriter.Result result = new StudentExportArchiveWriter(storage).write(data, output);

        assertThat(result.studentCount()).isEqualTo(3);
        assertThat(result.exportedPhotoCount()).isEqualTo(2);
        assertThat(result.missingPhotoCount()).isEqualTo(1);

        Map<String, byte[]> entries = unzip(output.toByteArray());
        assertThat(entries).containsKeys(
                "photos/ADM-001.jpg",
                "photos/A_B_2.png",
                "Student-Details.xlsx");
        assertThat(entries).doesNotContainKey("photos/CON.jpg");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(entries.get("Student-Details.xlsx")))) {
            var sheet = workbook.getSheet("Students");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Admission Number");
            assertThat(sheet.getRow(1).getCell(23).getStringCellValue()).isEqualTo("ADM-001.jpg");
            assertThat(sheet.getRow(1).getCell(24).getStringCellValue()).isEqualTo("Exported");
            assertThat(sheet.getRow(2).getCell(23).getStringCellValue()).isEqualTo("A_B_2.png");
            assertThat(sheet.getRow(3).getCell(23).getStringCellValue()).isEqualTo("_CON.jpg");
            assertThat(sheet.getRow(3).getCell(24).getStringCellValue()).isEqualTo("Missing");
        }
    }

    @Test
    void generatesWindowsSafeDeterministicPhotoStems() {
        assertThat(StudentExportArchiveWriter.safeFileStem(" ADM/42:*? ", 9L)).isEqualTo("ADM_42___");
        assertThat(StudentExportArchiveWriter.safeFileStem("NUL", 9L)).isEqualTo("_NUL");
        assertThat(StudentExportArchiveWriter.safeFileStem("   ", 9L)).isEqualTo("student-9");
    }

    private static Student student(long id, String admission, String name, String photo) {
        return new Student(
                id, admission, name, "Class 9", "A", "12", "BR-" + id,
                LocalDate.of(2012, 1, 2), LocalDate.of(2025, 4, 1), "Female",
                "Parent", "9876543210", "Mother", "9000000000", "12", "Main Road",
                "Central", "Hyderabad", "Telangana", "500001", "12 Main Road",
                "2026-27", "Paid", 96.5, photo);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
