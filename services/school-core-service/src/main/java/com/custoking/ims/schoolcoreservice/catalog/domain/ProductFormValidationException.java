package com.custoking.ims.schoolcoreservice.catalog.domain;

import java.util.Map;

public class ProductFormValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ProductFormValidationException(Map<String, String> fieldErrors) {
        super(fieldErrors.values().stream().findFirst().orElse("Validation failed"));
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() { return fieldErrors; }
}
