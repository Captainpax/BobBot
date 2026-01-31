package com.bobbot.config;

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
    /**
     * Load configuration from environment variables.
     *
     * @return configured EnvConfig
     */
    public static EnvConfig load() {
        Map<String, String> env = System.getenv();
        String token = firstEnv(env, "discord-token", "DISCORD_TOKEN")
                .orElseThrow(() -> new IllegalStateException("discord-token (or DISCORD_TOKEN) env var is required"));
        String superuser = firstEnv(env, "discord-superuser-id", "DISCORD_SUPERUSER_ID")
                .orElse("");
        Duration leaderboardInterval = parseDuration(env, "leaderboard-interval", "LEADERBOARD_INTERVAL", Duration.ofMinutes(60));
        Duration pollInterval = parseDuration(env, "poll-interval", "POLL_INTERVAL", Duration.ofMinutes(5));
        Path dataDir = Path.of(firstEnv(env, "data-dir", "DATA_DIR").orElse("data"));
        return new EnvConfig(token, superuser, leaderboardInterval, pollInterval, dataDir);
    }

    /**
     * Resolve the first non-blank environment variable from a primary or fallback key.
     *
     * @param env environment map
     * @param primary primary key (dashed)
     * @param fallback fallback key (uppercase)
     * @return optional value
     */
    private static Optional<String> firstEnv(Map<String, String> env, String primary, String fallback) {
        String value = env.get(primary);
        if (value == null || value.isBlank()) {
            value = env.get(fallback);
        }
        return Optional.ofNullable(value).filter(v -> !v.isBlank());
    }

    /**
     * Parse a duration from env variables, supporting seconds or s/m/h suffixes.
     *
     * @param env environment map
     * @param primary primary key
     * @param fallback fallback key
     * @param defaultValue default duration when absent or invalid
     * @return parsed duration
     */
    private static Duration parseDuration(Map<String, String> env, String primary, String fallback, Duration defaultValue) {
        String value = firstEnv(env, primary, fallback).orElse("");
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
            return defaultValue;
        }
    }
}
