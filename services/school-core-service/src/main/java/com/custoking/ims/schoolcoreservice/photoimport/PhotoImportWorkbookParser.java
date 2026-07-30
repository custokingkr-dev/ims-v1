package com.custoking.ims.schoolcoreservice.photoimport;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PhotoImportWorkbookParser {
    static final int MAX_ROWS = 500;
    static final long MAX_WORKBOOK_BYTES = 10L * 1024 * 1024;
    private static final List<String> REQUIRED_HEADERS =
            List.of("AdmissionNo", "Name", "Class", "Section", "ImageNo");

    public ParsedWorkbook parse(byte[] bytes, String fileName) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("The mapping workbook must be an .xlsx file");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The mapping workbook is empty");
        }
        if (bytes.length > MAX_WORKBOOK_BYTES) {
            throw new IllegalArgumentException("The mapping workbook must be 10 MB or smaller");
        }

        ZipSecureFile.setMinInflateRatio(0.01);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) {
                throw new IllegalArgumentException("The mapping workbook must contain exactly one sheet");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("The mapping workbook has no header row");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> headerIndexes = headerIndexes(headerRow, formatter, evaluator);
            List<WorkbookRow> rows = new ArrayList<>();
            for (int index = headerRow.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row, formatter, evaluator)) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new IllegalArgumentException("A photo import can contain at most 500 data rows");
                }
                rows.add(new WorkbookRow(
                        index + 1,
                        value(row, headerIndexes.get("admissionno"), formatter, evaluator),
                        value(row, headerIndexes.get("name"), formatter, evaluator),
                        value(row, headerIndexes.get("class"), formatter, evaluator),
                        value(row, headerIndexes.get("section"), formatter, evaluator),
                        value(row, headerIndexes.get("imageno"), formatter, evaluator)));
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("The mapping workbook contains no data rows");
            }
            return new ParsedWorkbook(sheet.getSheetName(), rows, REQUIRED_HEADERS);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not read the mapping workbook as a valid .xlsx file", ex);
        }
    }

    private Map<String, Integer> headerIndexes(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Cell cell : row) {
            String header = formatter.formatCellValue(cell, evaluator).trim().toLowerCase(Locale.ROOT);
            if (!header.isBlank()) {
                if (result.putIfAbsent(header, cell.getColumnIndex()) != null) {
                    throw new IllegalArgumentException("Duplicate workbook header: " + header);
                }
            }
        }
        Set<String> actual = result.keySet();
        List<String> missing = REQUIRED_HEADERS.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !actual.contains(value))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing workbook columns: " + String.join(", ", missing));
        }
        return result;
    }

    private boolean isBlank(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell, evaluator).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String value(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }

    public record ParsedWorkbook(String sheetName, List<WorkbookRow> rows, List<String> headers) {
    }

    public record WorkbookRow(
            int excelRow,
            String admissionNo,
            String name,
            String className,
            String sectionName,
            String imageNo) {
    }
}
