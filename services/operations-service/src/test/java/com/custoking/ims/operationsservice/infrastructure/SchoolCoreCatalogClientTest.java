package com.custoking.ims.operationsservice.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The tracker's calls to school-core use the internal catalogue routes and carry no shared token or user context. */
class SchoolCoreCatalogClientTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> headerNames = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private SchoolCoreCatalogClient clientFor(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            headerNames.addAll(exchange.getRequestHeaders().keySet());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new SchoolCoreCatalogClient("http://127.0.0.1:" + server.getAddress().getPort(), "never", 1000, 2000);
    }

    @Test
    void draftImagesAreReadFromTheInternalCatalogueRoute() throws Exception {
        var drafts = clientFor(200, "[{\"id\":5,\"contentType\":\"image/png\",\"originalFilename\":\"d.png\"}]").draftImages("CK-1");
        assertEquals(List.of("GET /api/v1/internal/catalog-orders/CK-1/draft-images"), requests);
        assertEquals(1, drafts.size());
        assertEquals(5L, drafts.get(0).id());
        assertEquals("image/png", drafts.get(0).contentType());
    }

    @Test
    void theStatusIsPutOnTheInternalCatalogueRoute() throws Exception {
        clientFor(204, "").pushStatus("CK-1", "PRINTING_PRESS", 7);
        assertEquals(List.of("PUT /api/v1/internal/catalog-orders/CK-1/tracker-status"), requests);
        assertEquals("{\"status\":\"PRINTING_PRESS\",\"trackerVersion\":7}", bodies.get(0));
    }

    @Test
    void noCallCarriesASharedTokenOrForwardedUserContext() throws Exception {
        var client = clientFor(200, "[]");
        client.draftImages("CK-1");
        client.pushStatus("CK-1", "PRINTING_PRESS", 7);
        for (String name : headerNames) {
            String lower = name.toLowerCase(Locale.ROOT);
            assertFalse(lower.endsWith("-service-token") || lower.startsWith("x-authenticated-") || lower.equals("x-ims-principal-carrier-token"), name);
        }
    }

    @Test
    void failuresAndMisconfigurationFailClosed() throws Exception {
        var client = clientFor(500, "{}");
        assertThrows(IllegalStateException.class, () -> client.draftImages("CK-1"));
        assertEquals("the order catalogue answered HTTP 500",
                assertThrows(IllegalStateException.class, () -> client.pushStatus("CK-1", "PRINTING_PRESS", 7)).getMessage());
        assertThrows(IllegalStateException.class, () -> new SchoolCoreCatalogClient("", "never", 300, 300).draftImages("CK-1"));
        assertThrows(IllegalStateException.class, () -> new SchoolCoreCatalogClient(null, null, 300, 300).pushStatus("CK-1", "PRINTING_PRESS", 7));
    }
}
