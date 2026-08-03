package com.custoking.ims.schoolcoreservice.photoimport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class PhotoImportWorkbookParser {
    static final int MAX_ROWS = 1000;
    static final long MAX_WORKBOOK_BYTES = 10L * 1024 * 1024;
    private static final List<String> REQUIRED_HEADERS =
            List.of("AdmissionNo", "Name", "Class", "Section", "ImageNo");

    public ParsedWorkbook parse(byte[] bytes, String fileName) {
        String extension = extension(fileName);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The mapping file is empty");
        }
        if (bytes.length > MAX_WORKBOOK_BYTES) {
            throw new IllegalArgumentException("The mapping file must be 10 MB or smaller");
        }
        return switch (extension) {
            case "xlsx", "xls" -> parseExcel(bytes, extension);
            case "csv" -> parseDelimited(bytes, ',', "CSV");
            case "tsv" -> parseDelimited(bytes, '\t', "TSV");
            default -> throw new IllegalArgumentException(
                    "The mapping file must be XLSX, XLS, CSV, or TSV");
        };
    }

    private ParsedWorkbook parseExcel(byte[] bytes, String extension) {
        if ("xlsx".equals(extension)) {
            ZipSecureFile.setMinInflateRatio(0.01);
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = mappingSheet(workbook, formatter, evaluator);
            return parseExcelSheet(sheet, formatter, evaluator);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Could not read the mapping file as a valid ." + extension + " workbook", ex);
        }
    }

    private ParsedWorkbook parseExcelSheet(
            Sheet sheet,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("The mapping workbook has no header row");
            }
            Map<String, Integer> headerIndexes = headerIndexes(headerRow, formatter, evaluator);
            List<WorkbookRow> rows = new ArrayList<>();
            for (int index = headerRow.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row, formatter, evaluator)) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new IllegalArgumentException(
                            "A photo import can contain at most " + MAX_ROWS + " data rows");
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
    }

    private Sheet mappingSheet(
            Workbook workbook,
            DataFormatter formatter,
            FormulaEvaluator evaluator) {
        if (workbook.getNumberOfSheets() == 1) {
            return workbook.getSheetAt(0);
        }
        List<Sheet> candidates = new ArrayList<>();
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) {
                continue;
            }
            Sheet sheet = workbook.getSheetAt(index);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                continue;
            }
            try {
                headerIndexes(headerRow, formatter, evaluator);
                candidates.add(sheet);
            } catch (IllegalArgumentException ex) {
                if (!isMissingHeaderError(ex)) {
                    throw ex;
                }
            }
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "The mapping workbook must contain one visible sheet with columns: "
                            + String.join(", ", REQUIRED_HEADERS));
        }
        throw new IllegalArgumentException(
                "The mapping workbook contains multiple visible mapping sheets; keep only one");
    }

    private static boolean isMissingHeaderError(IllegalArgumentException ex) {
        return ex.getMessage() != null && ex.getMessage().startsWith("Missing workbook columns:");
    }

    private ParsedWorkbook parseDelimited(byte[] bytes, char delimiter, String formatName) {
        String content = decodeUtf8(bytes, formatName);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(true)
                .get();
        try (CSVParser parser = format.parse(new StringReader(content))) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) {
                throw new IllegalArgumentException("The mapping file has no header row");
            }
            CSVRecord header = iterator.next();
            Map<String, Integer> headerIndexes = headerIndexes(values(header));
            List<WorkbookRow> rows = new ArrayList<>();
            while (iterator.hasNext()) {
                CSVRecord record = iterator.next();
                if (isBlank(record)) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new IllegalArgumentException(
                            "A photo import can contain at most " + MAX_ROWS + " data rows");
                }
                rows.add(new WorkbookRow(
                        Math.toIntExact(record.getRecordNumber()),
                        value(record, headerIndexes.get("admissionno")),
                        value(record, headerIndexes.get("name")),
                        value(record, headerIndexes.get("class")),
                        value(record, headerIndexes.get("section")),
                        value(record, headerIndexes.get("imageno"))));
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("The mapping file contains no data rows");
            }
            return new ParsedWorkbook(formatName, rows, REQUIRED_HEADERS);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Could not read the mapping file as valid " + formatName, ex);
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
        validateHeaders(result);
        return result;
    }

    private Map<String, Integer> headerIndexes(List<String> headers) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index).trim().toLowerCase(Locale.ROOT);
            if (!header.isBlank() && result.putIfAbsent(header, index) != null) {
                throw new IllegalArgumentException("Duplicate workbook header: " + header);
            }
        }
        validateHeaders(result);
        return result;
    }

    private void validateHeaders(Map<String, Integer> headerIndexes) {
        Set<String> actual = headerIndexes.keySet();
        List<String> missing = REQUIRED_HEADERS.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !actual.contains(value))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing workbook columns: " + String.join(", ", missing));
        }
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

    private static List<String> values(CSVRecord record) {
        List<String> values = new ArrayList<>(record.size());
        for (int index = 0; index < record.size(); index++) {
            values.add(record.get(index));
        }
        return values;
    }

    private static boolean isBlank(CSVRecord record) {
        for (String value : record) {
            if (!value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String value(CSVRecord record, int column) {
        return column < record.size() ? record.get(column).trim() : "";
    }

    private static String decodeUtf8(byte[] bytes, String formatName) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException(
                    formatName + " mapping files must use UTF-8 encoding", ex);
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int separator = fileName.lastIndexOf('.');
        return separator < 0 ? "" : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
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
