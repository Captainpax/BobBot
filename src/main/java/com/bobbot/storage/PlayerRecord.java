package com.bobbot.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Immutable player record for OSRS hiscore tracking.
 */
public class PlayerRecord {
    private final String username;
    private final int lastTotalLevel;
    private final Instant lastCheckedAt;
    private final Integer lastLeaderboardTotalLevel;

    /**
     * Create a player record.
     *
     * @param username OSRS username
     * @param lastTotalLevel last known total level
     * @param lastCheckedAt last time refreshed
     * @param lastLeaderboardTotalLevel last total level at leaderboard time
     */
    @JsonCreator
    public PlayerRecord(
            @JsonProperty("username") String username,
            @JsonProperty("lastTotalLevel") int lastTotalLevel,
            @JsonProperty("lastCheckedAt") Instant lastCheckedAt,
            @JsonProperty("lastLeaderboardTotalLevel") Integer lastLeaderboardTotalLevel
    ) {
        this.username = username;
        this.lastTotalLevel = lastTotalLevel;
        this.lastCheckedAt = lastCheckedAt;
        this.lastLeaderboardTotalLevel = lastLeaderboardTotalLevel;
    }

    /**
     * @return OSRS username
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return last known total level
     */
    public int getLastTotalLevel() {
        return lastTotalLevel;
    }

    /**
     * @return last refreshed timestamp
     */
    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    /**
     * @return total level at the last leaderboard snapshot
     */
    public Integer getLastLeaderboardTotalLevel() {
        return lastLeaderboardTotalLevel;
    }

    /**
     * Create a copy with a new total level and refreshed timestamp.
     *
     * @param totalLevel updated total level
     * @return new player record
     */
    public PlayerRecord withLevel(int totalLevel) {
        return new PlayerRecord(username, totalLevel, Instant.now(), lastLeaderboardTotalLevel);
    }

    /**
     * Create a copy with a new username and total level.
     *
     * @param newUsername updated username
     * @param totalLevel updated total level
     * @return new player record
     */
    public PlayerRecord withUsername(String newUsername, int totalLevel) {
        return new PlayerRecord(newUsername, totalLevel, Instant.now(), null);
    }

    /**
     * Create a copy with a new leaderboard snapshot level.
     *
     * @param totalLevel leaderboard total level
     * @return new player record
     */
    public PlayerRecord withLeaderboardSnapshot(int totalLevel) {
        return new PlayerRecord(username, lastTotalLevel, lastCheckedAt, totalLevel);
    }
}
