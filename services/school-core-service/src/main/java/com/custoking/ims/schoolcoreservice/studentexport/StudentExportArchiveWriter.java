package com.custoking.ims.schoolcoreservice.studentexport;

import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage;
import com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.StoredPhoto;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.ExportData;
import com.custoking.ims.schoolcoreservice.studentexport.StudentExportRepository.Student;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class StudentExportArchiveWriter {

    private static final int PHOTO_READ_PARALLELISM = 8;
    private static final int PHOTO_READ_BATCH = 16;
    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile(
            "(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$");
    private static final List<String> HEADERS = List.of(
            "Admission Number",
            "Student Name",
            "Class",
            "Section",
            "Roll Number",
            "Board Registration Number",
            "Date of Birth",
            "Admission Date",
            "Gender",
            "Father Name",
            "Father Contact",
            "Mother Name",
            "Student / Alternate Phone",
            "House Number",
            "Street",
            "Locality",
            "City",
            "State",
            "PIN Code",
            "Full Address",
            "Academic Year",
            "Fee Status",
            "Attendance Percent",
            "Photo Filename",
            "Photo Status");

    private final StudentPhotoStorage photoStorage;

    public StudentExportArchiveWriter(StudentPhotoStorage photoStorage) {
        this.photoStorage = photoStorage;
    }

    public Result write(ExportData data, OutputStream output) throws IOException {
        List<PhotoMapping> mappings = allocatePhotoNames(data.students());
        int exportedPhotos = 0;
        int missingPhotos = 0;

        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            // JPEG and XLSX payloads are already compressed. Level zero substantially reduces
            // Cloud Run CPU time while retaining the single-ZIP delivery format.
            zip.setLevel(Deflater.NO_COMPRESSION);
            zip.putNextEntry(new ZipEntry("photos/"));
            zip.closeEntry();

            try (ExecutorService executor = Executors.newFixedThreadPool(PHOTO_READ_PARALLELISM)) {
                for (int start = 0; start < mappings.size(); start += PHOTO_READ_BATCH) {
                    int end = Math.min(start + PHOTO_READ_BATCH, mappings.size());
                    List<PhotoMapping> batch = mappings.subList(start, end);
                    List<Future<Optional<StoredPhoto>>> reads = batch.stream()
                            .map(mapping -> executor.submit(
                                    () -> StringUtils.hasText(mapping.student().storedPhoto())
                                            ? photoStorage.readStoredPhoto(mapping.student().storedPhoto())
                                            : Optional.<StoredPhoto>empty()))
                            .toList();
                    for (int index = 0; index < batch.size(); index++) {
                        PhotoMapping mapping = batch.get(index);
                        Optional<StoredPhoto> photo = await(reads.get(index));
                        if (photo.isEmpty()) {
                            mapping.markMissing();
                            missingPhotos++;
                            continue;
                        }
                        StoredPhoto stored = photo.get();
                        String fileName = mapping.photoStem() + extension(stored.contentType());
                        mapping.markExported(fileName);
                        zip.putNextEntry(new ZipEntry("photos/" + fileName));
                        zip.write(stored.data());
                        zip.closeEntry();
                        exportedPhotos++;
                    }
                }
            }

            zip.putNextEntry(new ZipEntry("Student-Details.xlsx"));
            writeWorkbook(mappings, zip);
            zip.closeEntry();
            zip.finish();
        }
        return new Result(data.students().size(), exportedPhotos, missingPhotos);
    }

    private static Optional<StoredPhoto> await(Future<Optional<StoredPhoto>> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Student photo export was interrupted", ex);
        } catch (ExecutionException ex) {
            return Optional.empty();
        }
    }

    private static List<PhotoMapping> allocatePhotoNames(List<Student> students) {
        Set<String> used = new HashSet<>();
        List<PhotoMapping> mappings = new ArrayList<>(students.size());
        for (Student student : students) {
            String base = safeFileStem(student.admissionNumber(), student.id());
            String candidate = base;
            if (!used.add(candidate.toLowerCase(Locale.ROOT))) {
                candidate = trimToLength(base, 100) + "-" + student.id();
                int suffix = 2;
                while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
                    candidate = trimToLength(base, 95) + "-" + student.id() + "-" + suffix++;
                }
            }
            mappings.add(new PhotoMapping(student, candidate));
        }
        return mappings;
    }

    static String safeFileStem(String admissionNumber, long studentId) {
        String value = StringUtils.hasText(admissionNumber) ? admissionNumber.trim() : "student-" + studentId;
        value = value.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_")
                .replaceAll("[. ]+$", "")
                .trim();
        if (value.isBlank()) value = "student-" + studentId;
        if (WINDOWS_RESERVED_NAME.matcher(value).matches()) value = "_" + value;
        return trimToLength(value, 120);
    }

    private static String trimToLength(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String extension(String contentType) {
        if (contentType == null) return ".jpg";
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("png")) return ".png";
        if (normalized.contains("webp")) return ".webp";
        return ".jpg";
    }

    private static void writeWorkbook(List<PhotoMapping> mappings, OutputStream output) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try {
            Sheet sheet = workbook.createSheet("Students");
            sheet.createFreezePane(0, 1);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            Row header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(HEADERS.get(column));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, columnWidth(column));
            }

            int rowIndex = 1;
            for (PhotoMapping mapping : mappings) {
                Student student = mapping.student();
                Row row = sheet.createRow(rowIndex++);
                List<String> values = List.of(
                        text(student.admissionNumber()),
                        text(student.fullName()),
                        text(student.className()),
                        text(student.sectionName()),
                        text(student.rollNumber()),
                        text(student.boardRegistrationNumber()),
                        student.dateOfBirth() == null ? "" : student.dateOfBirth().toString(),
                        student.admissionDate() == null ? "" : student.admissionDate().toString(),
                        text(student.gender()),
                        text(student.fatherName()),
                        text(student.fatherContact()),
                        text(student.motherName()),
                        text(student.phone()),
                        text(student.houseNumber()),
                        text(student.street()),
                        text(student.locality()),
                        text(student.city()),
                        text(student.state()),
                        text(student.pinCode()),
                        text(student.address()),
                        text(student.academicYear()),
                        text(student.feeStatus()));
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(values.get(column));
                }
                if (student.attendancePercent() != null) {
                    row.createCell(22).setCellValue(student.attendancePercent());
                } else {
                    row.createCell(22).setCellValue("");
                }
                row.createCell(23).setCellValue(mapping.photoFileName());
                row.createCell(24).setCellValue(mapping.status());
            }
            if (!mappings.isEmpty()) {
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, mappings.size(), 0, HEADERS.size() - 1));
            }
            workbook.write(output);
        } finally {
            workbook.close();
        }
    }

    private static int columnWidth(int column) {
        return switch (column) {
            case 1, 9, 11, 19 -> 28 * 256;
            case 23 -> 26 * 256;
            default -> 18 * 256;
        };
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static final class PhotoMapping {
        private final Student student;
        private final String photoStem;
        private String photoFileName;
        private String status;

        private PhotoMapping(Student student, String photoStem) {
            this.student = student;
            this.photoStem = photoStem;
            this.photoFileName = photoStem + ".jpg";
            this.status = "Missing";
        }

        private Student student() { return student; }
        private String photoStem() { return photoStem; }
        private String photoFileName() { return photoFileName; }
        private String status() { return status; }
        private void markExported(String value) { photoFileName = value; status = "Exported"; }
        private void markMissing() { status = "Missing"; }
    }

    public record Result(int studentCount, int exportedPhotoCount, int missingPhotoCount) {}
}
