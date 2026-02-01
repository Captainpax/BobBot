package com.bobbot.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable player record for OSRS hiscore tracking.
 */
public class PlayerRecord {
    private final String username;
    private final int lastTotalLevel;
    private final long lastTotalXp;
    private final Instant lastCheckedAt;
    private final Integer lastLeaderboardTotalLevel;
    private final Integer lastWeeklySnapshotTotalLevel;
    private final Long lastWeeklySnapshotTotalXp;
    private final Instant lastWeeklySnapshotAt;
    private final Map<String, Integer> skillLevels;
    private final boolean pingOnLeaderboard;
    private final boolean pingOnLevelUp;

    /**
     * Create a player record.
     *
     * @param username OSRS username
     * @param lastTotalLevel last known total level
     * @param lastTotalXp last known total experience
     * @param lastCheckedAt last time refreshed
     * @param lastLeaderboardTotalLevel last total level at leaderboard time
     * @param lastWeeklySnapshotTotalLevel total level at the start of the current week
     * @param lastWeeklySnapshotTotalXp total XP at the start of the current week
     * @param lastWeeklySnapshotAt timestamp for the weekly snapshot
     * @param skillLevels map of skill levels
     * @param pingOnLeaderboard whether to ping the user on leaderboard updates
     * @param pingOnLevelUp whether to ping the user on level ups
     */
    @JsonCreator
    public PlayerRecord(
            @JsonProperty("username") String username,
            @JsonProperty("lastTotalLevel") int lastTotalLevel,
            @JsonProperty("lastTotalXp") long lastTotalXp,
            @JsonProperty("lastCheckedAt") Instant lastCheckedAt,
            @JsonProperty("lastLeaderboardTotalLevel") Integer lastLeaderboardTotalLevel,
            @JsonProperty("lastWeeklySnapshotTotalLevel") Integer lastWeeklySnapshotTotalLevel,
            @JsonProperty("lastWeeklySnapshotTotalXp") Long lastWeeklySnapshotTotalXp,
            @JsonProperty("lastWeeklySnapshotAt") Instant lastWeeklySnapshotAt,
            @JsonProperty("skillLevels") Map<String, Integer> skillLevels,
            @JsonProperty("pingOnLeaderboard") Boolean pingOnLeaderboard,
            @JsonProperty("pingOnLevelUp") Boolean pingOnLevelUp
    ) {
        this.username = username;
        this.lastTotalLevel = lastTotalLevel;
        this.lastTotalXp = lastTotalXp;
        this.lastCheckedAt = lastCheckedAt;
        this.lastLeaderboardTotalLevel = lastLeaderboardTotalLevel;
        this.lastWeeklySnapshotTotalLevel = lastWeeklySnapshotTotalLevel;
        this.lastWeeklySnapshotTotalXp = lastWeeklySnapshotTotalXp;
        this.lastWeeklySnapshotAt = lastWeeklySnapshotAt;
        this.skillLevels = skillLevels != null ? skillLevels : Collections.emptyMap();
        this.pingOnLeaderboard = pingOnLeaderboard != null ? pingOnLeaderboard : true;
        this.pingOnLevelUp = pingOnLevelUp != null ? pingOnLevelUp : true;
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
     * @return last known total XP
     */
    public long getLastTotalXp() {
        return lastTotalXp;
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
     * @return weekly snapshot total level
     */
    public Integer getLastWeeklySnapshotTotalLevel() {
        return lastWeeklySnapshotTotalLevel;
    }

    /**
     * @return weekly snapshot total XP
     */
    public Long getLastWeeklySnapshotTotalXp() {
        return lastWeeklySnapshotTotalXp;
    }

    /**
     * @return weekly snapshot timestamp
     */
    public Instant getLastWeeklySnapshotAt() {
        return lastWeeklySnapshotAt;
    }

    /**
     * @return map of skill levels
     */
    public Map<String, Integer> getSkillLevels() {
        return skillLevels;
    }

    /**
     * @return whether to ping on leaderboard
     */
    public boolean isPingOnLeaderboard() {
        return pingOnLeaderboard;
    }

    /**
     * @return whether to ping on level up
     */
    public boolean isPingOnLevelUp() {
        return pingOnLevelUp;
    }

    /**
     * Create a copy with a new total level/XP, skill levels, and refreshed timestamp.
     *
     * @param totalLevel updated total level
     * @param totalXp updated total XP
     * @param skillLevels updated map of skill levels
     * @return new player record
     */
    public PlayerRecord withLevel(int totalLevel, long totalXp, Map<String, Integer> skillLevels) {
        return new PlayerRecord(username, totalLevel, totalXp, Instant.now(), lastLeaderboardTotalLevel,
                lastWeeklySnapshotTotalLevel, lastWeeklySnapshotTotalXp, lastWeeklySnapshotAt, skillLevels,
                pingOnLeaderboard, pingOnLevelUp);
    }

    /**
     * Create a copy with a new total level/XP and refreshed timestamp, preserving skill levels.
     *
     * @param totalLevel updated total level
     * @param totalXp updated total XP
     * @return new player record
     */
    public PlayerRecord withLevel(int totalLevel, long totalXp) {
        return new PlayerRecord(username, totalLevel, totalXp, Instant.now(), lastLeaderboardTotalLevel,
                lastWeeklySnapshotTotalLevel, lastWeeklySnapshotTotalXp, lastWeeklySnapshotAt, skillLevels,
                pingOnLeaderboard, pingOnLevelUp);
    }

    /**
     * Create a copy with a new username, total level/XP, and skill levels.
     *
     * @param newUsername updated username
     * @param totalLevel updated total level
     * @param totalXp updated total XP
     * @param skillLevels updated map of skill levels
     * @return new player record
     */
    public PlayerRecord withUsername(String newUsername, int totalLevel, long totalXp, Map<String, Integer> skillLevels) {
        return new PlayerRecord(newUsername, totalLevel, totalXp, Instant.now(), null, null, null, null, skillLevels,
                pingOnLeaderboard, pingOnLevelUp);
    }

    /**
     * Create a copy with a new username and total level/XP.
     *
     * @param newUsername updated username
     * @param totalLevel updated total level
     * @param totalXp updated total XP
     * @return new player record
     */
    public PlayerRecord withUsername(String newUsername, int totalLevel, long totalXp) {
        return new PlayerRecord(newUsername, totalLevel, totalXp, Instant.now(), null, null, null, null, Collections.emptyMap(),
                pingOnLeaderboard, pingOnLevelUp);
    }

    /**
     * Create a copy with a new leaderboard snapshot level.
     *
     * @param totalLevel leaderboard total level
     * @return new player record
     */
    public PlayerRecord withLeaderboardSnapshot(int totalLevel) {
        return new PlayerRecord(username, lastTotalLevel, lastTotalXp, lastCheckedAt, totalLevel,
                lastWeeklySnapshotTotalLevel, lastWeeklySnapshotTotalXp, lastWeeklySnapshotAt, skillLevels,
                pingOnLeaderboard, pingOnLevelUp);
    }

    /**
     * Create a copy with a new weekly snapshot level/XP and timestamp.
     *
     * @param totalLevel weekly snapshot total level
     * @param totalXp weekly snapshot total XP
     * @param snapshotAt weekly snapshot time
     * @return new player record
     */
    public PlayerRecord withWeeklySnapshot(int totalLevel, long totalXp, Instant snapshotAt) {
        return new PlayerRecord(username, lastTotalLevel, lastTotalXp, lastCheckedAt, lastLeaderboardTotalLevel,
                totalLevel, totalXp, snapshotAt, skillLevels,
                pingOnLeaderboard, pingOnLevelUp);
    }

    /**
     * Create a copy with a new ping on leaderboard setting.
     *
     * @param enabled enabled
     * @return new player record
     */
    public PlayerRecord withPingOnLeaderboard(boolean enabled) {
        return new PlayerRecord(username, lastTotalLevel, lastTotalXp, lastCheckedAt, lastLeaderboardTotalLevel,
                lastWeeklySnapshotTotalLevel, lastWeeklySnapshotTotalXp, lastWeeklySnapshotAt, skillLevels,
                enabled, pingOnLevelUp);
    }

    /**
     * Create a copy with a new ping on level up setting.
     *
     * @param enabled enabled
     * @return new player record
     */
    public PlayerRecord withPingOnLevelUp(boolean enabled) {
        return new PlayerRecord(username, lastTotalLevel, lastTotalXp, lastCheckedAt, lastLeaderboardTotalLevel,
                lastWeeklySnapshotTotalLevel, lastWeeklySnapshotTotalXp, lastWeeklySnapshotAt, skillLevels,
                pingOnLeaderboard, enabled);
    }
}
