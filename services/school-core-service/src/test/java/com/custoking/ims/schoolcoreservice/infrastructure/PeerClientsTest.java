package com.custoking.ims.schoolcoreservice.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tracker and operator-directory clients call the peers' internal routes with only a Cloud Run identity token (no
 * shared token, no user context) and fail closed on every kind of failure.
 */
class PeerClientsTest {
    private static final String METADATA_PATH = "/computeMetadata/v1/instance/service-accounts/default/identity";

    private record Seen(String method, String uri, Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<String> metadataRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger metadataStatus = new AtomicInteger(200);

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    /** One local server plays both the peer (every path) and the metadata server ({@link #METADATA_PATH}). */
    private String serve(int status, String body) throws IOException {
        if (server != null) server.stop(0);
        seen.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(METADATA_PATH, exchange -> {
            metadataRequests.add(exchange.getRequestURI() + " flavor=" + exchange.getRequestHeaders().getFirst("Metadata-Flavor"));
            respond(exchange, metadataStatus.get(), "signed-identity-token-" + metadataRequests.size());
        });
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, status, body);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static TrackerGateway.NewTracker tracker() {
        return new TrackerGateway.NewTracker("CK-1", 4L, true, 2000, OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 7L, 42L, "School A");
    }

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T06:00:00Z");
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    // ---- tracker -------------------------------------------------------------------------------------------------------

    @Test
    void createSendsTheOrderFactsToTheInternalTrackerRoute() throws Exception {
        String base = serve(201, "{\"instanceId\":1,\"created\":true}");
        new OperationsTrackerClient(base, "never", 1000, 2000).create(tracker());
        assertEquals(1, seen.size());
        assertEquals("POST /api/v1/internal/notebook-trackers", seen.get(0).method() + " " + seen.get(0).uri());
        for (String expected : new String[]{"\"orderId\":\"CK-1\"", "\"schoolId\":4", "\"customised\":true", "\"orderedCount\":2000",
                "\"timeZone\":\"Asia/Kolkata\"", "\"placedBy\":7", "\"assigneeUserId\":42", "\"schoolName\":\"School A\""}) {
            assertTrue(seen.get(0).body().contains(expected), expected + " in " + seen.get(0).body());
        }
    }

    @Test
    void imageAccessAsksWithTheUserAndRoleAndReadsTheAnswer() throws Exception {
        String base = serve(200, "{\"allowed\":false,\"reason\":\"locked\"}");
        var access = new OperationsTrackerClient(base, "never", 1000, 2000).imageAccess("CK-1", 8, "OPERATIONS");
        assertFalse(access.allowed());
        assertEquals("locked", access.reason());
        assertEquals("POST /api/v1/internal/notebook-trackers/CK-1/image-access", seen.get(0).method() + " " + seen.get(0).uri());
        assertTrue(seen.get(0).body().contains("\"userId\":8") && seen.get(0).body().contains("\"role\":\"OPERATIONS\""), seen.get(0).body());
    }

    @Test
    void imageAccessIsAllowedOnlyOnAnExplicitTrue() throws Exception {
        assertTrue(new OperationsTrackerClient(serve(200, "{\"allowed\":true}"), "never", 1000, 2000).imageAccess("CK-1", 8, "SUPERADMIN").allowed());
        assertFalse(new OperationsTrackerClient(serve(200, "{}"), "never", 1000, 2000).imageAccess("CK-1", 8, "SUPERADMIN").allowed());
    }

    @Test
    void trackerErrorsAndMisconfigurationFailClosed() throws Exception {
        for (int status : new int[]{401, 403, 404, 500, 503}) {
            var client = new OperationsTrackerClient(serve(status, "{}"), "never", 1000, 2000);
            assertThrows(IllegalStateException.class, () -> client.create(tracker()), "create " + status);
        }
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient("http://127.0.0.1:9", "never", 300, 300).create(tracker()));
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient("http://127.0.0.1:9", "never", 300, 300).imageAccess("CK-1", 1, "OPERATIONS"));
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient("", "never", 300, 300).create(tracker()));
        assertThrows(IllegalStateException.class, () -> new OperationsTrackerClient(null, null, 300, 300).imageAccess("CK-1", 1, "OPERATIONS"));
    }

    // ---- operator directory ----------------------------------------------------------------------------------------------

    @Test
    void resolveAsksTheInternalDirectoryRouteForTheSchoolAndItem() throws Exception {
        String base = serve(200, "{\"outcome\":\"ITEM_SPECIFIC\",\"userId\":42,\"candidates\":[42]}");
        var resolution = new IdentityOperatorClient(base, "never", 1000, 2000).resolve(4, "NOTEBOOKS");
        assertEquals("ITEM_SPECIFIC", resolution.outcome());
        assertEquals(42L, resolution.userId());
        assertEquals("GET", seen.get(0).method());
        assertTrue(seen.get(0).uri().startsWith("/api/v1/internal/operator-directory/resolve?"), seen.get(0).uri());
        assertTrue(seen.get(0).uri().contains("schoolId=4") && seen.get(0).uri().contains("catalogCategory=NOTEBOOKS"), seen.get(0).uri());
    }

    @Test
    void anAmbiguousOutcomeHasNoUser() throws Exception {
        var resolution = new IdentityOperatorClient(serve(200, "{\"outcome\":\"AMBIGUOUS\",\"userId\":null,\"candidates\":[1,2]}"), "never", 1000, 2000)
                .resolve(4, "NOTEBOOKS");
        assertEquals("AMBIGUOUS", resolution.outcome());
        assertNull(resolution.userId());
    }

    @Test
    void directoryErrorsGarbageAndMisconfigurationFailClosed() throws Exception {
        for (int status : new int[]{401, 403, 500}) {
            var client = new IdentityOperatorClient(serve(status, "{\"outcome\":\"ITEM_SPECIFIC\",\"userId\":1}"), "never", 1000, 2000);
            assertThrows(IllegalStateException.class, () -> client.resolve(4, "NOTEBOOKS"), "status " + status);
        }
        var noOutcome = new IdentityOperatorClient(serve(200, "{}"), "never", 1000, 2000);
        assertThrows(IllegalStateException.class, () -> noOutcome.resolve(4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new IdentityOperatorClient("http://127.0.0.1:9", "never", 300, 300).resolve(4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new IdentityOperatorClient("", "never", 300, 300).resolve(4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new IdentityOperatorClient(null, null, 300, 300).resolve(4, "NOTEBOOKS"));
    }

    // ---- authentication --------------------------------------------------------------------------------------------------

    @Test
    void noPeerCallCarriesASharedTokenOrForwardedUserContext() throws Exception {
        String base = serve(200, "{\"outcome\":\"NONE\",\"allowed\":true}");
        var http = new PeerHttp(base, "always", 1000, 2000, base + METADATA_PATH, new TestClock());
        new OperationsTrackerClient(http).create(tracker());
        new OperationsTrackerClient(http).imageAccess("CK-1", 8, "OPERATIONS");
        new IdentityOperatorClient(http).resolve(4, "NOTEBOOKS");
        assertEquals(3, seen.size());
        for (Seen request : seen) {
            for (String name : request.headers().keySet()) {
                String lower = name.toLowerCase(Locale.ROOT);
                assertFalse(lower.endsWith("-service-token") || lower.startsWith("x-authenticated-") || lower.equals("x-ims-principal-carrier-token"),
                        name + " sent on " + request.uri());
            }
            assertEquals("Bearer signed-identity-token-1", request.header("Authorization"), request.uri());
        }
    }

    @Test
    void theIdentityTokenIsFullFormatForThePeerAndReusedFor45Minutes() throws Exception {
        String base = serve(201, "{}");
        TestClock clock = new TestClock();
        var client = new OperationsTrackerClient(new PeerHttp(base, "always", 1000, 2000, base + METADATA_PATH, clock));

        client.create(tracker());
        clock.advance(Duration.ofMinutes(44));
        client.create(tracker());
        assertEquals(1, metadataRequests.size(), "two calls within 45 minutes fetch the token once");
        String fetch = metadataRequests.get(0);
        assertTrue(fetch.contains("audience=" + URLEncoder.encode(base, StandardCharsets.UTF_8)), fetch);
        assertTrue(fetch.contains("&format=full"), fetch);
        assertTrue(fetch.endsWith(" flavor=Google"), fetch);
        assertEquals("Bearer signed-identity-token-1", seen.get(0).header("Authorization"));
        assertEquals("Bearer signed-identity-token-1", seen.get(1).header("Authorization"));

        clock.advance(Duration.ofMinutes(2));
        client.create(tracker());
        assertEquals(2, metadataRequests.size(), "a token older than 45 minutes is fetched again");
        assertEquals("Bearer signed-identity-token-2", seen.get(2).header("Authorization"));
    }

    @Test
    void aFailedTokenFetchSendsNoAuthorizationAndIsNotCached() throws Exception {
        String base = serve(201, "{}");
        var client = new OperationsTrackerClient(new PeerHttp(base, "always", 1000, 2000, base + METADATA_PATH, new TestClock()));
        metadataStatus.set(500);
        client.create(tracker());
        assertNull(seen.get(0).header("Authorization"));
        metadataStatus.set(200);
        client.create(tracker());
        assertEquals(2, metadataRequests.size());
        assertEquals("Bearer signed-identity-token-2", seen.get(1).header("Authorization"));
    }

    @Test
    void autoModeFetchesATokenOnlyForCloudRunPeers() throws Exception {
        String base = serve(201, "{}");
        new OperationsTrackerClient(new PeerHttp(base, "auto", 1000, 2000, base + METADATA_PATH, new TestClock())).create(tracker());
        new OperationsTrackerClient(new PeerHttp(base, "never", 1000, 2000, base + METADATA_PATH, new TestClock())).create(tracker());
        assertTrue(metadataRequests.isEmpty());
        assertNull(seen.get(0).header("Authorization"));
        assertNull(seen.get(1).header("Authorization"));
    }
}
