package com.custoking.ims.dto;

/** Carries both the refresh token (for cookie) and the sanitised AuthResponse (for body). */
public record LoginResult(String refreshToken, AuthResponse authResponse) {}
