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
    void createSendsTheOrderFactsToTheInternalOrderWorkflowRoute() throws Exception {
        String base = serve(201, "{\"instanceId\":1,\"created\":true}");
        new OperationsTrackerClient(base, "never", 1000, 2000).create(tracker());
        assertEquals(1, seen.size());
        assertEquals("POST /api/v1/internal/order-workflows", seen.get(0).method() + " " + seen.get(0).uri());
        for (String expected : new String[]{"\"orderId\":\"CK-1\"", "\"schoolId\":4", "\"customised\":true", "\"orderedCount\":2000",
                "\"timeZone\":\"Asia/Kolkata\"", "\"placedBy\":7", "\"assigneeUserId\":42", "\"schoolName\":\"School A\"",
                "\"definitionId\":\"NOTEBOOK_ORDER_TRACKER\"", "\"categoryCode\":\"NOTEBOOKS\"", "\"itemCode\":\"NB\"",
                "\"itemNoun\":\"notebook\"", "\"orderNumber\":null", "\"importedProgress\":[]"}) {
            assertTrue(seen.get(0).body().contains(expected), expected + " in " + seen.get(0).body());
        }
    }

    /** Design D4: a retired product workflow's stages travel with the create, mapped onto the engine's codes. */
    @Test
    void createCarriesTheItemTheNumberAndImportedProgress() throws Exception {
        String base = serve(201, "{}");
        var imported = java.util.List.of(
                new TrackerGateway.ImportedStep("DESIGNING_ACTIVITY", "DESIGN_ACTIVITY", 2, "COMPLETED",
                        OffsetDateTime.parse("2026-11-12T10:00:00+05:30"), 99L, "Drafts shared", null),
                new TrackerGateway.ImportedStep("CDR_CONVERSION", "CDR_CONVERSION", 3, "ACTIVE", null, null, "Fonts outlined", "Waiting"));
        var flier = new TrackerGateway.NewTracker("CK-2", 4L, true, 3000, OffsetDateTime.parse("2026-11-13T09:30:00+05:30"),
                "Asia/Kolkata", 7L, null, "School A", "FLIER_ORDER_WORKFLOW", "FLIERS", "FE", "flier", "FE-SA_IN-26-11-13-0001", imported);
        new OperationsTrackerClient(base, "never", 1000, 2000).create(flier);
        String body = seen.get(0).body();
        for (String expected : new String[]{"\"definitionId\":\"FLIER_ORDER_WORKFLOW\"", "\"categoryCode\":\"FLIERS\"",
                "\"itemCode\":\"FE\"", "\"itemNoun\":\"flier\"", "\"orderNumber\":\"FE-SA_IN-26-11-13-0001\"",
                "\"stepCode\":\"DESIGNING_ACTIVITY\"", "\"sourceStageCode\":\"DESIGN_ACTIVITY\"", "\"stepOrder\":2",
                "\"completedAt\":\"2026-11-12T10:00+05:30\"", "\"completedBy\":99", "\"details\":\"Drafts shared\"",
                "\"status\":\"ACTIVE\"", "\"note\":\"Waiting\""}) {
            assertTrue(body.contains(expected), expected + " in " + body);
        }
    }

    @Test
    void imageAccessAsksWithTheUserAndRoleAndReadsTheAnswer() throws Exception {
        String base = serve(200, "{\"allowed\":false,\"reason\":\"locked\"}");
        var access = new OperationsTrackerClient(base, "never", 1000, 2000).imageAccess("CK-1", 8, "OPERATIONS");
        assertFalse(access.allowed());
        assertEquals("locked", access.reason());
        assertEquals("POST /api/v1/internal/order-workflows/CK-1/design-file-access", seen.get(0).method() + " " + seen.get(0).uri());
        assertTrue(seen.get(0).body().contains("\"userId\":8") && seen.get(0).body().contains("\"role\":\"OPERATIONS\""), seen.get(0).body());
    }

    /**
     * Design D6, deploy skew: an engine deployed before the item order workflows has only the notebook-tracker route, and its
     * MachineCallerFilter refuses the unknown internal route with a bare 403 (no body) before any controller runs. The new
     * engine never answers 403 or 404 for a registered caller (an order without a workflow is {@code allowed: false}), so
     * that answer, or a 404, means the older engine and the question is asked again on its alias, which answers alike.
     */
    @Test
    void imageAccessAsksTheNotebookTrackerAliasWhenTheEngineIsOlder() throws Exception {
        for (Object[] older : new Object[][]{{403, ""}, {404, "{\"message\":\"Not Found\"}"}}) {
            String base = serve((Integer) older[0], (String) older[1]);
            server.createContext("/api/v1/internal/notebook-trackers/", exchange -> {
                seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(), Map.copyOf(exchange.getRequestHeaders()),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                respond(exchange, 200, "{\"allowed\":true,\"reason\":null}");
            });
            var access = new OperationsTrackerClient(base, "never", 1000, 2000).imageAccess("CK-1", 8, "OPERATIONS");
            assertTrue(access.allowed(), "after " + older[0]);
            assertEquals(List.of("POST /api/v1/internal/order-workflows/CK-1/design-file-access", "POST /api/v1/internal/notebook-trackers/CK-1/image-access"),
                    seen.stream().map(request -> request.method() + " " + request.uri()).toList(), "after " + older[0]);
            assertTrue(seen.get(1).body().contains("\"userId\":8") && seen.get(1).body().contains("\"role\":\"OPERATIONS\""), seen.get(1).body());
        }
    }

    /**
     * Only the older engine's answers are retried on the alias. A 403 that says why (the engine's MACHINE_CALLER_REJECTED, or
     * Cloud Run IAM's page) is a real refusal, and like a 5xx it fails closed; so does a missing route on both.
     */
    @Test
    void imageAccessFailsClosedWithoutTheAliasOnOtherRefusals() throws Exception {
        for (Object[] refusal : new Object[][]{{403, "{\"code\":\"MACHINE_CALLER_REJECTED\",\"message\":\"Caller not authorized\"}"},
                {403, "<html><body>Your client does not have permission</body></html>"}, {403, "{}"}, {500, ""}, {503, "{}"}}) {
            var client = new OperationsTrackerClient(serve((Integer) refusal[0], (String) refusal[1]), "never", 1000, 2000);
            assertThrows(IllegalStateException.class, () -> client.imageAccess("CK-1", 8, "OPERATIONS"), "access " + refusal[0] + " " + refusal[1]);
            assertEquals(1, seen.size(), "no alias after " + refusal[0] + " " + refusal[1]);
        }
        var bothMissing = new OperationsTrackerClient(serve(404, "{}"), "never", 1000, 2000);
        assertThrows(IllegalStateException.class, () -> bothMissing.imageAccess("CK-1", 8, "OPERATIONS"));
        assertEquals(2, seen.size());
        var bothBare = new OperationsTrackerClient(serve(403, ""), "never", 1000, 2000);
        assertThrows(IllegalStateException.class, () -> bothBare.imageAccess("CK-1", 8, "OPERATIONS"), "the alias refused too");
        assertEquals(2, seen.size());
    }

    /**
     * A create is never retried on the alias: an engine that predates the item order workflows would make every item a
     * notebook workflow. The queue keeps the order and retries once the new engine is deployed.
     */
    @Test
    void createIsNotRetriedOnTheNotebookTrackerAlias() throws Exception {
        for (Object[] older : new Object[][]{{404, "{}"}, {403, ""}}) {
            var client = new OperationsTrackerClient(serve((Integer) older[0], (String) older[1]), "never", 1000, 2000);
            assertThrows(IllegalStateException.class, () -> client.create(tracker()));
            assertEquals(1, seen.size(), "after " + older[0]);
            assertEquals("POST /api/v1/internal/order-workflows", seen.get(0).method() + " " + seen.get(0).uri());
        }
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
