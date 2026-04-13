package com.custoking.ims.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class AadharCrypto {
    private final SecretKeySpec keySpec;

    public AadharCrypto(@Value("${app.aadhar.secret:CustokingAadhar!}") String secret) {
        byte[] key = java.util.Arrays.copyOf(secret.getBytes(StandardCharsets.UTF_8), 16);
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt Aadhaar");
        }
    }

    public String hash(String plain) {
        if (plain == null || plain.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash Aadhaar");
        }
    }

    public String mask(String plain) {
        if (plain == null || plain.length() < 4) return "****";
        return "********" + plain.substring(plain.length() - 4);
    }
}
