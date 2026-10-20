package com.custoking.ims.operationsservice.infrastructure;

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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The operator-directory client asks identity's internal route with only a Cloud Run identity token, and fails closed
 * on anything but a clear "yes".
 */
class OperatorDirectoryClientTest {
    private static final String ROUTE = "/api/v1/internal/operator-directory/eligible";
    private static final String METADATA_PATH = "/computeMetadata/v1/instance/service-accounts/default/identity";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
    private final List<String> metadataRequests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** One local server plays identity ({@link #ROUTE}) and the metadata server ({@link #METADATA_PATH}). */
    private String serve(int status, String body) throws IOException {
        if (server != null) server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(METADATA_PATH, exchange -> {
            metadataRequests.add(exchange.getRequestURI() + " flavor=" + exchange.getRequestHeaders().getFirst("Metadata-Flavor"));
            respond(exchange, 200, "signed-identity-token-" + metadataRequests.size());
        });
        server.createContext(ROUTE, exchange -> {
            requests.add(exchange.getRequestURI().toString());
            headers.add(Map.copyOf(exchange.getRequestHeaders()));
            respond(exchange, status, body);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private OperatorDirectoryClient clientFor(int status, String body) throws IOException {
        return new OperatorDirectoryClient(serve(status, body), "never", 1000, 2000);
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().get(0)).findFirst().orElse(null);
    }

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T06:00:00Z");
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void itAsksTheInternalRouteAboutTheUserSchoolAndCatalogItem() throws Exception {
        assertTrue(clientFor(200, "{\"eligible\":true}").eligible(7, 4, "NOTEBOOKS"));
        assertTrue(requests.get(0).startsWith(ROUTE + "?"), requests.get(0));
        assertTrue(requests.get(0).contains("userId=7"), requests.get(0));
        assertTrue(requests.get(0).contains("schoolId=4"), requests.get(0));
        assertTrue(requests.get(0).contains("catalogCategory=NOTEBOOKS"), requests.get(0));
    }

    @Test
    void aNoFromIdentityIsNo() throws Exception {
        assertFalse(clientFor(200, "{\"eligible\":false}").eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void anythingOtherThanAnExplicitTrueIsNo() throws Exception {
        assertFalse(clientFor(200, "{}").eligible(7, 4, "NOTEBOOKS"));
        assertFalse(clientFor(200, "{\"eligible\":\"yes\"}").eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void errorStatusesAndGarbageFailClosedAsUnavailableNeverAsEligible() throws Exception {
        for (int status : new int[]{401, 403, 404, 500, 503}) {
            OperatorDirectoryClient client = clientFor(status, "{\"eligible\":true}");
            assertThrows(IllegalStateException.class, () -> client.eligible(7, 4, "NOTEBOOKS"), "status " + status);
        }
        OperatorDirectoryClient garbage = clientFor(200, "not json");
        assertThrows(IllegalStateException.class, () -> garbage.eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void anUnreachablePeerFailsClosed() {
        OperatorDirectoryClient client = new OperatorDirectoryClient("http://127.0.0.1:9", "never", 300, 300);
        assertThrows(IllegalStateException.class, () -> client.eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void missingConfigurationRefusesInsteadOfGuessing() {
        assertThrows(IllegalStateException.class, () -> new OperatorDirectoryClient("", "never", 300, 300).eligible(7, 4, "NOTEBOOKS"));
        assertThrows(IllegalStateException.class, () -> new OperatorDirectoryClient(null, null, 300, 300).eligible(7, 4, "NOTEBOOKS"));
    }

    @Test
    void itSendsOnlyAFullFormatIdentityTokenForIdentityReusedFor45Minutes() throws Exception {
        String base = serve(200, "{\"eligible\":true}");
        TestClock clock = new TestClock();
        var client = new OperatorDirectoryClient(new PeerHttp(base, "always", 1000, 2000, base + METADATA_PATH, clock));

        assertTrue(client.eligible(7, 4, "NOTEBOOKS"));
        clock.advance(Duration.ofMinutes(44));
        assertTrue(client.eligible(8, 4, "NOTEBOOKS"));
        assertEquals(1, metadataRequests.size(), "two calls within 45 minutes fetch the token once");
        assertTrue(metadataRequests.get(0).contains("audience=" + URLEncoder.encode(base, StandardCharsets.UTF_8)), metadataRequests.get(0));
        assertTrue(metadataRequests.get(0).contains("&format=full"), metadataRequests.get(0));
        assertTrue(metadataRequests.get(0).endsWith(" flavor=Google"), metadataRequests.get(0));
        for (Map<String, List<String>> sent : headers) {
            assertEquals("Bearer signed-identity-token-1", header(sent, "Authorization"));
            for (String name : sent.keySet()) {
                String lower = name.toLowerCase(Locale.ROOT);
                assertFalse(lower.endsWith("-service-token") || lower.startsWith("x-authenticated-") || lower.equals("x-ims-principal-carrier-token"), name);
            }
        }

        clock.advance(Duration.ofMinutes(2));
        client.eligible(9, 4, "NOTEBOOKS");
        assertEquals(2, metadataRequests.size(), "a token older than 45 minutes is fetched again");
        assertEquals("Bearer signed-identity-token-2", header(headers.get(2), "Authorization"));
    }
}
