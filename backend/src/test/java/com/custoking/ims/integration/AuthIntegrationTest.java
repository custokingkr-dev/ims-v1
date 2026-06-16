package com.custoking.ims.integration;

import com.custoking.ims.AbstractIntegrationTest;
import com.custoking.ims.entity.AcademicYearEntity;
import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.repo.AcademicYearRepository;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.repo.SchoolRepository;
import com.custoking.ims.service.RbacService;
import com.custoking.ims.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIntegrationTest extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository userRepo;
    @Autowired SchoolRepository schoolRepo;
    @Autowired AcademicYearRepository yearRepo;
    @Autowired PasswordUtil passwordUtil;
    @Autowired RbacService rbacService;

    private static final String ADMIN_EMAIL    = "it-admin@test.com";
    private static final String ADMIN_PASSWORD = "Test@1234";

    @BeforeEach
    void seedData() {
        // Seed school (needed by dashboard endpoint)
        SchoolEntity school = schoolRepo.findAll().stream().findFirst().orElseGet(() -> {
            SchoolEntity s = new SchoolEntity();
            s.setName("Test School");
            s.setShortCode("TEST");
            return schoolRepo.save(s);
        });

        // Seed active academic year (needed by workspace/dashboard)
        yearRepo.findFirstByActiveTrue().orElseGet(() -> {
            AcademicYearEntity y = new AcademicYearEntity();
            y.setId("2024-25");
            y.setLabel("2024-25");
            y.setActive(true);
            return yearRepo.save(y);
        });

        // Seed admin user (BCrypt hash is expensive — only compute once)
        AppUserEntity admin = userRepo.findByEmailIgnoreCase(ADMIN_EMAIL).orElseGet(() -> {
            AppUserEntity u = new AppUserEntity();
            u.setFullName("IT Admin");
            u.setEmail(ADMIN_EMAIL);
            u.setPasswordHash(passwordUtil.hash(ADMIN_PASSWORD));
            u.setRole("ADMIN");
            u.setBranchId(school.getId());
            u.setBranchName(school.getName());
            return userRepo.save(u);
        });

        // Authorization is RBAC-based (the legacy `role` field confers no permissions),
        // so grant the ADMIN role — which carries workspace:access (V120) — otherwise
        // permission-gated endpoints like /api/v1/dashboard return 403.
        if (!rbacService.userHasPermission(admin.getId(), "workspace:access")) {
            rbacService.assignSchoolRole(admin.getId(), "ADMIN", school.getId(), null);
        }
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithAccessTokenAndCookie() {
        var response = post("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("accessToken")).isNotNull().asString().isNotBlank();
        assertThat(body).doesNotContainKey("refreshToken");
        assertThat(body.get("role")).isEqualTo("ADMIN");

        // Refresh token must arrive in HttpOnly cookie, not the response body
        List<String> setCookie = response.getHeaders().get("Set-Cookie");
        assertThat(setCookie).isNotNull().anyMatch(c -> c.startsWith("refresh_token="));
        String rtCookie = setCookie.stream()
                .filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow();
        assertThat(rtCookie).contains("HttpOnly");
        assertThat(rtCookie).contains("SameSite=Strict");
        assertThat(rtCookie).contains("Path=/api/v1/auth");
    }

    @Test
    void login_wrongPassword_returns401() {
        var response = post("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", "wrong-password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void login_unknownEmail_returns401() {
        var response = post("/api/v1/auth/login",
                Map.of("email", "nobody@test.com", "password", "whatever"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_missingPassword_returns400WithRequestId() {
        var response = post("/api/v1/auth/login", Map.of("email", ADMIN_EMAIL));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull().containsKey("requestId");
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    @Test
    void refresh_validRefreshCookie_returnsNewAccessToken() {
        // Login to obtain the Set-Cookie header
        var loginResp = post("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookieHeader = extractRefreshCookieValue(loginResp.getHeaders());

        // Send the refresh token as a Cookie header (mirrors what a browser does)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, "refresh_token=" + cookieHeader);
        var refreshResp = rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, headers), MAP_TYPE);

        assertThat(refreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = refreshResp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("accessToken")).isNotNull().asString().isNotBlank();
        assertThat(body).doesNotContainKey("refreshToken");
    }

    @Test
    void refresh_reusesOldCookieAfterRotation_returns401() {
        var loginResp = post("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstCookie = extractRefreshCookieValue(loginResp.getHeaders());

        var firstRefresh = refreshWithCookie(firstCookie);
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedCookie = extractRefreshCookieValue(firstRefresh.getHeaders());

        var replay = refreshWithCookie(firstCookie);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var secondRefresh = refreshWithCookie(rotatedCookie);
        assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refresh_invalidCookie_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, "refresh_token=not.a.real.token");
        var response = rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refresh_missingCookie_returns401() {
        var response = post("/api/v1/auth/refresh", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_clearsCookie() {
        var loginResp = post("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookieHeader = extractRefreshCookieValue(loginResp.getHeaders());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "refresh_token=" + cookieHeader);
        var response = rest.exchange(
                "/api/v1/auth/logout", HttpMethod.POST, new HttpEntity<>(null, headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<String> setCookie = response.getHeaders().get("Set-Cookie");
        assertThat(setCookie).isNotNull()
                .anyMatch(c -> c.startsWith("refresh_token=") && c.contains("Max-Age=0"));

        assertThat(refreshWithCookie(cookieHeader).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── RBAC: protected endpoint ──────────────────────────────────────────────

    @Test
    void dashboard_withoutToken_returns401Or403() {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/api/v1/dashboard", HttpMethod.GET, HttpEntity.EMPTY, MAP_TYPE);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    void dashboard_withValidToken_returns200() {
        String accessToken = loginAndGetToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/v1/dashboard", HttpMethod.GET,
                new HttpEntity<>(headers), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Error response shape ──────────────────────────────────────────────────

    @Test
    void error_response_containsRequiredFields() {
        var response = post("/api/v1/auth/login", Map.of("email", ADMIN_EMAIL));

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull()
                .containsKeys("status", "error", "message", "timestamp");
    }

    // ── Request correlation header ────────────────────────────────────────────

    @Test
    void requestId_header_is_echoed_back() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-ID", "test-correlation-123");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD), headers),
                MAP_TYPE);

        assertThat(response.getHeaders().getFirst("X-Request-ID")).isEqualTo("test-correlation-123");
    }

    // ── Health endpoint ───────────────────────────────────────────────────────

    @Test
    void actuator_health_isPubliclyAccessible() {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/actuator/health", HttpMethod.GET, HttpEntity.EMPTY, MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("status");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers), MAP_TYPE);
    }

    private String loginAndGetToken() {
        var resp = post("/api/v1/auth/login",
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) resp.getBody().get("accessToken");
    }

    private ResponseEntity<Map<String, Object>> refreshWithCookie(String refreshCookieValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.COOKIE, "refresh_token=" + refreshCookieValue);
        return rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(null, headers), MAP_TYPE);
    }

    /** Extracts the raw refresh token value from a Set-Cookie header. */
    private String extractRefreshCookieValue(HttpHeaders headers) {
        List<String> cookies = headers.get("Set-Cookie");
        assertThat(cookies).isNotNull();
        return cookies.stream()
                .filter(c -> c.startsWith("refresh_token="))
                .findFirst()
                .map(c -> c.split(";")[0].substring("refresh_token=".length()))
                .orElseThrow(() -> new AssertionError("No refresh_token Set-Cookie header found"));
    }
}
