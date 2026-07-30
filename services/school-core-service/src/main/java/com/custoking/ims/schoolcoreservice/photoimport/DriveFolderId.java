package com.custoking.ims.schoolcoreservice.photoimport;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DriveFolderId {
    private static final Pattern RAW_ID = Pattern.compile("^[A-Za-z0-9_-]{10,200}$");
    private static final Pattern FOLDER_PATH = Pattern.compile("(?:^|/)folders/([A-Za-z0-9_-]{10,200})(?:/|$)");

    private DriveFolderId() {
    }

    static String parse(String value) {
        String candidate = value == null ? "" : value.trim();
        if (RAW_ID.matcher(candidate).matches()) {
            return candidate;
        }
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("drive.google.com")
                    || host.equalsIgnoreCase("docs.google.com"))) {
                throw invalid();
            }
            Matcher matcher = FOLDER_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (IllegalArgumentException ignored) {
            // Replaced with the stable API error below.
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Enter a valid Google Drive folder link or folder ID");
    }
}
