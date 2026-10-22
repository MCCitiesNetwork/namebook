package com.minecraftcitiesnetwork.namebook.core.remote;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A local HTTP server that answers every request with one fixed response and records what it was asked. */
final class StubServer implements AutoCloseable {

    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMillis;

    StubServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            this.paths.add(exchange.getRequestURI().getRawPath());
            this.userAgents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
            try {
                if (this.delayMillis > 0) {
                    Thread.sleep(this.delayMillis);
                }
                byte[] bytes = this.body.getBytes(StandardCharsets.UTF_8);
                if (this.status == 204) {
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    exchange.sendResponseHeaders(this.status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } catch (InterruptedException | IOException ignored) {
                // The client gave up (timeout test); nothing to answer.
            } finally {
                exchange.close();
            }
        });
        this.server.start();
    }

    StubServer reply(int status, String body) {
        this.status = status;
        this.body = body;
        return this;
    }

    StubServer delay(long millis) {
        this.delayMillis = millis;
        return this;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
