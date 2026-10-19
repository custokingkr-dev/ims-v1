package com.custoking.ims.schoolcoreservice.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The tracker and operator-directory clients send the machine token and fail closed on every kind of failure. */
class PeerClientsTest {
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private String serve(String path, int status, String body, AtomicReference<String> request, AtomicReference<String> token,
                         String tokenHeader, AtomicReference<String> payload) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            if (request != null) request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            if (token != null) token.set(exchange.getRequestHeaders().getFirst(tokenHeader));
            if (payload != null) payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static TrackerGateway.NewTracker tracker() {
        return new TrackerGateway.NewTracker("CK-1", 4L, true, 2000, OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 7L, 42L, "School A");
    }

    // ---- tracker -------------------------------------------------------------------------------------------------------

    @Test
    void createSendsTheOrderFactsWithTheMachineToken() throws Exception {
        AtomicReference<String> request = new AtomicReference<>(), token = new AtomicReference<>(), payload = new AtomicReference<>();
        String base = serve("/api/v1/workflows/notebook-orders", 201, "{\"instanceId\":1,\"created\":true}", request, token,
                "X-Notebook-Tracker-Service-Token", payload);
        new OperationsTrackerClient(base, "machine-token", "never", 1000, 2000).create(tracker());
        assertEquals("POST /api/v1/workflows/notebook-orders", request.get());
        assertEquals("machine-token", token.get());
        for (String expected : new String[]{"\"orderId\":\"CK-1\"", "\"schoolId\":4", "\"customised\":true", "\"orderedCount\":2000",
                "\"timeZone\":\"Asia/Kolkata\"", "\"placedBy\":7", "\"assigneeUserId\":42", "\"schoolName\":\"School A\""}) {
            assertTrue(payload.get().contains(expected), expected + " in " + payload.get());
        }
    }

    @Test
    void imageAccessAsksWithTheUserAndRoleAndReadsTheAnswer() throws Exception {
        AtomicReference<String> request = new AtomicReference<>(), payload = new AtomicReference<>();
        String base = serve("/api/v1/workflows/notebook-orders/CK-1/design/image-access", 200,
                "{\"allowed\":false,\"reason\":\"locked\"}", request, null, null, payload);
        var access = new OperationsTrackerClient(base, "machine-token", "never", 1000, 2000).imageAccess("CK-1", 8, "OPERATIONS");
        assertFalse(access.allowed());
        assertEquals("locked", access.reason());
        assertEquals("POST /api/v1/workflows/notebook-orders/CK-1/design/image-access", request.get());
        assertTrue(payload.get().contains("\"userId\":8") && payload.get().contains("\"role\":\"OPERATIONS\""), payload.get());
    }

    @Test
    void imageAccessIsAllowedOnlyOnAnExplicitTrue() throws Exception {
        String path = "/api/v1/workflows/notebook-orders/CK-1/design/image-access";
        assertTrue(new OperationsTrackerClient(serve(path, 200, "{\"allowed\":true}", null, null, null, null),
                "machine-token", "never", 1000, 2000).imageAccess("CK-1", 8, "SUPERADMIN").allowed());
        stop();
        assertFalse(new OperationsTrackerClient(serve(path, 200, "{}", null, null, null, null),
                "machine-token", "never", 1000, 2000).imageAccess("CK-1", 8, "SUPERADMIN").allowed());
    }

    @Test
    void trackerErrorsAndMisconfigurationFailClosed() throws Exception {
        for (int status : new int[]{401, 403, 404, 500, 503}) {
            stop();
            var client = new OperationsTrackerClient(serve("/api/v1/workflows/notebook-orders", status, "{}", null, null, null, null),
                    "machine-token", "never", 1000, 2000);
            assertThrows(IllegalStateException.class, () -> client.create(tracker()), "create " + status);
        }
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient("http://127.0.0.1:9", "machine-token", "never", 300, 300).create(tracker()));
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient("http://127.0.0.1:9", "machine-token", "never", 300, 300).imageAccess("CK-1", 1, "OPERATIONS"));
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient("", "machine-token", "never", 300, 300).create(tracker()));
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient("http://127.0.0.1:9", "", "never", 300, 300).imageAccess("CK-1", 1, "OPERATIONS"));
    }

    // ---- operator directory ----------------------------------------------------------------------------------------------

    @Test
    void resolveAsksForTheSchoolAndItemAndReadsTheOutcome() throws Exception {
        AtomicReference<String> request = new AtomicReference<>(), token = new AtomicReference<>();
        String base = serve("/api/v1/rbac/operator-directory/resolve", 200, "{\"outcome\":\"ITEM_SPECIFIC\",\"userId\":42,\"candidates\":[42]}",
                request, token, "X-Operator-Directory-Service-Token", null);
        var resolution = new IdentityOperatorClient(base, "directory-token", "never", 1000, 2000).resolve(4, "NOTEBOOKS");
        assertEquals("ITEM_SPECIFIC", resolution.outcome());
        assertEquals(42L, resolution.userId());
        assertEquals("directory-token", token.get());
        assertTrue(request.get().contains("schoolId=4") && request.get().contains("catalogCategory=NOTEBOOKS"), request.get());
    }

    @Test
    void anAmbiguousOutcomeHasNoUser() throws Exception {
        String base = serve("/api/v1/rbac/operator-directory/resolve", 200, "{\"outcome\":\"AMBIGUOUS\",\"userId\":null,\"candidates\":[1,2]}",
                null, null, null, null);
        var resolution = new IdentityOperatorClient(base, "directory-token", "never", 1000, 2000).resolve(4, "NOTEBOOKS");
        assertEquals("AMBIGUOUS", resolution.outcome());
        assertNull(resolution.userId());
    }

    @Test
    void directoryErrorsGarbageAndMisconfigurationFailClosed() throws Exception {
        String path = "/api/v1/rbac/operator-directory/resolve";
        for (int status : new int[]{401, 403, 500}) {
            stop();
            var client = new IdentityOperatorClient(serve(path, status, "{\"outcome\":\"ITEM_SPECIFIC\",\"userId\":1}", null, null, null, null),
                    "directory-token", "never", 1000, 2000);
            assertThrows(IllegalStateException.class, () -> client.resolve(4, "NOTEBOOKS"), "status " + status);
        }
        stop();
        var noOutcome = new IdentityOperatorClient(serve(path, 200, "{}", null, null, null, null), "directory-token", "never", 1000, 2000);
        assertThrows(IllegalStateException.class, () -> noOutcome.resolve(4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new IdentityOperatorClient("http://127.0.0.1:9", "directory-token", "never", 300, 300).resolve(4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new IdentityOperatorClient("", "directory-token", "never", 300, 300).resolve(4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new IdentityOperatorClient("http://127.0.0.1:9", "", "never", 300, 300).resolve(4, "NOTEBOOKS"));
    }
}
