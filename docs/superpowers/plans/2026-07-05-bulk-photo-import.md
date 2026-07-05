# Bulk Student Photo Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Attach student photos during bulk import — embedded images from `.xlsx` (uploaded per-student by the browser) and a `Photo` link column for any format (fetched server-side into our private bucket), never storing raw links.

**Architecture:** Client-orchestrated, two-phase. Phase A imports rows (now `.xlsx/.xls/.ods/.csv` via SheetJS; `.xlsx` also parsed by ExcelJS for embedded images) and `confirmImport` returns an `AdmissionNo→studentId` map. Phase B, in the browser, attaches each student's photo: embedded → resize → existing `POST /students/{id}/photo`; link → new SSRF-guarded `POST /students/{id}/photo-from-url`. Both funnel into `StudentPhotoStorage` (private bucket, signed URLs). Photos are best-effort — a student is created regardless of photo outcome.

**Tech Stack:** Java 25 / Spring Boot 4.0.7, `java.net.http.HttpClient`, Spring `JdbcClient`, `StudentPhotoStorage` (Thumbnailator + GCS), Testcontainers + JUnit 5, Mockito, MockMvc; React 18 + TypeScript, SheetJS (`xlsx`), ExcelJS, Vitest.

## Global Constraints

- Never persist a raw external URL. Links are fetched server-side into our GCS; inaccessible links are skipped with a reason.
- Photos are best-effort: the student data import succeeds and is never rolled back by a photo failure.
- Per-photo max **2MB** (`student.photo.max-bytes`); image content-types **`image/jpeg`, `image/png`, `image/webp`** only; resize target **512px** (handled by `StudentPhotoStorage.upload`).
- SSRF: allow only `http`/`https`; reject loopback/private/link-local ranges and the metadata IP (`127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `::1`, `fc00::/7`, `fe80::/10`); ≤3 redirects (re-validate host each hop); connect+read timeout **5s**.
- `photo-from-url` failures return **422** with a `reason` in `{unreachable, not_an_image, too_large, blocked_host, timeout, invalid_url}`.
- Formats: data import `.xlsx/.xls/.ods/.csv`; **embedded** photos `.xlsx` only; **link** column any format. File cap **50MB**, row cap **500**.
- Authorization: `photo-from-url` requires the `student:write` route token and is tenant-scoped to the student's own school (superadmin bypass; cross-tenant → 403).

---

## File Structure

**Backend — `services/school-core-service/`**
- Create `src/main/java/.../infrastructure/ImageUrlFetcher.java` — SSRF-guarded image download. Task 1.
- Create `src/main/java/.../infrastructure/ImageFetchException.java` — typed failure with a `reason`. Task 1.
- Modify `src/main/java/.../api/StudentReadController.java` — add `POST /{id}/photo-from-url`. Task 2.
- Modify `src/main/java/.../persistence/StudentReadRepository.java` — `confirmImport` returns `insertedStudents`; add `schoolIdForStudent`. Tasks 2, 3.
- Test `src/test/java/.../infrastructure/ImageUrlFetcherTest.java` — Task 1.
- Test `src/test/java/.../api/StudentPhotoFromUrlControllerTest.java` — Task 2.
- Test `src/test/java/.../persistence/StudentImportPhotoIntegrationTest.java` — Task 3 (Testcontainers).

**Frontend — `frontend/`**
- Modify `package.json` — add `xlsx` (SheetJS). Task 4.
- Modify `src/pages/workspace/panels/BulkImportPanel.tsx` — multi-format parse, embedded extraction, Phase B, UI. Tasks 4–7.
- Test `src/pages/workspace/panels/BulkImportPanel.test.tsx` — extend. Tasks 4–7.

---

## Task 1: SSRF-guarded image fetcher

**Files:**
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/ImageFetchException.java`
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/ImageUrlFetcher.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/infrastructure/ImageUrlFetcherTest.java`

**Interfaces:**
- Produces:
  - `class ImageFetchException extends RuntimeException` with `String reason()` (one of the reason codes) and a message.
  - `class ImageUrlFetcher` (`@Component`) with `FetchedImage fetch(String url)` and a `record FetchedImage(byte[] data, String contentType)`; throws `ImageFetchException` on any guard violation.
  - `static boolean ImageUrlFetcher.isBlockedAddress(java.net.InetAddress addr)` — pure, unit-tested.

- [ ] **Step 1: Write the failing test** — pure host-blocking checks + a real fetch against a local HTTP server.

```java
package com.custoking.ims.schoolcoreservice.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.*;

class ImageUrlFetcherTest {

    private final ImageUrlFetcher fetcher = new ImageUrlFetcher(2_097_152L);

    @Test
    void blocksMetadataAndPrivateAndLoopbackAddresses() throws Exception {
        assertThat(ImageUrlFetcher.isBlockedAddress(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(ImageUrlFetcher.isBlockedAddress(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(ImageUrlFetcher.isBlockedAddress(InetAddress.getByName("10.1.2.3"))).isTrue();
        assertThat(ImageUrlFetcher.isBlockedAddress(InetAddress.getByName("172.16.9.9"))).isTrue();
        assertThat(ImageUrlFetcher.isBlockedAddress(InetAddress.getByName("192.168.1.1"))).isTrue();
        assertThat(ImageUrlFetcher.isBlockedAddress(InetAddress.getByName("::1"))).isTrue();
        assertThat(ImageUrlFetcher.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd"))
                .isInstanceOf(ImageFetchException.class)
                .extracting(e -> ((ImageFetchException) e).reason()).isEqualTo("invalid_url");
    }

    @Test
    void rejectsNonImageContentType() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", ex -> {
            byte[] body = "hello".getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            // Loopback is blocked by isBlockedAddress, so this asserts the host guard fires first.
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x";
            assertThatThrownBy(() -> fetcher.fetch(url))
                    .isInstanceOf(ImageFetchException.class)
                    .extracting(e -> ((ImageFetchException) e).reason()).isEqualTo("blocked_host");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchesAnImageFromAnAllowedHost() throws Exception {
        // Serve a tiny JPEG and bypass the loopback block via a fetcher whose address check is
        // overridden for the test (see production note in Step 3: allowLoopbackForTest flag).
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        server.createContext("/p.jpg", ex -> {
            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, jpeg.length);
            ex.getResponseBody().write(jpeg);
            ex.close();
        });
        server.start();
        try {
            ImageUrlFetcher loopbackOk = ImageUrlFetcher.forTestAllowingLoopback(2_097_152L);
            var img = loopbackOk.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/p.jpg");
            assertThat(img.contentType()).isEqualTo("image/jpeg");
            assertThat(img.data()).isEqualTo(jpeg);
        } finally {
            server.stop(0);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=ImageUrlFetcherTest test`
Expected: FAIL — `ImageUrlFetcher`/`ImageFetchException` do not exist (compilation error).

- [ ] **Step 3: Implement the exception + fetcher**

`ImageFetchException.java`:
```java
package com.custoking.ims.schoolcoreservice.infrastructure;

public class ImageFetchException extends RuntimeException {
    private final String reason;
    public ImageFetchException(String reason, String message) { super(message); this.reason = reason; }
    public String reason() { return reason; }
}
```

`ImageUrlFetcher.java`:
```java
package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@Component
public class ImageUrlFetcher {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final int MAX_REDIRECTS = 3;

    private final long maxBytes;
    private final boolean allowLoopbackForTest;

    public ImageUrlFetcher(@Value("${student.photo.max-bytes:2097152}") long maxBytes) {
        this(maxBytes, false);
    }
    private ImageUrlFetcher(long maxBytes, boolean allowLoopbackForTest) {
        this.maxBytes = maxBytes;
        this.allowLoopbackForTest = allowLoopbackForTest;
    }
    static ImageUrlFetcher forTestAllowingLoopback(long maxBytes) {
        return new ImageUrlFetcher(maxBytes, true);
    }

    public record FetchedImage(byte[] data, String contentType) {}

    public FetchedImage fetch(String rawUrl) {
        URI uri = parseHttp(rawUrl);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        int hops = 0;
        while (true) {
            validateHost(uri.getHost());
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new ImageFetchException("timeout", "timed out fetching " + uri.getHost());
            } catch (Exception e) {
                throw new ImageFetchException("unreachable", "could not fetch: " + e.getMessage());
            }
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                if (++hops > MAX_REDIRECTS) throw new ImageFetchException("unreachable", "too many redirects");
                String loc = resp.headers().firstValue("location")
                        .orElseThrow(() -> new ImageFetchException("unreachable", "redirect without location"));
                uri = uri.resolve(loc);
                if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                    throw new ImageFetchException("blocked_host", "redirect to non-http scheme");
                }
                continue;
            }
            if (code != 200) throw new ImageFetchException("unreachable", "HTTP " + code);
            String contentType = resp.headers().firstValue("content-type").orElse("")
                    .split(";")[0].trim().toLowerCase();
            if (!IMAGE_TYPES.contains(contentType)) {
                throw new ImageFetchException("not_an_image", "content-type " + contentType);
            }
            byte[] data = readBounded(resp.body());
            return new FetchedImage(data, contentType);
        }
    }

    private URI parseHttp(String rawUrl) {
        URI uri;
        try { uri = URI.create(rawUrl.trim()); } catch (RuntimeException e) {
            throw new ImageFetchException("invalid_url", "malformed url");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
            throw new ImageFetchException("invalid_url", "only http(s) urls are allowed");
        }
        return uri;
    }

    private void validateHost(String host) {
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(host); } catch (Exception e) {
            throw new ImageFetchException("unreachable", "cannot resolve host");
        }
        for (InetAddress addr : addrs) {
            if (isBlockedAddress(addr) && !allowLoopbackForTest) {
                throw new ImageFetchException("blocked_host", "host resolves to a blocked address");
            }
        }
    }

    static boolean isBlockedAddress(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress() || isUniqueLocalIpv6(addr);
    }

    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7
    }

    private byte[] readBounded(InputStream in) {
        try (in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) throw new ImageFetchException("too_large", "image exceeds " + maxBytes + " bytes");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (ImageFetchException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageFetchException("unreachable", "read error: " + e.getMessage());
        }
    }
}
```

> Note: `isSiteLocalAddress()` covers `10/8`, `172.16/12`, `192.168/16`; `isLinkLocalAddress()` covers `169.254/16` and IPv6 `fe80::/10`; loopback covers `127/8` and `::1`. The `169.254.169.254` metadata IP is link-local, so it is blocked. The `allowLoopbackForTest` flag exists ONLY for the local-HTTP-server happy-path test and is never set in production (the public constructor passes `false`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=ImageUrlFetcherTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/ImageFetchException.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/ImageUrlFetcher.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/infrastructure/ImageUrlFetcherTest.java
git commit -m "feat(school-core): SSRF-guarded image URL fetcher"
```

---

## Task 2: `POST /students/{id}/photo-from-url` endpoint

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StudentReadRepository.java` (add `schoolIdForStudent`)
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/StudentReadController.java`
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/StudentPhotoFromUrlControllerTest.java`

**Interfaces:**
- Consumes: `ImageUrlFetcher.fetch(String) -> FetchedImage(byte[] data, String contentType)`, `ImageFetchException.reason()` (Task 1); existing `StudentReadRepository.attachPhoto(Long, byte[], String)`.
- Produces:
  - `StudentReadRepository.schoolIdForStudent(Long id) -> Long` (throws `IllegalArgumentException("student not found")` if absent).
  - `POST /api/v1/students/{id}/photo-from-url` body `{ "url": "…" }` → `Map` from `attachPhoto` on success; **422** `{reason}` on `ImageFetchException`; **403** cross-tenant; **404** unknown student.

- [ ] **Step 1: Write the failing test** (standalone MockMvc; mock the fetcher + repo)

```java
package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.infrastructure.ImageFetchException;
import com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentPhotoFromUrlControllerTest {

    private final StudentReadRepository students = mock(StudentReadRepository.class);
    private final ImageUrlFetcher fetcher = mock(ImageUrlFetcher.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new StudentReadController(students, fetcher, "tok"))
            .addFilters(new TenantContextFilter())
            .build();

    @AfterEach
    void cleanup() { TenantContext.clear(); }

    @Test
    void storesFetchedPhoto() throws Exception {
        when(students.schoolIdForStudent(42L)).thenReturn(10L);
        when(fetcher.fetch("https://cdn.example.com/a.jpg"))
                .thenReturn(new ImageUrlFetcher.FetchedImage(new byte[]{1, 2, 3}, "image/jpeg"));
        when(students.attachPhoto(eq(42L), any(byte[].class), eq("image/jpeg"))).thenReturn(Map.of("id", 42L));

        mvc.perform(post("/api/v1/students/42/photo-from-url")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://cdn.example.com/a.jpg\"}"))
                .andExpect(status().isOk());
        verify(students).attachPhoto(eq(42L), any(byte[].class), eq("image/jpeg"));
    }

    @Test
    void mapsFetchFailureTo422WithReason() throws Exception {
        when(students.schoolIdForStudent(42L)).thenReturn(10L);
        when(fetcher.fetch(anyString())).thenThrow(new ImageFetchException("not_an_image", "content-type text/html"));

        mvc.perform(post("/api/v1/students/42/photo-from-url")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x/y\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("not_an_image"));
        verify(students, never()).attachPhoto(anyLong(), any(), any());
    }

    @Test
    void crossTenantIsForbidden() throws Exception {
        when(students.schoolIdForStudent(42L)).thenReturn(99L); // student belongs to school 99
        mvc.perform(post("/api/v1/students/42/photo-from-url")
                        .header("X-Student-Service-Token", "tok")
                        .header("X-Authenticated-Role", "ADMIN")
                        .header("X-Authenticated-School-Id", "10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x/y\"}"))
                .andExpect(status().isForbidden());
        verify(fetcher, never()).fetch(anyString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=StudentPhotoFromUrlControllerTest test`
Expected: FAIL — `StudentReadController` has no `ImageUrlFetcher` constructor param / no `photo-from-url` mapping / `schoolIdForStudent` missing (compilation error).

- [ ] **Step 3a: Add `schoolIdForStudent` to `StudentReadRepository`**

Add near `attachPhoto`:
```java
    public Long schoolIdForStudent(Long id) {
        return jdbc.sql("SELECT school_id FROM student.students WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("student not found"));
    }
```

- [ ] **Step 3b: Inject the fetcher + add the endpoint in `StudentReadController`**

Add the import `import com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher;`, `import com.custoking.ims.schoolcoreservice.infrastructure.ImageFetchException;`, `import com.custoking.ims.schoolcoreservice.security.TenantScope;` (if not present), and `import org.springframework.http.ResponseEntity;`. Change the constructor to accept and store `ImageUrlFetcher fetcher` (add a `private final ImageUrlFetcher fetcher;` field; assign it after `students`/`readToken`). Then add the endpoint below.

The endpoint returns a `ResponseEntity` so the 422 body can carry an explicit `reason` field (the test asserts `$.reason`) while the success path stays 200. `execute(...)` is the existing private helper that maps `IllegalArgumentException` → 400.

```java
    @PostMapping("/{id}/photo-from-url")
    public ResponseEntity<Map<String, Object>> attachPhotoFromUrl(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "student:write");
        Long schoolId;
        try {
            schoolId = students.schoolIdForStudent(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "student not found", ex);
        }
        TenantScope.resolveSchoolId(schoolId);
        String url = body.get("url") == null ? "" : String.valueOf(body.get("url"));
        try {
            ImageUrlFetcher.FetchedImage img = fetcher.fetch(url);
            return ResponseEntity.ok(execute(() -> students.attachPhoto(id, img.data(), img.contentType())));
        } catch (ImageFetchException ex) {
            return ResponseEntity.unprocessableEntity().body(Map.of("reason", ex.reason(), "ok", false));
        }
    }
```

> **Every existing `StudentReadController` construction must pass the fetcher.** The prod code uses Spring DI (auto). Existing tests construct `new StudentReadController(students, "token")` — update those call sites to `new StudentReadController(students, mock(ImageUrlFetcher.class), "token")`. Grep `new StudentReadController(` and fix each (e.g. `StudentReadControllerTest`, `StudentTenantScopingTest`, `StudentValidationTest`).

- [ ] **Step 3c: Add `insertedStudents` to `confirmImport`** (needed by Phase B; small, do it here)

In `StudentReadRepository.confirmImport`, declare `List<Map<String, Object>> insertedStudents = new ArrayList<>();` next to `skippedRows`. In the success branch (right after `inserted++;`), add:
```java
                insertedStudents.add(row("admissionNo", normalized.get("admissionNo"), "studentId", studentId));
```
Add `"insertedStudents", insertedStudents,` to the final `return row(...)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=StudentPhotoFromUrlControllerTest test` → PASS (3 tests). Then run the previously-touched controller tests to confirm the constructor change compiles: `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest='StudentReadControllerTest,StudentTenantScopingTest,StudentValidationTest' test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/StudentReadController.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StudentReadRepository.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/StudentPhotoFromUrlControllerTest.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/StudentReadControllerTest.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/StudentTenantScopingTest.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/api/StudentValidationTest.java
git commit -m "feat(school-core): POST /students/{id}/photo-from-url + confirmImport returns inserted ids"
```

---

## Task 3: Verify `confirmImport` id map end-to-end (Testcontainers)

**Files:**
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/StudentImportPhotoIntegrationTest.java`

**Interfaces:**
- Consumes: `StudentReadRepository.previewImport(Map)`, `confirmImport(Map)` returning `insertedStudents` (Task 2).

- [ ] **Step 1: Write the failing test**

Model the container/Flyway bootstrap on `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/SchoolStructureIntegrationTest.java` (migrate `tenant_school` + `student` schemas; seed 12 classes '1'..'12', a school, and academic year). Then:

```java
    @Test
    void confirmImport_returnsAdmissionNoToStudentIdMap() throws Exception {
        long schoolId = seedSchool(5, 2); // reuse the helper pattern from SchoolStructureIntegrationTest
        StudentReadRepository repo = new StudentReadRepository(jdbc,
                org.mockito.Mockito.mock(com.custoking.ims.schoolcoreservice.infrastructure.StudentPhotoStorage.class));

        Map<String, Object> preview = repo.previewImport(Map.of(
                "schoolId", schoolId,
                "rows", java.util.List.of(Map.of(
                        "Name", "Imp One", "Class", "1", "Section", "A", "AdmissionNo", "IMP-1", "Gender", "Male"))));
        String fileToken = (String) preview.get("fileToken");

        Map<String, Object> confirm = repo.confirmImport(Map.of("schoolId", schoolId, "fileToken", fileToken));

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> inserted =
                (java.util.List<Map<String, Object>>) confirm.get("insertedStudents");
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).get("admissionNo")).isEqualTo("IMP-1");
        assertThat(((Number) inserted.get(0).get("studentId")).longValue()).isPositive();
    }
```

> Copy the `@BeforeAll`/`@BeforeEach`/`seedSchool` scaffolding from `SchoolStructureIntegrationTest` verbatim into this new test class (statics `PG`, `dataSource`, `jdbc`, and the class/academic-year seed). Construct `repo` with a mocked `StudentPhotoStorage` since import does not read photos.

- [ ] **Step 2: Run test to verify it fails (or passes)**

Run (Docker up): `./mvnw.cmd -f services/school-core-service/pom.xml -Dtest=StudentImportPhotoIntegrationTest test`
Expected: PASS if Task 2's `insertedStudents` change is in place; if it FAILS with `insertedStudents == null`, Task 2 Step 3c was not applied — fix it. Do not accept a Docker skip as a pass.

- [ ] **Step 3: (no new production code — this task verifies Task 2)**

- [ ] **Step 4: Run + full-module regression**

Run: `./mvnw.cmd -f services/school-core-service/pom.xml test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/StudentImportPhotoIntegrationTest.java
git commit -m "test(school-core): confirmImport returns admissionNo->studentId map"
```

---

## Task 4: Multi-format parsing via SheetJS

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/src/pages/workspace/panels/BulkImportPanel.tsx`
- Test: `frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx`

**Interfaces:**
- Produces: `parseRows(file: File) => Promise<Record<string, string|number>[]>` inside BulkImportPanel — reads `.xlsx/.xls/.ods/.csv` uniformly via SheetJS; each row keyed by header, with `__rowNumber`.

- [ ] **Step 1: Add the dependency**

Run (from `frontend/`): `npm install xlsx@0.18.5`
Expected: `xlsx` added to `dependencies` in `package.json`.

- [ ] **Step 2: Write the failing test** (append to `BulkImportPanel.test.tsx`)

```tsx
import * as XLSX from 'xlsx';
// ... existing imports ...

it('parses an .xlsx and a .csv into the same row shape via SheetJS', async () => {
  // Build an .xlsx in memory and hand it to the panel's parser through the file input.
  const ws = XLSX.utils.aoa_to_sheet([['Name', 'Class', 'AdmissionNo'], ['Aya', '1', 'A-1']]);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Students');
  const xlsxBuf = XLSX.write(wb, { type: 'array', bookType: 'xlsx' });
  const file = new File([xlsxBuf], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

  vi.mocked(api.post).mockResolvedValue({ data: { rows: [], validCount: 0, errorCount: 0, warningCount: 0, fileToken: 't' } });
  render(<BulkImportPanel onRefresh={vi.fn()} />);
  const input = document.querySelector('input[type=file]') as HTMLInputElement;
  await userEvent.upload(input, file);

  await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/import/preview', expect.objectContaining({
    rows: expect.arrayContaining([expect.objectContaining({ Name: 'Aya', Class: '1', AdmissionNo: 'A-1' })]),
  })));
});
```
Add imports at the top of the test file: `import userEvent from '@testing-library/user-event';`, `import { render, screen, waitFor } from '@testing-library/react';` (extend existing), and `import api from '../../../services/api';` (already present). Ensure `vi.mock('../../../services/api')` is present.

- [ ] **Step 3: Run test to verify it fails**

Run (from `frontend/`): `npm test -- BulkImportPanel`
Expected: FAIL — the current parser routes `.xlsx` through ExcelJS and posts a different row shape / the SheetJS path doesn't exist yet.

- [ ] **Step 4: Implement `parseRows` with SheetJS**

At the top of `BulkImportPanel.tsx` add `import * as XLSX from 'xlsx';`. Replace the body of `handleBulkImportFile`'s parsing branch (the `if (ext.endsWith('.csv')) { rows = parseCsvRows(...) } else { rows = await parseXlsxRows(...) }`) with a single call to a new `parseRows`:

```tsx
  const parseRows = async (file: File): Promise<Record<string, string | number>[]> => {
    const buf = await file.arrayBuffer();
    const wb = XLSX.read(buf, { type: 'array' });
    const sheet = wb.Sheets[wb.SheetNames[0]];
    if (!sheet) return [];
    const json = XLSX.utils.sheet_to_json<Record<string, string | number>>(sheet, { defval: '', raw: false });
    return json.map((row, index) => ({ ...row, __rowNumber: index + 2 }));
  };
```
In `handleBulkImportFile`, replace the csv/xlsx branch with `rows = await parseRows(file);`. Keep the accept-extension guard but expand it (Task 7 updates the accept list). Leave `parseXlsxRows`/`parseCsvRows`/`cellToScalar`/`splitCsvLine` in place for now — Task 5 reuses ExcelJS for images; `parseCsvRows` becomes unused and is removed in Task 7.

- [ ] **Step 5: Run test to verify it passes**

Run (from `frontend/`): `npm test -- BulkImportPanel` → PASS. Then `npm run build` → success.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json \
        frontend/src/pages/workspace/panels/BulkImportPanel.tsx \
        frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx
git commit -m "feat(fe): parse xlsx/xls/ods/csv imports via SheetJS"
```

---

## Task 5: Extract embedded images from `.xlsx` and stage per row

**Files:**
- Modify: `frontend/src/pages/workspace/panels/BulkImportPanel.tsx`
- Test: `frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx`

**Interfaces:**
- Produces: `extractXlsxPhotos(file: File) => Promise<Map<number, { bytes: Uint8Array; contentType: string }>>` — maps a **1-based data-row index** (matching `__rowNumber - 1`, i.e. the Nth data row) to its embedded image, using ExcelJS anchors; only images anchored in the `Photo` column are kept. Returns empty map when there is no `Photo` column or no images.

- [ ] **Step 1: Write the failing test** (round-trip: build an xlsx WITH an embedded image via ExcelJS, then extract)

```tsx
it('extracts an embedded image and maps it to the row anchored in the Photo column', async () => {
  const ExcelJS = (await import('exceljs')).default;
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet('Students');
  ws.addRow(['Name', 'AdmissionNo', 'Photo']);       // row 1 header; Photo is column 3 (index 2)
  ws.addRow(['Aya', 'A-1', '']);                     // data row 1 -> sheet row 2
  const jpeg = new Uint8Array([0xFF, 0xD8, 0xFF, 0xE0, 0, 0]);
  const imageId = wb.addImage({ buffer: jpeg, extension: 'jpeg' });
  // Anchor the image's top-left into the Photo cell of the data row (col 2, row 1 — 0-indexed).
  ws.addImage(imageId, { tl: { col: 2, row: 1 }, ext: { width: 40, height: 40 } });
  const buf = await wb.xlsx.writeBuffer();
  const file = new File([buf], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

  const photos = await extractXlsxPhotos(file); // exported for test via a module-level helper (see Step 3)
  expect(photos.get(1)).toBeTruthy();            // data row 1
  expect(photos.get(1)!.contentType).toContain('jpeg');
});
```
> Export `extractXlsxPhotos` from the module (top-level function, not a closure) so the test can import it: `import { extractXlsxPhotos } from './BulkImportPanel';`.

- [ ] **Step 2: Run test to verify it fails**

Run (from `frontend/`): `npm test -- BulkImportPanel`
Expected: FAIL — `extractXlsxPhotos` is not exported / not implemented.

- [ ] **Step 3: Implement `extractXlsxPhotos`** (module-level export in `BulkImportPanel.tsx`)

```tsx
export async function extractXlsxPhotos(
  file: File,
): Promise<Map<number, { bytes: Uint8Array; contentType: string }>> {
  const result = new Map<number, { bytes: Uint8Array; contentType: string }>();
  const name = file.name.toLowerCase();
  if (!name.endsWith('.xlsx')) return result; // embedded images only supported for .xlsx
  const ExcelJS = (await import('exceljs')).default;
  const wb = new ExcelJS.Workbook();
  await wb.xlsx.load(await file.arrayBuffer());
  const sheet = wb.worksheets[0];
  if (!sheet) return result;

  // Find the Photo column (0-indexed) from the header row.
  let photoCol = -1;
  sheet.getRow(1).eachCell({ includeEmpty: true }, (cell, col) => {
    if (String(cell.value ?? '').trim().toLowerCase() === 'photo') photoCol = col - 1; // ExcelJS col is 1-based
  });
  if (photoCol < 0) return result;

  for (const img of sheet.getImages()) {
    const anchorCol = img.range?.tl?.nativeCol ?? img.range?.tl?.col;
    const anchorRow = img.range?.tl?.nativeRow ?? img.range?.tl?.row; // 0-indexed
    if (anchorCol == null || anchorRow == null) continue;
    if (Math.round(anchorCol) !== photoCol) continue;                 // only images in the Photo column
    const dataRow = Math.round(anchorRow); // sheet row (0-indexed); header is row 0, so dataRow 1 == first data row
    if (dataRow < 1) continue;
    const media = wb.getImage(Number(img.imageId));
    const buffer = media.buffer as ArrayBuffer;
    const ext = (media.extension || 'jpeg').toLowerCase();
    const contentType = ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : 'image/jpeg';
    result.set(dataRow, { bytes: new Uint8Array(buffer), contentType });
  }
  return result;
}
```
> Note: ExcelJS anchor fields are `nativeRow`/`nativeCol` (0-indexed) in recent versions, with `row`/`col` as fractional; the code reads `nativeCol/nativeRow` first, falling back to `col/row`. The returned map key is the **data-row ordinal** (1 = first data row), which equals `__rowNumber - 1` from `parseRows` (Task 4).

- [ ] **Step 4: Run test to verify it passes**

Run (from `frontend/`): `npm test -- BulkImportPanel` → PASS. Then `npm run build` → success.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/BulkImportPanel.tsx frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx
git commit -m "feat(fe): extract embedded .xlsx photos mapped to their student row"
```

---

## Task 6: Phase B — attach photos after import

**Files:**
- Modify: `frontend/src/pages/workspace/panels/BulkImportPanel.tsx`
- Test: `frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx`

**Interfaces:**
- Consumes: `parseRows` (Task 4), `extractXlsxPhotos` (Task 5); confirm response `{ insertedStudents: [{admissionNo, studentId}], ... }` (Task 2); endpoints `POST /students/{id}/photo` (multipart) and `POST /students/{id}/photo-from-url` `{url}` (Task 2).
- Produces: `attachPhotos(insertedStudents, stagedByRow, admissionByRow) => Promise<{ attached: number; skipped: {admissionNo: string; reason: string}[] }>` — module-level, exported for test. Concurrency 4.

- [ ] **Step 1: Write the failing test** (append)

```tsx
it('attaches embedded photos via multipart and link photos via photo-from-url', async () => {
  vi.mocked(api.post).mockReset();
  vi.mocked(api.post).mockResolvedValue({ data: { ok: true } });

  const staged = new Map<number, { kind: 'embedded'; bytes: Uint8Array; contentType: string } | { kind: 'link'; url: string }>([
    [1, { kind: 'embedded', bytes: new Uint8Array([1, 2]), contentType: 'image/jpeg' }],
    [2, { kind: 'link', url: 'https://cdn/x.jpg' }],
  ]);
  const admissionByRow = new Map<number, string>([[1, 'A-1'], [2, 'A-2']]);
  const inserted = [{ admissionNo: 'A-1', studentId: 11 }, { admissionNo: 'A-2', studentId: 22 }];

  const report = await attachPhotos(inserted, staged, admissionByRow);

  expect(report.attached).toBe(2);
  // embedded -> multipart to /students/11/photo
  expect(api.post).toHaveBeenCalledWith('/students/11/photo', expect.any(FormData), expect.objectContaining({ headers: expect.any(Object) }));
  // link -> /students/22/photo-from-url
  expect(api.post).toHaveBeenCalledWith('/students/22/photo-from-url', { url: 'https://cdn/x.jpg' });
});

it('records a skip (not a throw) when a photo fails', async () => {
  vi.mocked(api.post).mockReset();
  vi.mocked(api.post).mockRejectedValue({ response: { status: 422, data: { reason: 'unreachable' } } });
  const staged = new Map([[1, { kind: 'link' as const, url: 'https://cdn/x.jpg' }]]);
  const report = await attachPhotos([{ admissionNo: 'A-1', studentId: 11 }], staged, new Map([[1, 'A-1']]));
  expect(report.attached).toBe(0);
  expect(report.skipped[0]).toMatchObject({ admissionNo: 'A-1', reason: 'unreachable' });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `frontend/`): `npm test -- BulkImportPanel`
Expected: FAIL — `attachPhotos` not exported.

- [ ] **Step 3: Implement `attachPhotos`** (module-level export)

```tsx
type StagedPhoto =
  | { kind: 'embedded'; bytes: Uint8Array; contentType: string }
  | { kind: 'link'; url: string };

export async function attachPhotos(
  insertedStudents: Array<{ admissionNo: string; studentId: number }>,
  stagedByRow: Map<number, StagedPhoto>,
  admissionByRow: Map<number, string>,
): Promise<{ attached: number; skipped: Array<{ admissionNo: string; reason: string }> }> {
  const idByAdmission = new Map(insertedStudents.map((s) => [String(s.admissionNo), s.studentId]));
  const jobs: Array<{ admissionNo: string; studentId: number; photo: StagedPhoto }> = [];
  for (const [rowOrdinal, photo] of stagedByRow.entries()) {
    const admissionNo = admissionByRow.get(rowOrdinal);
    if (!admissionNo) continue;
    const studentId = idByAdmission.get(String(admissionNo));
    if (studentId == null) continue; // row wasn't inserted (skipped/error) — no photo to attach
    jobs.push({ admissionNo, studentId, photo });
  }

  let attached = 0;
  const skipped: Array<{ admissionNo: string; reason: string }> = [];
  const CONCURRENCY = 4;
  let cursor = 0;
  async function worker() {
    while (cursor < jobs.length) {
      const job = jobs[cursor++];
      try {
        if (job.photo.kind === 'embedded') {
          const blob = new Blob([job.photo.bytes], { type: job.photo.contentType });
          const fd = new FormData();
          fd.append('file', new File([blob], 'photo.jpg', { type: job.photo.contentType }));
          await api.post(`/students/${job.studentId}/photo`, fd, { headers: { 'Content-Type': 'multipart/form-data' } });
        } else {
          await api.post(`/students/${job.studentId}/photo-from-url`, { url: job.photo.url });
        }
        attached += 1;
      } catch (err: unknown) {
        const reason = (err as { response?: { data?: { reason?: string } } })?.response?.data?.reason
          || (err instanceof Error ? err.message : 'failed');
        skipped.push({ admissionNo: job.admissionNo, reason });
      }
    }
  }
  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
  return { attached, skipped };
}
```
> Embedded photos are uploaded via the existing multipart endpoint, which resizes server-side (2MB cap enforced there). Client-side downscaling of very large embedded images is out of scope for this task; the 2MB server cap rejects oversized ones, which surface as a skip.

- [ ] **Step 4: Run test to verify it passes**

Run (from `frontend/`): `npm test -- BulkImportPanel` → PASS. Then `npm run build` → success.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/BulkImportPanel.tsx frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx
git commit -m "feat(fe): attach bulk photos (embedded->multipart, link->photo-from-url)"
```

---

## Task 7: Wire Phase B into the panel + UI (accept list, staging, progress, report)

**Files:**
- Modify: `frontend/src/pages/workspace/panels/BulkImportPanel.tsx`
- Test: `frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx`

**Interfaces:**
- Consumes: `parseRows`, `extractXlsxPhotos`, `attachPhotos`, confirm's `insertedStudents`.

- [ ] **Step 1: Write the failing test** (end-to-end panel flow with an embedded photo)

```tsx
it('runs photo phase after import and shows the photo report', async () => {
  const ExcelJS = (await import('exceljs')).default;
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet('S');
  ws.addRow(['Name', 'Class', 'Section', 'AdmissionNo', 'Photo']);
  ws.addRow(['Aya', '1', 'A', 'A-1', '']);
  const imageId = wb.addImage({ buffer: new Uint8Array([0xFF, 0xD8, 0xFF, 0xE0, 0, 0]), extension: 'jpeg' });
  ws.addImage(imageId, { tl: { col: 4, row: 1 }, ext: { width: 40, height: 40 } });
  const file = new File([await wb.xlsx.writeBuffer()], 'roster.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

  vi.mocked(api.post).mockImplementation((url: string) => {
    if (url === '/students/import/preview') return Promise.resolve({ data: { rows: [{ rowNumber: 2, name: 'Aya', className: '1', sectionName: 'A', admissionNo: 'A-1', phone: '', status: 'Valid' }], validCount: 1, errorCount: 0, warningCount: 0, fileToken: 't' } });
    if (url === '/students/import/confirm') return Promise.resolve({ data: { done: true, inserted: 1, skipped: 0, skippedRows: [], insertedStudents: [{ admissionNo: 'A-1', studentId: 11 }] } });
    if (url === '/students/11/photo') return Promise.resolve({ data: { ok: true } });
    return Promise.resolve({ data: {} });
  });

  render(<BulkImportPanel onRefresh={vi.fn()} />);
  await userEvent.upload(document.querySelector('input[type=file]') as HTMLInputElement, file);
  await screen.findByRole('button', { name: /import 1 valid rows/i });
  await userEvent.click(screen.getByRole('button', { name: /import 1 valid rows/i }));

  await waitFor(() => expect(api.post).toHaveBeenCalledWith('/students/11/photo', expect.any(FormData), expect.any(Object)));
  await screen.findByText(/1 photo/i); // photo report line
});
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `frontend/`): `npm test -- BulkImportPanel`
Expected: FAIL — the panel does not stage photos on parse, does not run `attachPhotos` after confirm, and renders no photo report.

- [ ] **Step 3: Wire it into the component**

1. Add state: `const [photoReport, setPhotoReport] = useState<{ attached: number; skipped: Array<{ admissionNo: string; reason: string }> } | null>(null);` and refs to hold staged photos + admission-by-row between parse and confirm:
```tsx
  const stagedPhotosRef = useRef<Map<number, StagedPhoto>>(new Map());
  const admissionByRowRef = useRef<Map<number, string>>(new Map());
```
2. In `handleBulkImportFile`, after `rows = await parseRows(file);`, build the staging maps:
```tsx
      const embedded = await extractXlsxPhotos(file); // Map<dataRowOrdinal, {bytes, contentType}>
      const staged = new Map<number, StagedPhoto>();
      const admissionByRow = new Map<number, string>();
      rows.forEach((row, index) => {
        const ordinal = index + 1;
        const admissionNo = String(row['AdmissionNo'] ?? row['admissionNo'] ?? '').trim();
        if (admissionNo) admissionByRow.set(ordinal, admissionNo);
        const emb = embedded.get(ordinal);
        const link = String(row['Photo'] ?? row['PhotoUrl'] ?? '').trim();
        if (emb) staged.set(ordinal, { kind: 'embedded', bytes: emb.bytes, contentType: emb.contentType });
        else if (/^https?:\/\//i.test(link)) staged.set(ordinal, { kind: 'link', url: link });
      });
      stagedPhotosRef.current = staged;
      admissionByRowRef.current = admissionByRow;
```
3. In `confirmBulkImport`, after the import completes (in the inline branch and after the polling branch), if there are staged photos, run Phase B:
```tsx
      const insertedStudents = (confirmRes.data as { insertedStudents?: Array<{ admissionNo: string; studentId: number }> })?.insertedStudents || [];
      if (stagedPhotosRef.current.size > 0 && insertedStudents.length > 0) {
        setSaving('photos');
        const report = await attachPhotos(insertedStudents, stagedPhotosRef.current, admissionByRowRef.current);
        setPhotoReport(report);
      }
```
   Place this after `await onRefresh();` and before the final success toast; set `saving` back to `''` in the `finally`.
4. Render the photo report (below the existing skipped-rows card):
```tsx
      {photoReport ? (
        <div className="ck-card" style={{ marginTop: 16 }}>
          <div className="ck-card-h"><div className="ck-card-t">Photos — {photoReport.attached} attached · {photoReport.skipped.length} skipped</div></div>
          {photoReport.skipped.length ? <div className="ck-form-body">{photoReport.skipped.map((s, i) => <div key={i} className="ts" style={{ marginBottom: 8 }}>{s.admissionNo}: {s.reason}</div>)}</div> : null}
        </div>
      ) : null}
```
5. Update the accept list + copy: change the file input `accept=".xlsx,.csv"` to `accept=".xlsx,.xls,.ods,.csv"`, the extension guard in `handleBulkImportFile` from `.xlsx/.csv` to also allow `.xls`/`.ods`, the drop-zone sub-text to ".xlsx, .xls, .ods, .csv supported", and the size guard from `5 * 1024 * 1024` to `50 * 1024 * 1024`. Re-add a `Photo` row to `IMPORT_COLUMNS` (`{ key: 'Photo', required: false, example: 'embedded image or https://…', note: 'Embedded image (.xlsx) or a public image link (any format) — optional' }`) and update the footer copy to describe embedded-or-link. Remove the now-unused `parseXlsxRows`, `parseCsvRows`, `cellToScalar` helpers and the `splitCsvLine` import if no longer referenced.

- [ ] **Step 4: Run test + full suite + build**

Run (from `frontend/`): `npm test -- BulkImportPanel` → PASS. Then `npm test` (full) → all pass. Then `npm run build` → success.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/workspace/panels/BulkImportPanel.tsx frontend/src/pages/workspace/panels/BulkImportPanel.test.tsx
git commit -m "feat(fe): bulk import stages + attaches photos with a per-student report"
```

---

## Final verification (after all tasks)

- [ ] Backend: `./mvnw.cmd -f services/school-core-service/pom.xml test` → BUILD SUCCESS (Docker running so the integration tests execute).
- [ ] Frontend: from `frontend/`, `npm test` then `npm run build` → both green.
- [ ] Manual dev check after deploy: import an `.xlsx` with an embedded photo in the `Photo` column of one row and an `https://` image link in another → both students get photos (verify the signed-URL photo renders on the Students grid); a private/unreachable link → that student imports without a photo and appears in the photo skip report with a reason.
