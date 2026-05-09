package com.custoking.ims.service;

import com.custoking.ims.auth.service.AuthService;
import com.custoking.ims.dto.LoginRequest;
import com.custoking.ims.dto.LoginResult;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.security.AppUserDetailsService;
import com.custoking.ims.security.JwtService;
import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock AppUserRepository userRepository;
    @Mock JwtService jwtService;
    @Mock AppUserDetailsService userDetailsService;
    @Mock PasswordUtil passwordUtil;
    @Mock AuditLogService auditLogService;
    @InjectMocks AuthService authService;

    private AppUserEntity user;

    @BeforeEach
    void setUp() {
        user = new AppUserEntity();
        user.setId(1L);
        user.setEmail("admin@test.com");
        user.setPasswordHash("hashed-secret");
        user.setRole("ADMIN");
        user.setFullName("Admin User");
    }

    @Test
    void login_withValidCredentials_returnsLoginResult() {
        when(userRepository.findByEmailIgnoreCase("admin@test.com")).thenReturn(Optional.of(user));
        when(passwordUtil.verify("secret", "hashed-secret")).thenReturn(true);
        when(jwtService.generateToken(any(AppUserDetails.class))).thenReturn("access-jwt");
        when(jwtService.generateRefreshToken(any(AppUserDetails.class))).thenReturn("refresh-jwt");

        LoginResult result = authService.login(new LoginRequest("admin@test.com", "secret"));

        assertThat(result.refreshToken()).isEqualTo("refresh-jwt");
        assertThat(result.authResponse().accessToken()).isEqualTo("access-jwt");
        assertThat(result.authResponse().email()).isEqualTo("admin@test.com");
        assertThat(result.authResponse().role()).isEqualTo("ADMIN");
    }

    @Test
    void login_withWrongPassword_throwsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase("admin@test.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin@test.com", "wrong")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void login_withUnknownEmail_throwsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@test.com", "secret")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void refresh_withValidToken_returnsNewLoginResult() {
        when(jwtService.extractUsername("good-refresh")).thenReturn("admin@test.com");
        when(jwtService.isTokenValid("good-refresh", "admin@test.com")).thenReturn(true);
        when(userDetailsService.loadUserByUsername("admin@test.com"))
                .thenReturn(new AppUserDetails(user));
        when(jwtService.generateToken(any(AppUserDetails.class))).thenReturn("new-access-jwt");
        when(jwtService.generateRefreshToken(any(AppUserDetails.class))).thenReturn("new-refresh-jwt");

        LoginResult result = authService.refresh("good-refresh");

        assertThat(result.authResponse().accessToken()).isEqualTo("new-access-jwt");
        assertThat(result.refreshToken()).isEqualTo("new-refresh-jwt");
    }

    @Test
    void refresh_withExpiredToken_throwsUnauthorized() {
        when(jwtService.extractUsername("expired-refresh")).thenReturn("admin@test.com");
        when(jwtService.isTokenValid("expired-refresh", "admin@test.com")).thenReturn(false);

        assertThatThrownBy(() -> authService.refresh("expired-refresh"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
