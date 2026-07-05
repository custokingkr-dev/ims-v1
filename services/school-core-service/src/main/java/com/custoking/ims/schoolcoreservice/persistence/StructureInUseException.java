package com.custoking.ims.schoolcoreservice.persistence;

/** Thrown when a class/section shrink would orphan students. Mapped to HTTP 409. */
public class StructureInUseException extends RuntimeException {
    public StructureInUseException(String message) {
        super(message);
    }
}
