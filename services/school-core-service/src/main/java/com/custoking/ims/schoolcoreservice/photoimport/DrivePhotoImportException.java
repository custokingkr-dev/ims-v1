package com.custoking.ims.schoolcoreservice.photoimport;

public class DrivePhotoImportException extends RuntimeException {
    private final String code;

    public DrivePhotoImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DrivePhotoImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
