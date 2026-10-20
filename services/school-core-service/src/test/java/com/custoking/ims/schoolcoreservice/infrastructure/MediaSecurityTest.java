package com.custoking.ims.schoolcoreservice.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.assertj.core.api.Assertions.*;

class MediaSecurityTest {
    @Test void slowBodyIsCancelledWithinWholeFetchDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { var t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/slow", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, 100);
            try {
                for (int i = 0; i < 100; i++) { exchange.getResponseBody().write(i); exchange.getResponseBody().flush(); Thread.sleep(250); }
            } catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start();
        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> ImageUrlFetcher.forTestAllowingLoopback(1000).fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"))
                    .isInstanceOf(ImageFetchException.class).extracting(e -> ((ImageFetchException)e).reason()).isEqualTo("timeout");
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isBetween(4500L, 6500L);
        } finally { server.stop(0); }
    }
    @Test void rejectsUrlCredentialsAndUnapprovedPorts() {
        var fetcher = new ImageUrlFetcher(1000);
        assertThatThrownBy(() -> fetcher.fetch("https://user:pass@8.8.8.8/a")).isInstanceOf(ImageFetchException.class);
        assertThatThrownBy(() -> fetcher.fetch("https://8.8.8.8:8443/a")).isInstanceOf(ImageFetchException.class);
    }
    @Test void spreadsheetTextNeutralizesWhitespaceAndControlPrefixedFormulas() {
        for (String value : new String[]{"=1+1", " +1", "\t=1", "\r@x", "\n-x", "\uFEFF=1", "\0=1"})
            assertThat(SpreadsheetText.csv(value)).contains("'" + value.replace("\"", "\"\""));
        assertThat(SpreadsheetText.csv("A, B")).isEqualTo("\"A, B\"");
        assertThat(SpreadsheetText.csv("ordinary text")).isEqualTo("ordinary text");
        assertThat(SpreadsheetText.delimited("A\tB", '\t')).isEqualTo("\"A\tB\"");
    }
    @Test void decodingAdmissionIsBoundedAndReleased() {
        try (var first = MediaWorkBudget.acquire(); var second = MediaWorkBudget.acquire()) {
            assertThatThrownBy(MediaWorkBudget::acquire).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        try (var availableAgain = MediaWorkBudget.acquire()) { assertThat(availableAgain).isNotNull(); }
    }

    @Test void newPhotoUploadsHavePrivateNoStoreMetadataAndUnapprovedLegacyUrlsAreHidden() {
        var photos = new StudentPhotoStorage("private-bucket", 60, 512, 5242880, "signer@example.test");
        var storage = org.mockito.Mockito.mock(com.google.cloud.storage.Storage.class);
        org.springframework.test.util.ReflectionTestUtils.setField(photos, "storage", storage);
        photos.uploadNormalizedPortrait("school1", 1, new byte[]{1, 2, 3});
        var metadata = org.mockito.ArgumentCaptor.forClass(com.google.cloud.storage.BlobInfo.class);
        org.mockito.Mockito.verify(storage).create(metadata.capture(), org.mockito.ArgumentMatchers.any(byte[].class));
        assertThat(metadata.getValue().getCacheControl()).isEqualTo("private, max-age=0, no-store");
        assertThat(photos.toDisplayUrl("http://example.test/photo.jpg")).isNull();
        assertThat(photos.toDisplayUrl("https://tracking.example.test/photo.jpg")).isNull();
        assertThat(photos.toDisplayUrl("https://storage.googleapis.com/other-bucket/schools/a.jpg")).isNull();
        assertThat(photos.toDisplayUrl("https://storage.googleapis.com/private-bucket/schools/a.jpg")).isNull();
    }

    @Test void streamingBodyAbortsBeforeOversizedAllocation() {
        var subscription = org.mockito.Mockito.mock(java.util.concurrent.Flow.Subscription.class);
        var body = new BoundedHttpBody(4);
        body.onSubscribe(subscription);
        body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[]{1, 2, 3})));
        body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[]{4, 5})));
        org.mockito.Mockito.verify(subscription).cancel();
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
        var valid = new BoundedHttpBody(4);
        valid.onSubscribe(org.mockito.Mockito.mock(java.util.concurrent.Flow.Subscription.class));
        valid.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        valid.onComplete();
        assertThat(valid.getBody().toCompletableFuture().join()).containsExactly(1, 2, 3, 4);
    }

    @Test void invalidUrlChurnCannotEvadeAuthenticatedFetchQuota() {
        var fetcher = new ImageUrlFetcher(1000);
        com.custoking.ims.schoolcoreservice.security.TenantContext.set(new com.custoking.ims.schoolcoreservice.security.TenantContext(
                908821L, "test@example.test", "ADMIN", 908821L, null));
        try {
            for (int i = 0; i < 20; i++) {
                int attempt = i;
                assertThatThrownBy(() -> fetcher.fetch("file:///invalid-" + attempt)).isInstanceOf(ImageFetchException.class)
                        .extracting(error -> ((ImageFetchException) error).reason()).isEqualTo("invalid_url");
            }
            assertThatThrownBy(() -> fetcher.fetch("file:///other")).isInstanceOf(ImageFetchException.class)
                    .extracting(error -> ((ImageFetchException) error).reason()).isEqualTo("busy");
        } finally { com.custoking.ims.schoolcoreservice.security.TenantContext.clear(); }
    }
}
