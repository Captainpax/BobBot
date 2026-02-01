package com.bobbot.service;

import com.bobbot.config.EnvConfig;
import com.bobbot.discord.BotStatus;
import com.bobbot.discord.DiscordFormatUtils;
import com.bobbot.osrs.HiscoreClient;
import com.bobbot.osrs.OsrsApiClient;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
import com.bobbot.util.FormatUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Service that builds a health report for the bot.
 */
public class HealthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthService.class);
    private final Instant startedAt;
    private final EnvConfig envConfig;
    private final JsonStorage storage;
    private final LeaderboardService leaderboardService;
    private final HiscoreClient hiscoreClient;
    private final OsrsApiClient apiClient;
    private final Map<String, AiExecutionLog> thoughtCache = Collections.synchronizedMap(new LinkedHashMap<String, AiExecutionLog>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AiExecutionLog> eldest) {
            return size() > 100;
        }
    });

    public record AiExecutionLog(String prompt, String thinking, String authorId) {}

    /**
     * Create a new health service.
     *
     * @param envConfig environment configuration
     * @param storage storage layer
     * @param leaderboardService leaderboard service
     * @param hiscoreClient hiscore client
     * @param apiClient OSRS API client
     */
    public HealthService(EnvConfig envConfig, JsonStorage storage, LeaderboardService leaderboardService, HiscoreClient hiscoreClient, OsrsApiClient apiClient) {
        this.startedAt = Instant.now();
        this.envConfig = envConfig;
        this.storage = storage;
        this.leaderboardService = leaderboardService;
        this.hiscoreClient = hiscoreClient;
        this.apiClient = apiClient;
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
        builder.append("- osrs-api status: ").append(data.apiStatus()).append("\n");
        builder.append("- osrs-api ping: ").append(data.apiPing()).append("ms").append("\n");
        builder.append("- osrs hiscore status: ").append(data.osrsStatus()).append("\n");
        builder.append("- osrs hiscore ping: ").append(data.osrsPing()).append("ms").append("\n");
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
        builder.append("- ai url: ").append(data.aiUrl() == null || data.aiUrl().isBlank() ? "not set" : data.aiUrl()).append("\n");
        builder.append("- ai model: ").append(data.aiModel() == null || data.aiModel().isBlank() ? "not set" : data.aiModel()).append("\n");
        builder.append("- scheduled leaderboard: ").append(data.scheduledLeaderboard()).append("\n");
        builder.append("- leaderboard interval: ").append(data.leaderboardInterval()).append("\n");
        builder.append("- poll interval: ").append(data.pollInterval()).append("\n");
        builder.append("- gateway ping: ").append(data.discordPing()).append("ms");
        return builder.toString();
    }

    /**
     * Build an embedded health report.
     *
     * @param jda active JDA client
     * @param tab the tab to display (instance, connectivity, statistics, configuration, ai)
     * @return message embed
     */
    public MessageEmbed buildHealthEmbed(JDA jda, String tab) {
        HealthData data = getHealthData(jda);
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("🤖 BobBot Health Report");
        eb.setColor(0xffbb00); // OSRS Gold
        eb.setTimestamp(Instant.now());
        eb.setFooter("BobBot v0.1.0", jda.getSelfUser().getEffectiveAvatarUrl());

        if (tab == null || tab.isBlank() || tab.equalsIgnoreCase("all")) {
            // Full report summary
            eb.addField("🤖 Instance",
                    String.format("Env: `%s`\nStatus: `%s`\nUptime: `%s`",
                            data.environment(), data.botStatus(), FormatUtils.formatDuration(data.uptime())), true);

            eb.addField("🌐 Connectivity",
                    String.format("Discord: `%s` (%dms)\nAPI: `%s` (%dms)\nHiscores: `%s` (%dms)",
                            data.discordStatus(), data.discordPing(), data.apiStatus(), data.apiPing(), data.osrsStatus(), data.osrsPing()), true);

            eb.addField("📊 Statistics",
                    String.format("Guilds: `%d`\nPlayers: `%d`\nAdmins: `%d`\nTop XP: `%s`",
                            data.guildCount(), data.playerCount(), data.adminCount(), data.topXpUser()), true);

            String bobChatInfo = data.bobsChatChannelId() == null || data.bobsChatChannelId().isBlank() ? "`not set`" : "`" + data.bobsChatChannelId() + "`";
            if (data.bobsChatChannelId() != null && !data.bobsChatChannelId().isBlank()) {
                net.dv8tion.jda.api.entities.channel.middleman.GuildChannel channel = jda.getGuildChannelById(data.bobsChatChannelId());
                if (channel != null) {
                    bobChatInfo = String.format("[#%s](https://discord.com/channels/%s/%s)", channel.getName(), channel.getGuild().getId(), data.bobsChatChannelId());
                }
            }

            eb.addField("⚙️ Configuration",
                    String.format("LB Chan: `%s`\nBob Chat: %s\nSched: `%s`\nLB Int: `%s`\nPoll: `%s`",
                            data.leaderboardChannelId() == null || data.leaderboardChannelId().isBlank() ? "not set" : data.leaderboardChannelId(),
                            bobChatInfo,
                            data.scheduledLeaderboard(),
                            data.leaderboardInterval(),
                            data.pollInterval()), false);

            eb.addField("🤖 AI",
                    String.format("URL: `%s`\nModel: `%s`",
                            data.aiUrl() == null || data.aiUrl().isBlank() ? "not set" : data.aiUrl(),
                            data.aiModel() == null || data.aiModel().isBlank() ? "not set" : data.aiModel()), false);
        } else {
            // Tabbed view
            switch (tab.toLowerCase()) {
                case "instance" -> eb.addField("🤖 Instance",
                        String.format("**Environment:** `%s`\n**Status:** `%s`\n**Uptime:** `%s`",
                                data.environment(), data.botStatus(), FormatUtils.formatDuration(data.uptime())), false);
                case "connectivity" -> {
                    eb.addField("🌐 Discord", String.format("**Status:** `%s`\n**Ping:** `%dms`", data.discordStatus(), data.discordPing()), true);
                    eb.addField("🛠️ Gateway", String.format("**Ping:** `%dms`", data.discordPing()), true);
                    eb.addField("🚀 OSRS API", String.format("**Status:** `%s`\n**Ping:** `%dms`", data.apiStatus(), data.apiPing()), true);
                    eb.addField("⚔️ OSRS Hiscores", String.format("**Status:** `%s`\n**Ping:** `%dms`", data.osrsStatus(), data.osrsPing()), true);
                }
                case "statistics" -> {
                    eb.addField("🏰 Guilds", String.format("`%d`", data.guildCount()), true);
                    eb.addField("📜 Linked Players", String.format("`%d`", data.playerCount()), true);
                    eb.addField("👑 Admins", String.format("`%d`", data.adminCount()), true);
                    eb.addField("🏆 Top XP User", String.format("`%s`", data.topXpUser()), false);
                }
                case "configuration" -> {
                    String bobChatInfo = data.bobsChatChannelId() == null || data.bobsChatChannelId().isBlank() ? "`not set`" : "`" + data.bobsChatChannelId() + "`";
                    if (data.bobsChatChannelId() != null && !data.bobsChatChannelId().isBlank()) {
                        net.dv8tion.jda.api.entities.channel.middleman.GuildChannel channel = jda.getGuildChannelById(data.bobsChatChannelId());
                        if (channel != null) {
                            bobChatInfo = String.format("[#%s](https://discord.com/channels/%s/%s)", channel.getName(), channel.getGuild().getId(), data.bobsChatChannelId());
                        }
                    }
                    eb.addField("Leaderboard Channel", String.format("`%s`", data.leaderboardChannelId() == null || data.leaderboardChannelId().isBlank() ? "not set" : data.leaderboardChannelId()), true);
                    eb.addField("Bob's Chat Channel", bobChatInfo, true);
                    eb.addField("Scheduled Leaderboard", String.format("`%s`", data.scheduledLeaderboard()), true);
                    eb.addField("Leaderboard Interval", String.format("`%s`", data.leaderboardInterval()), true);
                    eb.addField("Poll Interval", String.format("`%s`", data.pollInterval()), true);
                }
                case "ai" -> {
                    eb.addField("API URL", String.format("`%s`", data.aiUrl() == null || data.aiUrl().isBlank() ? "not set" : data.aiUrl()), false);
                    eb.addField("Model", String.format("`%s`", data.aiModel() == null || data.aiModel().isBlank() ? "not set" : data.aiModel()), false);
                }
                default -> eb.setDescription("Unknown tab: " + tab);
            }
        }

        return eb.build();
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
        OsrsApiClient.ApiHealth apiHealth = apiClient.fetchApiHealth();
        String environment = envConfig.environment();
        if (environment == null || environment.isBlank()) {
            environment = settings.getEnvironment();
        }
        return new HealthData(
                environment,
                BotStatus.normalize(settings.getBotStatus()),
                jda.getStatus().name().toLowerCase(Locale.ROOT),
                jda.getGatewayPing(),
                osrsStatus.status,
                osrsStatus.pingMs,
                apiHealth.status(),
                apiHealth.pingMs(),
                Duration.between(startedAt, Instant.now()),
                jda.getGuilds().size(),
                storage.loadPlayers().size(),
                settings.getAdminUserIds().size(),
                resolveTopXpUser(),
                settings.getLeaderboardChannelId(),
                settings.getBobsChatChannelId(),
                settings.getAiUrl(),
                settings.getAiModel(),
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
            String apiStatus,
            long apiPing,
            Duration uptime,
            long guildCount,
            int playerCount,
            int adminCount,
            String topXpUser,
            String leaderboardChannelId,
            String bobsChatChannelId,
            String aiUrl,
            String aiModel,
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
     * Cache the AI's thoughts for a specific message ID.
     *
     * @param messageId Discord message ID
     * @param prompt the user prompt
     * @param thinking the AI's thinking/tools log
     * @param authorId the ID of the user who triggered the AI
     */
    public void cacheThought(String messageId, String prompt, String thinking, String authorId) {
        if (messageId != null && thinking != null && !thinking.isBlank()) {
            thoughtCache.put(messageId, new AiExecutionLog(prompt, thinking, authorId));
        }
    }

    /**
     * Retrieve cached thoughts for a specific message ID.
     *
     * @param messageId Discord message ID
     * @return cached log or null
     */
    public AiExecutionLog getCachedThought(String messageId) {
        return thoughtCache.get(messageId);
    }

    /**
     * Send an AI thinking log to users who have opted in.
     *
     * @param jda JDA instance
     * @param author the user who triggered the AI
     * @param prompt the prompt sent to the AI
     * @param thinking the thinking log from the AI
     */
    public void sendThinkingLog(JDA jda, net.dv8tion.jda.api.entities.User author, String prompt, String thinking) {
        BotSettings settings = storage.loadSettings();
        Set<String> recipientIds = new HashSet<>(settings.getThoughtRecipientIds());
        
        // If nobody has opted in, default to the superuser to avoid complete loss of logs
        if (recipientIds.isEmpty()) {
            String superuserId = envConfig.superuserId();
            if (superuserId != null && !superuserId.isBlank()) {
                recipientIds.add(superuserId);
            }
        }

        for (String userId : recipientIds) {
            if (!isAdmin(userId)) continue;

            LOGGER.debug("Attempting to send thinking log to user {}", userId);
            jda.retrieveUserById(userId).queue(user -> {
                user.openPrivateChannel().queue(channel -> {
                    EmbedBuilder eb = DiscordFormatUtils.createBobEmbed(jda)
                            .setTitle("🤖 AI Thinking Log")
                            .addField("For", author.getAsTag() + " (" + author.getId() + ")", true)
                            .addField("Prompt", prompt.length() > 1024 ? prompt.substring(0, 1021) + "..." : prompt, false);

                    channel.sendMessageEmbeds(eb.build()).queue(
                        s -> LOGGER.debug("Sent thinking log embed to {}", user.getId()),
                        f -> LOGGER.warn("Failed to send thinking log embed to {}", user.getId(), f)
                    );

                    sendThinkingBody(channel, thinking, user.getId());
                }, error -> LOGGER.warn("Failed to open private channel to user {} to send thinking log", user.getId(), error));
            }, error -> LOGGER.warn("Failed to retrieve user {} to send thinking log.", userId, error));
        }
    }

    /**
     * Send AI thinking log specifically to one user (on-demand).
     *
     * @param jda JDA instance
     * @param targetUser the user to receive the log
     * @param author the user who triggered the AI response
     * @param prompt the original prompt
     * @param thinking the thinking log
     */
    public void sendThoughtsToUser(JDA jda, net.dv8tion.jda.api.entities.User targetUser, net.dv8tion.jda.api.entities.User author, String prompt, String thinking) {
        targetUser.openPrivateChannel().queue(channel -> {
            EmbedBuilder eb = DiscordFormatUtils.createBobEmbed(jda)
                    .setTitle("🤖 AI Thought Breakdown (On-demand)")
                    .addField("For", author.getAsTag() + " (" + author.getId() + ")", true)
                    .addField("Prompt", prompt.length() > 1024 ? prompt.substring(0, 1021) + "..." : prompt, false);

            channel.sendMessageEmbeds(eb.build()).queue();
            sendThinkingBody(channel, thinking, targetUser.getId());
        }, error -> LOGGER.warn("Failed to open private channel to user {} for on-demand thoughts", targetUser.getId(), error));
    }

    private void sendThinkingBody(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel, String thinking, String userId) {
        if (thinking.length() <= 1900) {
            channel.sendMessage("```\n" + thinking + "\n```").queue(
                s -> LOGGER.debug("Sent thinking log body to {}", userId),
                f -> LOGGER.warn("Failed to send thinking log body to {}", userId, f)
            );
        } else {
            int start = 0;
            int part = 1;
            while (start < thinking.length()) {
                int end = Math.min(start + 1900, thinking.length());
                String chunk = thinking.substring(start, end);
                channel.sendMessage(String.format("```\n(Part %d)\n%s\n```", part++, chunk)).queue(
                    s -> LOGGER.debug("Sent thinking log part to {}", userId),
                    f -> LOGGER.warn("Failed to send thinking log part to {}", userId, f)
                );
                start = end;
            }
        }
    }

    /**
     * Toggle the AI thinking log preference for a user.
     *
     * @param userId Discord user ID
     * @return true if enabled, false if disabled
     */
    public boolean toggleThoughts(String userId) {
        BotSettings settings = storage.loadSettings();
        Set<String> recipients = new HashSet<>(settings.getThoughtRecipientIds());
        boolean enabled;
        if (recipients.contains(userId)) {
            recipients.remove(userId);
            enabled = false;
        } else {
            recipients.add(userId);
            enabled = true;
        }
        storage.saveSettings(settings.withThoughtRecipientIds(recipients));
        return enabled;
    }

    /**
     * Send a message to the configured Bob's chat channel.
     *
     * @param jda active JDA client
     * @param message message to send
     */
    public void announceToBobsChat(JDA jda, String message) {
        if (jda == null || message == null || message.isBlank()) return;
        
        BotSettings settings = storage.loadSettings();
        String channelId = settings.getBobsChatChannelId();
        if (channelId == null || channelId.isBlank()) {
            LOGGER.debug("Bob's chat channel not configured; skipping announcement: {}", message);
            return;
        }

        net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = jda.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel.class, channelId);
        if (channel != null) {
            channel.sendMessage(message).queue(
                s -> LOGGER.info("Sent announcement to Bob's chat: {}", message),
                f -> LOGGER.warn("Failed to send announcement to Bob's chat", f)
            );
        } else {
            LOGGER.warn("Could not find Bob's chat channel with ID: {}", channelId);
        }
    }

    /**
     * Send a message to the configured Bob's chat channel and wait for it to be sent.
     * Useful during shutdown.
     *
     * @param jda active JDA client
     * @param message message to send
     */
    public void announceToBobsChatBlocking(JDA jda, String message) {
        if (jda == null || message == null || message.isBlank()) return;
        
        BotSettings settings = storage.loadSettings();
        String channelId = settings.getBobsChatChannelId();
        if (channelId == null || channelId.isBlank()) return;

        net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = jda.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel.class, channelId);
        if (channel != null) {
            try {
                channel.sendMessage(message).complete();
                LOGGER.info("Sent blocking announcement to Bob's chat: {}", message);
            } catch (Exception e) {
                LOGGER.warn("Failed to send blocking announcement to Bob's chat", e);
            }
        }
    }

    /**
     * Check if a member is an admin (superuser, in admin list, or has an admin role).
     *
     * @param member Discord guild member
     * @return true if admin
     */
    public boolean isAdmin(Member member) {
        if (member == null) return false;
        if (isAdmin(member.getUser().getId())) return true;
        
        BotSettings settings = storage.loadSettings();
        String customAdminRoleId = settings.getAdminRoleId();
        
        return member.getRoles().stream()
                .anyMatch(role -> role.getName().equalsIgnoreCase(RoleService.ADMIN_ROLE_NAME) 
                        || (customAdminRoleId != null && role.getId().equals(customAdminRoleId)));
    }

    /**
     * Update and persist the custom admin role ID.
     *
     * @param roleId role ID
     * @return updated role ID
     */
    public String updateAdminRole(String roleId) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withAdminRoleId(roleId));
        return roleId;
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
