package com.custoking.ims.schoolcoreservice.photoimport;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DscImageNumber {
    private static final Pattern FILE_NAME = Pattern.compile(
            "^DSC_?0*([0-9]+)\\.(jpe?g|png)$", Pattern.CASE_INSENSITIVE);

    private DscImageNumber() {
    }

    static Optional<String> fromFileName(String fileName) {
        Matcher matcher = FILE_NAME.matcher(fileName == null ? "" : fileName.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(canonical(matcher.group(1)));
    }

    static String canonical(String value) {
        String digits = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!digits.matches("[0-9]+")) {
            throw new IllegalArgumentException("ImageNo must contain digits only");
        }
        String normalized = digits.replaceFirst("^0+(?!$)", "");
        return normalized.isEmpty() ? "0" : normalized;
    }
}
