package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormValidationException;
import com.custoking.ims.schoolcoreservice.persistence.ProductCatalogRepository.CatalogReferenceConflict;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogProductFormExceptionHandler {
    @ExceptionHandler(ProductFormValidationException.class)
    public ResponseEntity<?> invalid(ProductFormValidationException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage(), "fieldErrors", exception.fieldErrors()));
    }
    @ExceptionHandler(CatalogReferenceConflict.class)
    public ResponseEntity<?> referenced(CatalogReferenceConflict exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage(), "referencingOrderCount", exception.referencingOrderCount()));
    }
}
