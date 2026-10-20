package com.custoking.ims.schoolcoreservice.infrastructure;

/** Text cells only; numeric cells should retain their server-validated numeric type. */
public final class SpreadsheetText {
    private SpreadsheetText() {}

    public static String csv(String value) { return delimited(value, ','); }
    public static String delimited(String value, char delimiter) {
        String safe = value == null ? "" : value;
        int first = 0;
        while (first < safe.length() && (Character.isWhitespace(safe.charAt(first)) || Character.isSpaceChar(safe.charAt(first))
                || Character.isISOControl(safe.charAt(first)) || safe.charAt(first) == '\uFEFF')) first++;
        if ((!safe.isEmpty() && Character.isISOControl(safe.charAt(0)))
                || (first < safe.length() && "=+-@".indexOf(safe.charAt(first)) >= 0)) safe = "'" + safe;
        if (safe.indexOf(delimiter) >= 0 || safe.indexOf('"') >= 0 || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0)
            return '"' + safe.replace("\"", "\"\"") + '"';
        return safe;
    }
}
