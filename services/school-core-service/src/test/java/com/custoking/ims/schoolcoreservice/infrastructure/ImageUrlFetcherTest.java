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
