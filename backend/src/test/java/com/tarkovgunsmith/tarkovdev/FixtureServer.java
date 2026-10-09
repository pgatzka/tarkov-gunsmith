package com.tarkovgunsmith.tarkovdev;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local stand-in for json.tarkov.dev serving the recorded fixtures under {@code
 * tarkovdev/regular/} for any mode. Mimics the ETag / 304 behaviour of the real server.
 */
class FixtureServer implements AutoCloseable {

    static final String ETAG = "W/\"fixture-etag\"";

    private final HttpServer server;
    private final List<Headers> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();

    /** Serve {@code <endpoint>.json.<suffix>} with this Content-Encoding instead of plain JSON. */
    volatile String contentEncoding;

    volatile int forcedStatus;

    FixtureServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<Headers> requests() {
        return requests;
    }

    List<String> paths() {
        return paths;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestHeaders());
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        try (exchange) {
            if (forcedStatus != 0) {
                exchange.sendResponseHeaders(forcedStatus, -1);
                return;
            }
            String endpoint = path.substring(path.lastIndexOf('/') + 1);
            String resource = "/tarkovdev/regular/" + endpoint + ".json" + suffix();
            try (InputStream fixture = getClass().getResourceAsStream(resource)) {
                if (fixture == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("ETag", ETAG);
                if (ETAG.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    exchange.sendResponseHeaders(304, -1);
                    return;
                }
                byte[] body = fixture.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (contentEncoding != null) {
                    exchange.getResponseHeaders().set("Content-Encoding", contentEncoding);
                }
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    private String suffix() {
        if (contentEncoding == null) {
            return "";
        }
        return switch (contentEncoding) {
            case "br" -> ".br";
            case "gzip" -> ".gz";
            default -> "";
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
