package com.bobbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Environment-backed configuration for the bot.
 *
 * @param discordToken token for the Discord bot
 * @param superuserId Discord user ID allowed to run privileged commands
 * @param leaderboardInterval interval between leaderboard posts
 * @param pollInterval interval between OSRS level checks
 * @param dataDirectory directory for JSON storage
 * @param healthPort port for the health HTTP server
 */
public record EnvConfig(
        String discordToken,
        String superuserId,
        Duration leaderboardInterval,
        Duration pollInterval,
        Path dataDirectory,
        int healthPort,
        String environment,
        String osrsApiUrl
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnvConfig.class);

    /**
     * Load configuration from environment variables.
     *
     * @return configured EnvConfig
     */
    public static EnvConfig load() {
        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
        loadEnvFile(env);
        
        Optional<ResolvedEnv> tokenEnv = firstEnv(env, "discord-token", "discord_token", "DISCORD_TOKEN");
        String token = tokenEnv.map(ResolvedEnv::value).orElse("");
        String superuser = firstEnvValue(env, "discord-superuser-id", "discord_superuser_id", "DISCORD_SUPERUSER_ID")
                .orElse("");
        Duration leaderboardInterval = parseDuration(env, Duration.ofMinutes(60), "leaderboard-interval", "leaderboard_interval", "LEADERBOARD_INTERVAL");
        Duration pollInterval = parseDuration(env, Duration.ofMinutes(5), "poll-interval", "poll_interval", "POLL_INTERVAL");
        Path dataDir = Path.of(firstEnvValue(env, "data-dir", "data_dir", "DATA_DIR").orElse("data"));
        int healthPort = parsePort(env, 8080, "health-port", "health_port", "HEALTH_PORT", "PORT");
        String environment = detectEnvironment(env);
        String osrsApiUrl = firstEnvValue(env, "osrs-api-url", "osrs_api_url", "OSRS_API_URL").orElse("http://localhost:3000");
        EnvConfig config = new EnvConfig(token, superuser, leaderboardInterval, pollInterval, dataDir, healthPort, environment, osrsApiUrl);
        if (!config.hasDiscordToken()) {
            LOGGER.error("Discord token missing. Set discord-token, discord_token, or DISCORD_TOKEN to start the bot.");
        }
        LOGGER.info(
                "Loaded env config: discord token from {}, superuser set: {}, leaderboard interval: {}, poll interval: {}, data dir: {}, health port: {}, environment: {}, osrs api url: {}",
                tokenEnv.map(ResolvedEnv::key).orElse("missing"),
                !superuser.isBlank(),
                leaderboardInterval,
                pollInterval,
                dataDir.toAbsolutePath(),
                healthPort,
                environment.isBlank() ? "not set" : environment,
                osrsApiUrl
        );
        return config;
    }

    public boolean hasDiscordToken() {
        return discordToken != null && !discordToken.isBlank();
    }

    /**
     * Resolve the first non-blank environment variable from a primary or fallback key.
     *
     * @param env environment map
     * @param keys possible keys in order of preference
     * @return optional value
     */
    private static Optional<ResolvedEnv> firstEnv(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return Optional.of(new ResolvedEnv(key, value));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstEnvValue(Map<String, String> env, String... keys) {
        return firstEnv(env, keys).map(ResolvedEnv::value);
    }

    private static void loadEnvFile(Map<String, String> env) {
        Path path = Path.of(".env");
        if (java.nio.file.Files.exists(path)) {
            try {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(path);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int sep = line.indexOf('=');
                    if (sep > 0) {
                        String key = line.substring(0, sep).trim();
                        String value = line.substring(sep + 1).trim();
                        // Only set if not already present in environment
                        env.putIfAbsent(key, value);
                        // Also try lowercase variants for internal compatibility
                        env.putIfAbsent(key.toLowerCase(Locale.ROOT).replace("_", "-"), value);
                    }
                }
            } catch (java.io.IOException e) {
                LOGGER.warn("Failed to read .env file", e);
            }
        }
    }

    private static String detectEnvironment(Map<String, String> env) {
        String explicit = firstEnvValue(env, "ENVIRONMENT", "environment").orElse("");
        if (!explicit.isBlank()) {
            return explicit;
        }

        // Check for Docker
        if (java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv")) || env.containsKey("DOCKER_CONTAINER")) {
            return "Docker";
        }

        // Check for Kubernetes
        if (env.containsKey("KUBERNETES_SERVICE_HOST")) {
            return "Kubernetes";
        }

        // Check for IntelliJ
        if (env.containsKey("INTELLIJ_IDEA_RUN_CONF") || env.containsKey("IDE_PROJECT_ROOT")) {
            return "IntelliJ";
        }

        // Default to Local if running on personal machine (heuristic)
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win") || os.contains("mac")) {
            return "Local";
        }

        return "";
    }

    /**
     * Parse a duration from env variables, supporting seconds or s/m/h suffixes.
     *
     * @param env environment map
     * @param keys env keys to try
     * @param defaultValue default duration when absent or invalid
     * @return parsed duration
     */
    private static Duration parseDuration(Map<String, String> env, Duration defaultValue, String... keys) {
        String value = firstEnvValue(env, keys).orElse("");
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            if (value.matches("\\d+")) {
                return Duration.ofSeconds(Long.parseLong(value));
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            if (normalized.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            if (normalized.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
            }
            return Duration.parse(value);
        } catch (Exception e) {
            LOGGER.warn("Invalid duration for {} ({}). Using default {}", String.join("/", keys), value, defaultValue, e);
            return defaultValue;
        }
    }

    private static int parsePort(Map<String, String> env, int defaultValue, String... keys) {
        String value = firstEnvValue(env, keys).orElse("");
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            return port;
        } catch (Exception e) {
            LOGGER.warn("Invalid port for {} ({}). Using default {}", String.join("/", keys), value, defaultValue, e);
            return defaultValue;
        }
    }

    private record ResolvedEnv(String key, String value) {
    }
}
