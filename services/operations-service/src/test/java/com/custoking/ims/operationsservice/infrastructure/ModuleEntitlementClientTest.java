package com.custoking.ims.operationsservice.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the operations -> school-core module-entitlement lookup over a real local
 * HTTP server (mirrors identity's TenantSchoolClientTest style), including the in-memory
 * TTL cache that avoids re-fetching on every firefighting request.
 */
class ModuleEntitlementClientTest {

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

    // cloud-run-auth = "never" so the metadata server is never contacted in tests.
    private ModuleEntitlementClient client(long ttlMs) {
        return new ModuleEntitlementClient(baseUrl, "tok", "never", ttlMs, 3000, 5000);
    }

    @Test
    void activeModulesReturnsUppercasedCodes() {
        server.createContext("/api/v1/schools/1/modules/active", exchange ->
                respond(exchange, 200, "[{\"moduleCode\":\"FIREFIGHTING\"}, {\"moduleCode\":\"students\"}]"));

        ModuleEntitlementClient client = client(60000);

        assertThat(client.activeModules(1L)).isEqualTo(Set.of("FIREFIGHTING", "STUDENTS"));
    }

    @Test
    void secondCallWithinTtlDoesNotRefetch() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/v1/schools/1/modules/active", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "[{\"moduleCode\":\"FIREFIGHTING\"}]");
        });

        ModuleEntitlementClient client = client(60000);

        assertThat(client.activeModules(1L)).isEqualTo(Set.of("FIREFIGHTING"));
        assertThat(client.activeModules(1L)).isEqualTo(Set.of("FIREFIGHTING"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void notConfiguredThrows() {
        ModuleEntitlementClient unconfigured = new ModuleEntitlementClient("", "", "never", 60000, 3000, 5000);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unconfigured.activeModules(1L))
                .isInstanceOf(IllegalStateException.class);
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
