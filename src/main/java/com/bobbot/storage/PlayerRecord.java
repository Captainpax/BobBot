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

    /**
     * Create a player record.
     *
     * @param username OSRS username
     * @param lastTotalLevel last known total level
     * @param lastCheckedAt last time refreshed
     */
    @JsonCreator
    public PlayerRecord(
            @JsonProperty("username") String username,
            @JsonProperty("lastTotalLevel") int lastTotalLevel,
            @JsonProperty("lastCheckedAt") Instant lastCheckedAt
    ) {
        this.username = username;
        this.lastTotalLevel = lastTotalLevel;
        this.lastCheckedAt = lastCheckedAt;
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
     * Create a copy with a new total level and refreshed timestamp.
     *
     * @param totalLevel updated total level
     * @return new player record
     */
    public PlayerRecord withLevel(int totalLevel) {
        return new PlayerRecord(username, totalLevel, Instant.now());
    }

    /**
     * Create a copy with a new username and total level.
     *
     * @param newUsername updated username
     * @param totalLevel updated total level
     * @return new player record
     */
    public PlayerRecord withUsername(String newUsername, int totalLevel) {
        return new PlayerRecord(newUsername, totalLevel, Instant.now());
    }
}
