package com.bobbot.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Stored settings for the bot.
 */
public class BotSettings {
    private final String leaderboardChannelId;
    private final String bobsChatChannelId;
    private final String botStatus;
    private final String environment;
    private final Instant lastLeaderboardTimestamp;
    private final Set<String> adminUserIds;

    /**
     * Create a settings object.
     *
     * @param leaderboardChannelId channel for leaderboard and level-up posts
     * @param bobsChatChannelId main channel for Bob's pings
     * @param botStatus configured bot presence status
     * @param environment bot environment setting
     * @param lastLeaderboardTimestamp last leaderboard timestamp
     * @param adminUserIds list of admin user IDs
     */
    @JsonCreator
    public BotSettings(@JsonProperty("leaderboardChannelId") String leaderboardChannelId,
                       @JsonProperty("bobsChatChannelId") String bobsChatChannelId,
                       @JsonProperty("botStatus") String botStatus,
                       @JsonProperty("environment") String environment,
                       @JsonProperty("lastLeaderboardTimestamp") Instant lastLeaderboardTimestamp,
                       @JsonProperty("adminUserIds") Set<String> adminUserIds) {
        this.leaderboardChannelId = leaderboardChannelId;
        this.bobsChatChannelId = bobsChatChannelId;
        this.botStatus = botStatus;
        this.environment = environment;
        this.lastLeaderboardTimestamp = lastLeaderboardTimestamp;
        this.adminUserIds = adminUserIds != null ? new HashSet<>(adminUserIds) : new HashSet<>();
    }

    /**
     * @return configured leaderboard channel ID
     */
    public String getLeaderboardChannelId() {
        return leaderboardChannelId;
    }

    /**
     * @return configured bobs chat channel ID
     */
    public String getBobsChatChannelId() {
        return bobsChatChannelId;
    }

    /**
     * @return configured bot presence status
     */
    public String getBotStatus() {
        return botStatus;
    }

    /**
     * @return bot environment setting
     */
    public String getEnvironment() {
        return environment;
    }

    /**
     * @return last leaderboard timestamp
     */
    public Instant getLastLeaderboardTimestamp() {
        return lastLeaderboardTimestamp;
    }

    /**
     * @return set of admin user IDs
     */
    public Set<String> getAdminUserIds() {
        return Collections.unmodifiableSet(adminUserIds);
    }

    /**
     * Create a new settings object with the given leaderboard channel ID.
     *
     * @param channelId channel ID
     * @return updated settings
     */
    public BotSettings withLeaderboardChannelId(String channelId) {
        return new BotSettings(channelId, bobsChatChannelId, botStatus, environment, lastLeaderboardTimestamp, adminUserIds);
    }

    /**
     * Create a new settings object with the given bobs chat channel ID.
     *
     * @param channelId channel ID
     * @return updated settings
     */
    public BotSettings withBobsChatChannelId(String channelId) {
        return new BotSettings(leaderboardChannelId, channelId, botStatus, environment, lastLeaderboardTimestamp, adminUserIds);
    }

    /**
     * Create a new settings object with the given bot status.
     *
     * @param status bot presence status
     * @return updated settings
     */
    public BotSettings withBotStatus(String status) {
        return new BotSettings(leaderboardChannelId, bobsChatChannelId, status, environment, lastLeaderboardTimestamp, adminUserIds);
    }

    /**
     * Create a new settings object with the given environment.
     *
     * @param environment bot environment
     * @return updated settings
     */
    public BotSettings withEnvironment(String environment) {
        return new BotSettings(leaderboardChannelId, bobsChatChannelId, botStatus, environment, lastLeaderboardTimestamp, adminUserIds);
    }

    /**
     * Create a new settings object with the given leaderboard timestamp.
     *
     * @param timestamp last leaderboard timestamp
     * @return updated settings
     */
    public BotSettings withLastLeaderboardTimestamp(Instant timestamp) {
        return new BotSettings(leaderboardChannelId, bobsChatChannelId, botStatus, environment, timestamp, adminUserIds);
    }

    /**
     * Create a new settings object with the given admin user IDs.
     *
     * @param adminUserIds set of admin user IDs
     * @return updated settings
     */
    public BotSettings withAdminUserIds(Set<String> adminUserIds) {
        return new BotSettings(leaderboardChannelId, bobsChatChannelId, botStatus, environment, lastLeaderboardTimestamp, adminUserIds);
    }
}
