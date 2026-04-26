package com.custoking.ims.util;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordUtil {
    private final PasswordEncoder encoder;

    public PasswordUtil(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean verify(String raw, String hash) {
        return encoder.matches(raw, hash);
    }
}
