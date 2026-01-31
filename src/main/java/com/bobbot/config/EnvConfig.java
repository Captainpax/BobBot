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
 */
public record EnvConfig(
        String discordToken,
        String superuserId,
        Duration leaderboardInterval,
        Duration pollInterval,
        Path dataDirectory
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnvConfig.class);

    /**
     * Load configuration from environment variables.
     *
     * @return configured EnvConfig
     */
    public static EnvConfig load() {
        Map<String, String> env = System.getenv();
        ResolvedEnv tokenEnv = firstEnv(env, "discord-token", "discord_token", "DISCORD_TOKEN")
                .orElseThrow(() -> new IllegalStateException("discord-token, discord_token, or DISCORD_TOKEN env var is required"));
        String token = tokenEnv.value();
        String superuser = firstEnvValue(env, "discord-superuser-id", "discord_superuser_id", "DISCORD_SUPERUSER_ID")
                .orElse("");
        Duration leaderboardInterval = parseDuration(env, Duration.ofMinutes(60), "leaderboard-interval", "leaderboard_interval", "LEADERBOARD_INTERVAL");
        Duration pollInterval = parseDuration(env, Duration.ofMinutes(5), "poll-interval", "poll_interval", "POLL_INTERVAL");
        Path dataDir = Path.of(firstEnvValue(env, "data-dir", "data_dir", "DATA_DIR").orElse("data"));
        EnvConfig config = new EnvConfig(token, superuser, leaderboardInterval, pollInterval, dataDir);
        LOGGER.info(
                "Loaded env config: discord token from {}, superuser set: {}, leaderboard interval: {}, poll interval: {}, data dir: {}",
                tokenEnv.key(),
                !superuser.isBlank(),
                leaderboardInterval,
                pollInterval,
                dataDir.toAbsolutePath()
        );
        return config;
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

    private record ResolvedEnv(String key, String value) {
    }
}
