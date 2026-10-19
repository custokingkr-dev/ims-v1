package com.custoking.ims.operationsservice.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The operator-directory client asks identity the right question and fails closed on anything but a clear "yes". */
class OperatorDirectoryClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private OperatorDirectoryClient clientFor(int status, String body, AtomicReference<String> seenRequest, AtomicReference<String> seenToken) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/rbac/operator-directory/eligible", exchange -> {
            if (seenRequest != null) seenRequest.set(exchange.getRequestURI().toString());
            if (seenToken != null) seenToken.set(exchange.getRequestHeaders().getFirst("X-Operator-Directory-Service-Token"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new OperatorDirectoryClient("http://127.0.0.1:" + server.getAddress().getPort(), "directory-token", "never", 1000, 2000);
    }

    @Test
    void itAsksWithTheMachineTokenAndTheUserSchoolAndCatalogItem() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        OperatorDirectoryClient client = clientFor(200, "{\"eligible\":true}", request, token);

        assertTrue(client.eligible(7, 4, "NOTEBOOKS"));
        assertEquals("directory-token", token.get());
        assertTrue(request.get().contains("userId=7"), request.get());
        assertTrue(request.get().contains("schoolId=4"), request.get());
        assertTrue(request.get().contains("catalogCategory=NOTEBOOKS"), request.get());
    }

    @Test
    void aNoFromIdentityIsNo() throws Exception {
        assertFalse(clientFor(200, "{\"eligible\":false}", null, null).eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void anythingOtherThanAnExplicitTrueIsNo() throws Exception {
        assertFalse(clientFor(200, "{}", null, null).eligible(7, 4, "NOTEBOOKS"));
        stop();
        assertFalse(clientFor(200, "{\"eligible\":\"yes\"}", null, null).eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void errorStatusesAndGarbageFailClosedAsUnavailableNeverAsEligible() throws Exception {
        for (int status : new int[]{401, 403, 404, 500, 503}) {
            stop();
            OperatorDirectoryClient client = clientFor(status, "{\"eligible\":true}", null, null);
            assertThrows(IllegalStateException.class, () -> client.eligible(7, 4, "NOTEBOOKS"), "status " + status);
        }
        stop();
        OperatorDirectoryClient garbage = clientFor(200, "not json", null, null);
        assertThrows(IllegalStateException.class, () -> garbage.eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void anUnreachablePeerFailsClosed() {
        OperatorDirectoryClient client = new OperatorDirectoryClient("http://127.0.0.1:9", "directory-token", "never", 300, 300);
        assertThrows(IllegalStateException.class, () -> client.eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void missingConfigurationRefusesInsteadOfGuessing() {
        assertThrows(IllegalStateException.class, () -> new OperatorDirectoryClient("", "directory-token", "never", 300, 300).eligible(7, 4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new OperatorDirectoryClient("http://127.0.0.1:9", "", "never", 300, 300).eligible(7, 4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new OperatorDirectoryClient(null, null, null, 300, 300).eligible(7, 4, "NOTEBOOKS"));
    }
}
