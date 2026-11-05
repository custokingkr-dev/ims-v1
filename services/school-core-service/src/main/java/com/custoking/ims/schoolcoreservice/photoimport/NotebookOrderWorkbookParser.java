package com.custoking.ims.schoolcoreservice.photoimport;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class NotebookOrderWorkbookParser {
    public static final long MAX_WORKBOOK_BYTES = 10L * 1024 * 1024;
    private static final int MAX_ROWS = 1000;
    private static final Set<String> TYPE_HEADERS =
            Set.of("notebooktype", "notebook", "type", "notebooksize", "size", "item", "product", "description");
    private static final Set<String> QUANTITY_HEADERS =
            Set.of("quantity", "qty", "totalquantity", "noofnotebooks", "notebookquantity", "orderqty");
    private static final Set<String> ORDER_LABELS =
            Set.of("orderid", "orderno", "ordernumber", "ordernumberid", "pono", "purchaseorderno");
    private static final Pattern FORMATIVE = Pattern.compile("\\bFA\\b|FORMATIVE|ASSESSMENT");
    private static final Pattern TOTAL = Pattern.compile("^(GRAND\\s*)?TOTAL$");

    public Preview parse(byte[] bytes, String fileName, String orderId) {
        String normalizedName = fileName == null ? "" : fileName.strip();
        int separator = Math.max(normalizedName.lastIndexOf('/'), normalizedName.lastIndexOf('\\'));
        normalizedName = normalizedName.substring(separator + 1);
        if (normalizedName.length() > 255) throw new IllegalArgumentException("The workbook file name is too long");
        if (!normalizedName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Upload an .xlsx workbook");
        }
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("The workbook is empty");
        if (bytes.length > MAX_WORKBOOK_BYTES) throw new IllegalArgumentException("The workbook must be 10 MB or smaller");
        if (orderId == null || orderId.isBlank()) throw new IllegalArgumentException("An order ID is required");

        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(20L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
        validateZipBudget(bytes);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (!(workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook)) {
                throw new IllegalArgumentException("Upload an .xlsx workbook");
            }
            if (workbook.getNumberOfSheets() > 10) throw new IllegalArgumentException("The workbook has too many sheets");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<Table> tables = new ArrayList<>();
            String workbookOrderId = findOrderId(workbook, formatter);
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) continue;
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet.getLastRowNum() > MAX_ROWS + 60) throw new IllegalArgumentException("The workbook has too many rows");
                Table table = findTable(sheet, formatter);
                if (table != null) tables.add(table);
            }
            if (tables.isEmpty()) {
                throw new IllegalArgumentException("The workbook needs a row with NOTEBOOK TYPE and QUANTITY columns");
            }
            if (tables.size() > 1) {
                throw new IllegalArgumentException("The workbook has multiple matching sheets; keep one order table");
            }
            if (workbookOrderId != null && !workbookOrderId.equalsIgnoreCase(orderId.strip())) {
                throw new IllegalArgumentException("The workbook order ID does not match the open order");
            }
            return totals(tables.getFirst(), normalizedName, workbookOrderId, orderId.strip(), formatter);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not read the file as a valid .xlsx workbook", ex);
        }
    }

    private Table findTable(Sheet sheet, DataFormatter formatter) {
        Table match = null;
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 59); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Integer typeColumn = null;
            Integer quantityColumn = null;
            for (Cell cell : row) {
                validateCell(cell);
                String header = normalize(formatter.formatCellValue(cell));
                if (TYPE_HEADERS.contains(header)) typeColumn = cell.getColumnIndex();
                if (QUANTITY_HEADERS.contains(header)) quantityColumn = cell.getColumnIndex();
            }
            if (typeColumn != null && quantityColumn != null) {
                if (match != null) throw new IllegalArgumentException("The sheet has multiple notebook order tables");
                match = new Table(sheet, rowIndex, typeColumn, quantityColumn);
            }
        }
        return match;
    }

    private Preview totals(Table table, String fileName, String workbookOrderId, String orderId, DataFormatter formatter) {
        long total = 0;
        long king = 0;
        long longBooks = 0;
        long jumboKing = 0;
        long jumboLong = 0;
        long fa = 0;
        int validRows = 0;
        int lastRow = table.sheet().getLastRowNum();
        for (int rowIndex = table.headerRow() + 1; rowIndex <= lastRow; rowIndex++) {
            Row row = table.sheet().getRow(rowIndex);
            if (row == null) continue;
            String type = value(row, table.typeColumn(), formatter).strip();
            String rawQuantity = value(row, table.quantityColumn(), formatter).strip();
            if (type.isEmpty() || rawQuantity.isEmpty() || TOTAL.matcher(type.toUpperCase(Locale.ROOT)).matches()) continue;
            long quantity = quantity(rawQuantity, rowIndex + 1);
            if (quantity == 0) continue;
            validRows++;
            if (validRows > MAX_ROWS) throw new IllegalArgumentException("An order workbook can contain at most " + MAX_ROWS + " data rows");
            total = add(total, quantity);
            String upper = type.toUpperCase(Locale.ROOT);
            if (FORMATIVE.matcher(upper).find()) fa = add(fa, quantity);
            else if (upper.contains("JUMBO") && upper.contains("KING")) jumboKing = add(jumboKing, quantity);
            else if (upper.contains("JUMBO") && upper.contains("LONG")) jumboLong = add(jumboLong, quantity);
            else if (Pattern.compile("\\bKING\\b").matcher(upper).find()) king = add(king, quantity);
            else if (Pattern.compile("\\bLONG\\b").matcher(upper).find()) longBooks = add(longBooks, quantity);
        }
        if (validRows == 0) throw new IllegalArgumentException("No notebook order rows with a positive quantity were found");
        return new Preview(orderId, fileName, table.sheet().getSheetName(), workbookOrderId, total, king, longBooks,
                jumboKing, jumboLong, fa, validRows);
    }

    private String findOrderId(Workbook workbook, DataFormatter formatter) {
        String found = null;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) continue;
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 24); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (Cell cell : row) {
                    validateCell(cell);
                    if (!ORDER_LABELS.contains(normalize(formatter.formatCellValue(cell)))) continue;
                    String value = nextValue(row, cell.getColumnIndex(), formatter);
                    if (value == null && rowIndex < sheet.getLastRowNum()) {
                        Row nextRow = sheet.getRow(rowIndex + 1);
                        if (nextRow != null) value = nextValue(nextRow, cell.getColumnIndex() - 1, formatter);
                    }
                    if (value != null) {
                        if (found != null && !found.equalsIgnoreCase(value)) {
                            throw new IllegalArgumentException("The workbook contains conflicting order IDs");
                        }
                        found = value;
                    }
                }
            }
        }
        return found;
    }

    private String nextValue(Row row, int column, DataFormatter formatter) {
        for (int index = Math.max(0, column + 1); index < Math.min(row.getLastCellNum(), 32); index++) {
            Cell cell = row.getCell(index);
            if (cell == null) continue;
            validateCell(cell);
            String value = formatter.formatCellValue(cell).strip();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private String value(Row row, int column, DataFormatter formatter) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return "";
        validateCell(cell);
        return formatter.formatCellValue(cell);
    }

    private void validateCell(Cell cell) {
        if (cell.getColumnIndex() >= 32) throw new IllegalArgumentException("The workbook may have at most 32 columns");
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA) {
            throw new IllegalArgumentException("Workbook formulas are not permitted; paste values instead");
        }
        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && cell.getStringCellValue().length() > 4096) {
            throw new IllegalArgumentException("Workbook cells must be 4096 characters or shorter");
        }
    }

    private long quantity(String raw, int row) {
        String normalized = raw.replace(",", "").replace(" ", "");
        try {
            java.math.BigDecimal parsed = new java.math.BigDecimal(normalized);
            long quantity = parsed.longValueExact();
            if (quantity < 0 || quantity > 100_000_000L) throw new ArithmeticException();
            return quantity;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("Quantity in row " + row + " must be a whole number from 0 to 100,000,000");
        }
    }

    private long add(long left, long right) {
        long total = Math.addExact(left, right);
        if (total > 100_000_000L) throw new IllegalArgumentException("The workbook total exceeds 100,000,000 notebooks");
        return total;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void validateZipBudget(byte[] bytes) {
        long total = 0;
        int entries = 0;
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 100) throw new IllegalArgumentException("The workbook has too many ZIP entries");
                long size = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    size += read;
                    total += read;
                    if (size > 20L * 1024 * 1024 || total > 30L * 1024 * 1024) {
                        throw new IllegalArgumentException("The workbook expands beyond the safe size limit");
                    }
                }
            }
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Invalid workbook archive", ex);
        }
    }

    private record Table(Sheet sheet, int headerRow, int typeColumn, int quantityColumn) { }

    public record Preview(String orderId, String fileName, String sheetName, String workbookOrderId, long total,
                          long king, long longBooks, long jumboKing, long jumboLong, long fa, int validRows) { }
}
