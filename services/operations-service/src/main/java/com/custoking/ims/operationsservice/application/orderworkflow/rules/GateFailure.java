package com.custoking.ims.operationsservice.application.orderworkflow.rules;

/**
 * One requirement that still blocks {@code Complete} on a stage (design D19). {@code code} is the failing gate's type
 * ({@code FIELDS_PRESENT}, {@code ALL_CHECKED}, ...); {@code message} says what is missing, for people, for example
 * "Save the controlled storage link". The screen lists these under the disabled button and in Upcoming actions.
 */
public record GateFailure(String code, String message) {
    public GateFailure {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("A gate failure needs a code");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("A gate failure needs a message");
    }
}
