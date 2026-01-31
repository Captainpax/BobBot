package com.bobbot.health;

import com.bobbot.config.EnvConfig;
import com.bobbot.service.HealthService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple HTTP server that serves the /health endpoint.
 */
public class HealthHttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthHttpServer.class);

    private final EnvConfig envConfig;
    private final HealthService healthService;
    private final AtomicReference<Optional<JDA>> jdaRef = new AtomicReference<>(Optional.empty());
    private HttpServer server;

    public HealthHttpServer(EnvConfig envConfig, HealthService healthService) {
        this.envConfig = envConfig;
        this.healthService = healthService;
    }

    /**
     * Start the HTTP server.
     *
     * @param jda active JDA instance if available
     */
    public void start(Optional<JDA> jda) {
        if (server != null) {
            return;
        }
        jdaRef.set(jda);
        try {
            server = HttpServer.create(new InetSocketAddress(envConfig.healthPort()), 0);
            server.createContext("/health", new HealthHandler(jdaRef, healthService));
            server.start();
            LOGGER.info("Health HTTP server started on port {}", envConfig.healthPort());
        } catch (IOException e) {
            LOGGER.error("Failed to start health HTTP server on port {}", envConfig.healthPort(), e);
        }
    }

    /**
     * Update the JDA reference used by the health handler.
     *
     * @param jda active JDA instance if available
     */
    public void setJda(Optional<JDA> jda) {
        jdaRef.set(jda);
    }

    /**
     * Stop the HTTP server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOGGER.info("Health HTTP server stopped");
        }
    }

    private static class HealthHandler implements HttpHandler {
        private final AtomicReference<Optional<JDA>> jdaRef;
        private final HealthService healthService;

        private HealthHandler(AtomicReference<Optional<JDA>> jdaRef, HealthService healthService) {
            this.jdaRef = jdaRef;
            this.healthService = healthService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = jdaRef.get().map(healthService::buildHealthReport)
                    .orElse("BobBot health:\n- discord status: not-ready");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(payload);
            }
        }
    }
}
