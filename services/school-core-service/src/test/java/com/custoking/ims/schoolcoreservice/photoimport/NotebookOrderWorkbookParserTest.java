package com.custoking.ims.schoolcoreservice.photoimport;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotebookOrderWorkbookParserTest {
    private final NotebookOrderWorkbookParser parser = new NotebookOrderWorkbookParser();

    @Test
    void parsesAliasesAndAggregatesNotebookCategories() throws Exception {
        byte[] workbook = workbook("ORD-42", false, false);

        var preview = parser.parse(workbook, "order.xlsx", "ORD-42");

        assertEquals(new NotebookOrderWorkbookParser.Preview(
                "ORD-42", "order.xlsx", "Order", "ORD-42", 26, 5, 6, 3, 4, 8, 5), preview);
    }

    @Test
    void rejectsEmbeddedOrderIdThatDoesNotMatchTheOpenOrder() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(workbook("ORD-OTHER", false, false), "order.xlsx", "ORD-42"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(workbook("NB-DPS_GUR-26-11-13-0041", false, false), "order.xlsx", "CK-42", "NB-DPS_GUR-26-11-13-0042"));
    }

    /** Design §3.6: the client's sheets carry the SOP order number, which names the open order as well as its CK id does. */
    @Test
    void acceptsAWorkbookNamingTheOrderByItsNumber() throws Exception {
        var preview = parser.parse(workbook("nb-dps_gur-26-11-13-0042", false, false), "order.xlsx", "CK-42", "NB-DPS_GUR-26-11-13-0042");
        assertEquals("CK-42", preview.orderId());
        assertEquals("nb-dps_gur-26-11-13-0042", preview.workbookOrderId());
        assertEquals(26, preview.total());
        assertEquals("CK-42", parser.parse(workbook("CK-42", false, false), "order.xlsx", "CK-42", "NB-DPS_GUR-26-11-13-0042").orderId());
    }

    @Test
    void rejectsMultipleMatchingOrderTables() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            for (String name : new String[]{"First", "Second"}) {
                var sheet = workbook.createSheet(name);
                sheet.createRow(0).createCell(0).setCellValue("Type");
                sheet.getRow(0).createCell(1).setCellValue("Quantity");
                sheet.createRow(1).createCell(0).setCellValue("King");
                sheet.getRow(1).createCell(1).setCellValue(1);
            }
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertThrows(IllegalArgumentException.class, () -> parser.parse(bytes, "order.xlsx", "ORD-42"));
    }

    @Test
    void rejectsFractionalQuantitiesAndWorkbookFormulas() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(workbook("ORD-42", true, false), "order.xlsx", "ORD-42"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(workbook("ORD-42", false, true), "order.xlsx", "ORD-42"));
    }

    @Test
    void rejectsInvalidAndOversizedFiles() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[]{1, 2, 3}, "order.xlsx", "ORD-42"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(new byte[(int) NotebookOrderWorkbookParser.MAX_WORKBOOK_BYTES + 1], "order.xlsx", "ORD-42"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(new byte[]{1}, "order.xls", "ORD-42"));
    }

    @Test
    void rejectsLegacyWorkbookDataRenamedAsXlsx() throws Exception {
        byte[] legacy;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Order").createRow(0).createCell(0).setCellValue("not xlsx");
            workbook.write(output);
            legacy = output.toByteArray();
        }
        assertThrows(IllegalArgumentException.class, () -> parser.parse(legacy, "order.xlsx", "ORD-42"));
    }

    private byte[] workbook(String orderId, boolean fractional, boolean formula) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Order");
            var idRow = sheet.createRow(0);
            idRow.createCell(0).setCellValue("Order Number");
            idRow.createCell(1).setCellValue(orderId);
            var header = sheet.createRow(2);
            header.createCell(0).setCellValue("Notebook Type");
            header.createCell(1).setCellValue("Qty");
            String[] types = {"King", "Long", "Jumbo King", "Jumbo Long", "FA"};
            int[] quantities = {5, 6, 3, 4, 8};
            for (int i = 0; i < types.length; i++) {
                var row = sheet.createRow(i + 3);
                row.createCell(0).setCellValue(types[i]);
                row.createCell(1).setCellValue(quantities[i]);
            }
            if (fractional) sheet.getRow(3).getCell(1).setCellValue(1.5);
            if (formula) sheet.getRow(3).getCell(1).setCellFormula("5+1");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
