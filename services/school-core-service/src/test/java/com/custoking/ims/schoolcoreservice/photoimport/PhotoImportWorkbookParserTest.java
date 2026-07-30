package com.custoking.ims.schoolcoreservice.photoimport;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoImportWorkbookParserTest {
    private final PhotoImportWorkbookParser parser = new PhotoImportWorkbookParser();

    @Test
    void parsesNumericIdentifiersUsingDisplayedValuesAndKeepsBlankImageRows() throws Exception {
        byte[] workbook;
        try (XSSFWorkbook xlsx = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = xlsx.createSheet("Sheet1");
            var header = sheet.createRow(0);
            String[] headers = {"AdmissionNo", "Name", "Class", "Section", "ImageNo"};
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);

            var first = sheet.createRow(1);
            first.createCell(0, CellType.NUMERIC).setCellValue(2045);
            first.createCell(1).setCellValue("Aaira Khan");
            first.createCell(2).setCellValue("I");
            first.createCell(3).setCellValue("A");
            first.createCell(4, CellType.NUMERIC).setCellValue(5747);

            var second = sheet.createRow(2);
            second.createCell(0, CellType.NUMERIC).setCellValue(2288);
            second.createCell(1).setCellValue("SAKEENA FATIMA");
            second.createCell(2).setCellValue("I");
            second.createCell(3).setCellValue("A");
            second.createCell(4).setBlank();
            xlsx.write(output);
            workbook = output.toByteArray();
        }

        var parsed = parser.parse(workbook, "adm_no_imag_no_mapping.xlsx");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().getFirst().admissionNo()).isEqualTo("2045");
        assertThat(parsed.rows().getFirst().imageNo()).isEqualTo("5747");
        assertThat(parsed.rows().get(1).imageNo()).isEmpty();
        assertThat(parsed.headers()).containsExactly("AdmissionNo", "Name", "Class", "Section", "ImageNo");
    }

    @Test
    void rejectsMissingRequiredHeaders() throws Exception {
        byte[] workbook;
        try (XSSFWorkbook xlsx = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = xlsx.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("AdmissionNo");
            sheet.createRow(1).createCell(0).setCellValue("2045");
            xlsx.write(output);
            workbook = output.toByteArray();
        }
        assertThatThrownBy(() -> parser.parse(workbook, "mapping.xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing workbook columns");
    }
}
