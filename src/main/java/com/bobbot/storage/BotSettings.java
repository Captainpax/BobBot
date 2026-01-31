package com.bobbot.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Stored settings for the bot.
 */
public class BotSettings {
    private final String leaderboardChannelId;

    /**
     * Create a settings object.
     *
     * @param leaderboardChannelId channel for leaderboard and level-up posts
     */
    @JsonCreator
    public BotSettings(@JsonProperty("leaderboardChannelId") String leaderboardChannelId) {
        this.leaderboardChannelId = leaderboardChannelId;
    }

    /**
     * @return configured leaderboard channel ID
     */
    public String getLeaderboardChannelId() {
        return leaderboardChannelId;
    }

    /**
     * Create a new settings object with the given leaderboard channel ID.
     *
     * @param channelId channel ID
     * @return updated settings
     */
    public BotSettings withLeaderboardChannelId(String channelId) {
        return new BotSettings(channelId);
    }
}
