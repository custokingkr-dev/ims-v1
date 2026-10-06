package com.custoking.ims.identityservice.security;

public class StepUpRequiredException extends RuntimeException {
    public StepUpRequiredException() { super("Passkey verification required"); }
}
