package com.custoking.ims.schoolcoreservice.persistence;

/** A key already identifies a different collection; retrying must never create another. */
@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
public class PaymentConflictException extends RuntimeException {
    public PaymentConflictException(String message) { super(message); }
}
