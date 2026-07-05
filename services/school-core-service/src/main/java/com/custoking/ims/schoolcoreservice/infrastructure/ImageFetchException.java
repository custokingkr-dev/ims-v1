package com.custoking.ims.schoolcoreservice.infrastructure;

public class ImageFetchException extends RuntimeException {
    private final String reason;
    public ImageFetchException(String reason, String message) { super(message); this.reason = reason; }
    public String reason() { return reason; }
}
