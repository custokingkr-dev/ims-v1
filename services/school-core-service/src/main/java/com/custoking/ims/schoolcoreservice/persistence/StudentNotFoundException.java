package com.custoking.ims.schoolcoreservice.persistence;

public class StudentNotFoundException extends RuntimeException {

    public StudentNotFoundException(String message) {
        super(message);
    }
}
