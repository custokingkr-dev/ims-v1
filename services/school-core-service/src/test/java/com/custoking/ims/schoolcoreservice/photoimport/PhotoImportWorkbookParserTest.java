package com.custoking.ims.schoolcoreservice.photoimport;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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

    @Test
    void parsesLegacyXlsAndPreservesDisplayedIdentifierFormatting() throws Exception {
        byte[] workbook;
        try (HSSFWorkbook xls = new HSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = xls.createSheet("Sheet1");
            var header = sheet.createRow(0);
            String[] headers = {"AdmissionNo", "Name", "Class", "Section", "ImageNo"};
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }
            var identifierStyle = xls.createCellStyle();
            identifierStyle.setDataFormat(xls.createDataFormat().getFormat("000000"));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(2045);
            row.getCell(0).setCellStyle(identifierStyle);
            row.createCell(1).setCellValue("Aaira Khan");
            row.createCell(2).setCellValue("I");
            row.createCell(3).setCellValue("A");
            row.createCell(4).setCellValue(5747);
            row.getCell(4).setCellStyle(identifierStyle);
            xls.write(output);
            workbook = output.toByteArray();
        }

        var parsed = parser.parse(workbook, "mapping.xls");

        assertThat(parsed.rows()).singleElement().satisfies(row -> {
            assertThat(row.admissionNo()).isEqualTo("002045");
            assertThat(row.imageNo()).isEqualTo("005747");
        });
        assertThat(parsed.sheetName()).isEqualTo("Sheet1");
    }

    @Test
    void parsesUtf8CsvWithBomQuotedFieldsAndBlankImageNumbers() {
        String csv = "\uFEFFAdmissionNo,Name,Class,Section,ImageNo\r\n"
                + "002045,\"Khan, Aaira\",I,A,05747\r\n"
                + "002288,\"Sakeena Fatima\",I,A,\r\n";

        var parsed = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "mapping.CSV");

        assertThat(parsed.sheetName()).isEqualTo("CSV");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().getFirst().excelRow()).isEqualTo(2);
        assertThat(parsed.rows().getFirst().admissionNo()).isEqualTo("002045");
        assertThat(parsed.rows().getFirst().name()).isEqualTo("Khan, Aaira");
        assertThat(parsed.rows().getFirst().imageNo()).isEqualTo("05747");
        assertThat(parsed.rows().get(1).imageNo()).isEmpty();
    }

    @Test
    void parsesTsvAndRejectsUnsupportedOrInvalidTextFiles() {
        String tsv = "AdmissionNo\tName\tClass\tSection\tImageNo\n"
                + "2045\tAaira Khan\tI\tA\t5747\n";

        var parsed = parser.parse(tsv.getBytes(StandardCharsets.UTF_8), "mapping.tsv");

        assertThat(parsed.rows()).singleElement().satisfies(row ->
                assertThat(row.imageNo()).isEqualTo("5747"));
        assertThatThrownBy(() -> parser.parse(tsv.getBytes(StandardCharsets.UTF_8), "mapping.ods"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XLSX, XLS, CSV, or TSV");
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte) 0xC3, (byte) 0x28}, "mapping.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8");
    }
}
