package com.custoking.ims.schoolcoreservice.persistence;

/**
 * Signals that a student import was deliberately rejected before doing work because
 * another import owns the school or the bounded fleet-wide import capacity.
 */
public final class ImportAdmissionException extends RuntimeException {
    private final String code;
    private final int retryAfterSeconds;

    public ImportAdmissionException(String code, String message, int retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
        return code;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
