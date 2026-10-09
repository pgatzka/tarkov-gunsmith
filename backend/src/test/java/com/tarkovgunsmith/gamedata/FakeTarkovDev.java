package com.tarkovgunsmith.gamedata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local stand-in for json.tarkov.dev serving the recorded fixtures per mode ({@code
 * /pve/traders} falls back to the regular fixture). The ETag is a hash of the body, so a changed
 * payload gets a new one, like on the real server; a matching {@code If-None-Match} gets 304.
 */
class FakeTarkovDev implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> overrides = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    FakeTarkovDev() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Serves {@code body} at {@code path} (e.g. {@code /pve/items}) instead of the fixture. */
    void serve(String path, byte[] body) {
        overrides.put(path, body);
    }

    /** Answers requests for {@code path} with {@code status} until {@link #recover}. */
    void fail(String path, int status) {
        failures.put(path, status);
    }

    void recover(String path) {
        failures.remove(path);
    }

    void reset() {
        overrides.clear();
        failures.clear();
        requests.clear();
    }

    /** Requests received since the last {@link #reset()} or {@link #clearRequests()}. */
    List<Request> requests() {
        return List.copyOf(requests);
    }

    void clearRequests() {
        requests.clear();
    }

    /** The fixture served at {@code path}, e.g. {@code /pve/items}. */
    static byte[] fixture(String path) {
        String[] parts = path.substring(1).split("/");
        String resource = "/tarkovdev/" + parts[0] + "/" + parts[1] + ".json";
        String fallback = "/tarkovdev/regular/" + parts[1] + ".json";
        try (InputStream in = FakeTarkovDev.class.getResourceAsStream(resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (InputStream in = FakeTarkovDev.class.getResourceAsStream(fallback)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            int status = respond(exchange, path, ifNoneMatch);
            requests.add(new Request(path, ifNoneMatch, status));
        }
    }

    private int respond(HttpExchange exchange, String path, String ifNoneMatch) throws IOException {
        Integer failure = failures.get(path);
        if (failure != null) {
            exchange.sendResponseHeaders(failure, -1);
            return failure;
        }
        byte[] body = overrides.containsKey(path) ? overrides.get(path) : fixture(path);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            return 404;
        }
        String etag = etag(body);
        exchange.getResponseHeaders().set("ETag", etag);
        if (etag.equals(ifNoneMatch)) {
            exchange.sendResponseHeaders(304, -1);
            return 304;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        return 200;
    }

    private static String etag(byte[] body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
            return "W/\"" + HexFormat.of().formatHex(hash, 0, 8) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    record Request(String path, String ifNoneMatch, int status) {}
}
