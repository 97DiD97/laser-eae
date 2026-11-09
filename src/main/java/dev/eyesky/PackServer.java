package dev.eyesky;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Мини-веб-сервер, который раздаёт ресурспак, лежащий внутри jar плагина. */
public final class PackServer {
    private final HttpServer server;
    private final ExecutorService executor;

    public PackServer(String bindAddress, int port, byte[] packBytes) throws IOException {
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "FloatingEye-PackServer");
            t.setDaemon(true);
            return t;
        });
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.server.createContext("/pack.zip", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, packBytes.length);
                exchange.getResponseBody().write(packBytes);
            }
        });
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }
}
