package com.custoking.ims.identityservice.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end resilience test for the identity -> tenant-school HTTP client.
 * Spins up a real local HTTP server so the connect/read timeout behaviour is
 * exercised over a socket, not mocked.
 */
class TenantSchoolClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private TenantSchoolClient client(int readTimeoutMs) {
        // cloud-run-auth = "never" so the metadata server is never contacted.
        return new TenantSchoolClient(RestClient.builder(), baseUrl, "tok", "never", 1000, readTimeoutMs);
    }

    @Test
    void slowUpstreamFailsFastWithGatewayTimeout() {
        server.createContext("/api/v1/schools/5", exchange -> {
            try {
                Thread.sleep(1500); // exceeds the 200ms read timeout below
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, "{\"id\":5,\"name\":\"North\"}");
        });

        TenantSchoolClient client = client(200);

        assertThatThrownBy(() -> client.school(5L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void fastUpstreamParsesSchool() {
        server.createContext("/api/v1/schools/5", exchange -> respond(exchange, "{\"id\":5,\"name\":\"North\"}"));

        TenantSchoolClient client = client(5000);

        TenantSchoolClient.TenantSchoolRef ref = client.school(5L);
        assertThat(ref.id()).isEqualTo(5L);
        assertThat(ref.name()).isEqualTo("North");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
