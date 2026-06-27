package com.custoking.ims.identityservice.infrastructure;

import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end resilience tests for the identity -> tenant-school HTTP client.
 * Spins up a real local HTTP server so timeout and retry behaviour is exercised
 * over a socket, not mocked.
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

    // cloud-run-auth = "never" so the metadata server is never contacted; 10ms backoff keeps tests fast.
    private TenantSchoolClient client(int readTimeoutMs, int maxAttempts) {
        return new TenantSchoolClient(RestClient.builder(), baseUrl, "tok", "never", 1000, readTimeoutMs, maxAttempts, 10);
    }

    @Test
    void slowUpstreamFailsFastWithGatewayTimeout() {
        server.createContext("/api/v1/schools/5", exchange -> {
            try {
                Thread.sleep(1500); // exceeds the 200ms read timeout below
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"id\":5,\"name\":\"North\"}");
        });

        TenantSchoolClient client = client(200, 1); // no retry: fail fast

        assertThatThrownBy(() -> client.school(5L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void fastUpstreamParsesSchool() {
        server.createContext("/api/v1/schools/5", exchange -> respond(exchange, 200, "{\"id\":5,\"name\":\"North\"}"));

        TenantSchoolClient client = client(5000, 3);

        TenantSchoolClient.TenantSchoolRef ref = client.school(5L);
        assertThat(ref.id()).isEqualTo(5L);
        assertThat(ref.name()).isEqualTo("North");
    }

    @Test
    void retriesTransient503ThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/v1/zones/9", exchange -> {
            if (calls.incrementAndGet() < 3) {
                respond(exchange, 503, "{\"message\":\"unavailable\"}");
            } else {
                respond(exchange, 200, "{\"id\":9,\"name\":\"East\"}");
            }
        });

        TenantSchoolClient client = client(5000, 3);

        TenantSchoolClient.TenantSchoolRef ref = client.zone(9L);
        assertThat(ref.name()).isEqualTo("East");
        assertThat(calls.get()).isEqualTo(3); // two failures retried, third succeeded
    }

    @Test
    void exhaustsRetriesThenPropagatesStatus() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/v1/zones/9", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 503, "{\"message\":\"unavailable\"}");
        });

        TenantSchoolClient client = client(5000, 3);

        assertThatThrownBy(() -> client.zone(9L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(3); // all attempts exhausted
    }

    @Test
    void clientErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/v1/schools/7", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 400, "{\"message\":\"bad request\"}");
        });

        TenantSchoolClient client = client(5000, 3);

        assertThatThrownBy(() -> client.school(7L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(calls.get()).isEqualTo(1); // 4xx is deterministic: no retry
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
