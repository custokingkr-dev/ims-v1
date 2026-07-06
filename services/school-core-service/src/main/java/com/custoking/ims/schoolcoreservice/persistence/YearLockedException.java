package com.custoking.ims.schoolcoreservice.persistence;

public final class YearLockedException extends RuntimeException {

    public YearLockedException(String message) {
        super(message);
    }
}
