package com.bobbot.service;

import com.bobbot.config.EnvConfig;
import com.bobbot.discord.BotStatus;
import com.bobbot.osrs.HiscoreClient;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
import com.bobbot.util.FormatUtils;
import net.dv8tion.jda.api.JDA;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        HealthData data = getHealthData(jda);
        StringBuilder builder = new StringBuilder();
        builder.append("BobBot health:\n");
        builder.append("- environment: ").append(data.environment()).append("\n");
        builder.append("- bot status: ").append(data.botStatus()).append("\n");
        builder.append("- discord status: ").append(data.discordStatus()).append("\n");
        builder.append("- discord ping: ").append(data.discordPing()).append("ms").append("\n");
        builder.append("- osrs status: ").append(data.osrsStatus()).append("\n");
        builder.append("- osrs ping: ").append(data.osrsPing()).append("ms").append("\n");
        builder.append("- uptime: ").append(FormatUtils.formatDuration(data.uptime())).append("\n");
        builder.append("- guilds: ").append(data.guildCount()).append("\n");
        builder.append("- linked players: ").append(data.playerCount()).append("\n");
        builder.append("- additional admins: ").append(data.adminCount()).append("\n");
        builder.append("- top xp user: ").append(data.topXpUser()).append("\n");
        builder.append("- leaderboard channel: ")
                .append(data.leaderboardChannelId() == null || data.leaderboardChannelId().isBlank() ? "not set" : data.leaderboardChannelId())
                .append("\n");
        builder.append("- bobs chat channel: ")
                .append(data.bobsChatChannelId() == null || data.bobsChatChannelId().isBlank() ? "not set" : data.bobsChatChannelId())
                .append("\n");
        builder.append("- scheduled leaderboard: ").append(data.scheduledLeaderboard()).append("\n");
        builder.append("- leaderboard interval: ").append(data.leaderboardInterval()).append("\n");
        builder.append("- poll interval: ").append(data.pollInterval()).append("\n");
        builder.append("- gateway ping: ").append(data.discordPing()).append("ms");
        return builder.toString();
    }

    /**
     * Gather all health data into a structured record.
     *
     * @param jda active JDA client
     * @return health data
     */
    public HealthData getHealthData(JDA jda) {
        BotSettings settings = storage.loadSettings();
        OsrsStatus osrsStatus = fetchOsrsStatus();
        return new HealthData(
                settings.getEnvironment(),
                BotStatus.normalize(settings.getBotStatus()),
                jda.getStatus().name().toLowerCase(Locale.ROOT),
                jda.getGatewayPing(),
                osrsStatus.status,
                osrsStatus.pingMs,
                Duration.between(startedAt, Instant.now()),
                jda.getGuilds().size(),
                storage.loadPlayers().size(),
                settings.getAdminUserIds().size(),
                resolveTopXpUser(),
                settings.getLeaderboardChannelId(),
                settings.getBobsChatChannelId(),
                leaderboardService.isScheduledLeaderboardEnabled(),
                envConfig.leaderboardInterval(),
                envConfig.pollInterval()
        );
    }

    public record HealthData(
            String environment,
            String botStatus,
            String discordStatus,
            long discordPing,
            String osrsStatus,
            long osrsPing,
            Duration uptime,
            long guildCount,
            int playerCount,
            int adminCount,
            String topXpUser,
            String leaderboardChannelId,
            String bobsChatChannelId,
            boolean scheduledLeaderboard,
            Duration leaderboardInterval,
            Duration pollInterval
    ) {
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

    /**
     * Update and persist the bot environment.
     *
     * @param environment environment name
     * @return updated environment string
     */
    public String updateEnvironment(String environment) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withEnvironment(environment));
        return environment;
    }

    /**
     * Update and persist Bob's chat channel.
     *
     * @param channelId channel ID
     * @return updated channel ID
     */
    public String updateBobsChatChannel(String channelId) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withBobsChatChannelId(channelId));
        return channelId;
    }

    /**
     * Check if a user ID is the configured superuser.
     *
     * @param userId Discord user ID
     * @return true if superuser
     */
    public boolean isSuperuser(String userId) {
        return userId != null && userId.equals(envConfig.superuserId());
    }

    /**
     * Check if a user ID is an admin (superuser or in admin list).
     *
     * @param userId Discord user ID
     * @return true if admin
     */
    public boolean isAdmin(String userId) {
        if (isSuperuser(userId)) {
            return true;
        }
        BotSettings settings = storage.loadSettings();
        return settings.getAdminUserIds().contains(userId);
    }

    /**
     * Add a user to the admin list.
     *
     * @param userId Discord user ID
     * @return true if added, false if already an admin
     */
    public boolean addAdmin(String userId) {
        BotSettings settings = storage.loadSettings();
        Set<String> admins = new HashSet<>(settings.getAdminUserIds());
        if (admins.add(userId)) {
            storage.saveSettings(settings.withAdminUserIds(admins));
            return true;
        }
        return false;
    }

    /**
     * Remove a user from the admin list.
     *
     * @param userId Discord user ID
     * @return true if removed, false if not in list
     */
    public boolean removeAdmin(String userId) {
        BotSettings settings = storage.loadSettings();
        Set<String> admins = new HashSet<>(settings.getAdminUserIds());
        if (admins.remove(userId)) {
            storage.saveSettings(settings.withAdminUserIds(admins));
            return true;
        }
        return false;
    }

    private String resolveTopXpUser() {
        Map<String, PlayerRecord> players = storage.loadPlayers();
        if (players.isEmpty()) {
            return "none";
        }
        PlayerRecord best = null;
        for (PlayerRecord record : players.values()) {
            if (best == null || record.getLastTotalXp() > best.getLastTotalXp()) {
                best = record;
            }
        }
        if (best == null) {
            return "none";
        }
        return String.format(Locale.US, "%s (%,d XP)", best.getUsername(), best.getLastTotalXp());
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

    private record OsrsStatus(String status, long pingMs) {
    }
}
