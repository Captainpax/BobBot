package com.bobbot.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stored settings for the bot.
 */
public class BotSettings {
    private final String leaderboardChannelId;
    private final String botStatus;

    /**
     * Create a settings object.
     *
     * @param leaderboardChannelId channel for leaderboard and level-up posts
     * @param botStatus configured bot presence status
     */
    @JsonCreator
    public BotSettings(@JsonProperty("leaderboardChannelId") String leaderboardChannelId,
                       @JsonProperty("botStatus") String botStatus) {
        this.leaderboardChannelId = leaderboardChannelId;
        this.botStatus = botStatus;
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
     * Create a new settings object with the given leaderboard channel ID.
     *
     * @param channelId channel ID
     * @return updated settings
     */
    public BotSettings withLeaderboardChannelId(String channelId) {
        return new BotSettings(channelId, botStatus);
    }

    /**
     * Create a new settings object with the given bot status.
     *
     * @param status bot presence status
     * @return updated settings
     */
    public BotSettings withBotStatus(String status) {
        return new BotSettings(leaderboardChannelId, status);
    }
}
