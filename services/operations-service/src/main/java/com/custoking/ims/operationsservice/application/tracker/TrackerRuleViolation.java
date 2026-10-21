package com.custoking.ims.operationsservice.application.tracker;

/** A tracker business rule was broken. The code is stable for callers; the message is for people. */
public class TrackerRuleViolation extends RuntimeException {
    private final String code;

    public TrackerRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
