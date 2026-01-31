package com.bobbot.service;

import com.bobbot.config.EnvConfig;
import com.bobbot.discord.BotStatus;
import com.bobbot.osrs.HiscoreClient;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
import net.dv8tion.jda.api.JDA;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Service that builds a health report for the bot.
 */
public class HealthService {
    private final Instant startedAt;
    private final EnvConfig envConfig;
    private final JsonStorage storage;
    private final LeaderboardService leaderboardService;
    private final HiscoreClient hiscoreClient;

    /**
     * Create a new health service.
     *
     * @param envConfig environment configuration
     * @param storage storage layer
     * @param leaderboardService leaderboard service
     */
    public HealthService(EnvConfig envConfig, JsonStorage storage, LeaderboardService leaderboardService) {
        this.startedAt = Instant.now();
        this.envConfig = envConfig;
        this.storage = storage;
        this.leaderboardService = leaderboardService;
        this.hiscoreClient = new HiscoreClient();
    }

    /**
     * Determine if the bot is online and connected to Discord.
     *
     * @param jda active JDA client
     * @return true if online
     */
    public boolean isOnline(JDA jda) {
        return jda.getStatus() == JDA.Status.CONNECTED;
    }

    /**
     * Build a health report for the current bot instance.
     *
     * @param jda active JDA client
     * @return formatted health report
     */
    public String buildHealthReport(JDA jda) {
        Duration uptime = Duration.between(startedAt, Instant.now());
        BotSettings settings = storage.loadSettings();
        String leaderboardChannelId = settings.getLeaderboardChannelId();
        String botStatus = BotStatus.normalize(settings.getBotStatus());
        int playerCount = storage.loadPlayers().size();
        long guildCount = jda.getGuilds().size();
        long ping = jda.getGatewayPing();
        OsrsStatus osrsStatus = fetchOsrsStatus();
        String topXpUser = resolveTopXpUser();

        StringBuilder builder = new StringBuilder();
        builder.append("BobBot health:\n");
        builder.append("- bot status: ").append(botStatus).append("\n");
        builder.append("- discord status: ").append(jda.getStatus().name().toLowerCase(Locale.ROOT)).append("\n");
        builder.append("- discord ping: ").append(ping).append("ms").append("\n");
        builder.append("- osrs status: ").append(osrsStatus.status).append("\n");
        builder.append("- osrs ping: ").append(osrsStatus.pingMs).append("ms").append("\n");
        builder.append("- uptime: ").append(formatDuration(uptime)).append("\n");
        builder.append("- guilds: ").append(guildCount).append("\n");
        builder.append("- linked players: ").append(playerCount).append("\n");
        builder.append("- top xp user: ").append(topXpUser).append("\n");
        builder.append("- leaderboard channel: ")
                .append(leaderboardChannelId == null || leaderboardChannelId.isBlank() ? "not set" : leaderboardChannelId)
                .append("\n");
        builder.append("- scheduled leaderboard: ").append(leaderboardService.isScheduledLeaderboardEnabled()).append("\n");
        builder.append("- leaderboard interval: ").append(envConfig.leaderboardInterval()).append("\n");
        builder.append("- poll interval: ").append(envConfig.pollInterval()).append("\n");
        builder.append("- gateway ping: ").append(ping).append("ms");
        return builder.toString();
    }

    /**
     * Update and persist the bot presence status.
     *
     * @param jda active JDA client
     * @param status input status
     * @return normalized status string
     */
    public String updateBotStatus(JDA jda, String status) {
        String normalized = BotStatus.normalize(status);
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withBotStatus(normalized));
        jda.getPresence().setStatus(BotStatus.toOnlineStatus(normalized));
        return normalized;
    }

    private String resolveTopXpUser() {
        Map<String, PlayerRecord> players = storage.loadPlayers();
        if (players.isEmpty()) {
            return "none";
        }
        PlayerRecord best = null;
        for (PlayerRecord record : players.values()) {
            if (best == null || record.getLastTotalLevel() > best.getLastTotalLevel()) {
                best = record;
            }
        }
        if (best == null) {
            return "none";
        }
        return String.format("%s (total level %d)", best.getUsername(), best.getLastTotalLevel());
    }

    private OsrsStatus fetchOsrsStatus() {
        long start = System.nanoTime();
        try {
            int statusCode = hiscoreClient.fetchStatusCode("Zezima");
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            String status = statusCode == 200 ? "ok" : "error (" + statusCode + ")";
            return new OsrsStatus(status, elapsedMs);
        } catch (Exception e) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            return new OsrsStatus("error", elapsedMs);
        }
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, remainingSeconds);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, remainingSeconds);
        }
        return String.format("%ds", remainingSeconds);
    }

    private record OsrsStatus(String status, long pingMs) {
    }
}
