package dev.synapse.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A local HTTP server the billing providers can be pointed at
 * ({@code synapse.provider-api-base.<name>}), so their request shape and
 * response mapping are exercised without touching the internet.
 */
public final class StubProviderServer implements AutoCloseable {

    /** One captured request: path, headers, decoded body. */
    public record Call(String method, String path, Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, String> responses = new LinkedHashMap<>();
    private int status = 200;

    public StubProviderServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Reply to {@code METHOD /path} (path only, no query) with this JSON. */
    public StubProviderServer on(String method, String path, String json) {
        responses.put(method + " " + path, json);
        return this;
    }

    public StubProviderServer failWith(int httpStatus, String json) {
        this.status = httpStatus;
        responses.put("*", json);
        return this;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call lastCall() {
        return calls.get(calls.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0)));
        calls.add(new Call(exchange.getRequestMethod(), path, headers, body));

        String json = responses.getOrDefault(exchange.getRequestMethod() + " " + path, responses.getOrDefault("*", "{}"));
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
