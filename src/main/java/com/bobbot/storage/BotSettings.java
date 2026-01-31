package com.bobbot.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Stored settings for the bot.
 */
public class BotSettings {
    private final String leaderboardChannelId;
    private final String botStatus;
    private final Instant lastLeaderboardTimestamp;

    /**
     * Create a settings object.
     *
     * @param leaderboardChannelId channel for leaderboard and level-up posts
     * @param botStatus configured bot presence status
     * @param lastLeaderboardTimestamp last leaderboard timestamp
     */
    @JsonCreator
    public BotSettings(@JsonProperty("leaderboardChannelId") String leaderboardChannelId,
                       @JsonProperty("botStatus") String botStatus,
                       @JsonProperty("lastLeaderboardTimestamp") Instant lastLeaderboardTimestamp) {
        this.leaderboardChannelId = leaderboardChannelId;
        this.botStatus = botStatus;
        this.lastLeaderboardTimestamp = lastLeaderboardTimestamp;
    }

    /**
     * @return configured leaderboard channel ID
     */
    public String getLeaderboardChannelId() {
        return leaderboardChannelId;
    }

    /**
     * @return configured bot presence status
     */
    public String getBotStatus() {
        return botStatus;
    }

    /**
     * @return last leaderboard timestamp
     */
    public Instant getLastLeaderboardTimestamp() {
        return lastLeaderboardTimestamp;
    }

    /**
     * Create a new settings object with the given leaderboard channel ID.
     *
     * @param channelId channel ID
     * @return updated settings
     */
    public BotSettings withLeaderboardChannelId(String channelId) {
        return new BotSettings(channelId, botStatus, lastLeaderboardTimestamp);
    }

    /**
     * Create a new settings object with the given bot status.
     *
     * @param status bot presence status
     * @return updated settings
     */
    public BotSettings withBotStatus(String status) {
        return new BotSettings(leaderboardChannelId, status, lastLeaderboardTimestamp);
    }

    /**
     * Create a new settings object with the given leaderboard timestamp.
     *
     * @param timestamp last leaderboard timestamp
     * @return updated settings
     */
    public BotSettings withLastLeaderboardTimestamp(Instant timestamp) {
        return new BotSettings(leaderboardChannelId, botStatus, timestamp);
    }
}
