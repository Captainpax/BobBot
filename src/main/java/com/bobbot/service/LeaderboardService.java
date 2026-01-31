package com.bobbot.service;

import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service to post and manage leaderboard updates.
 */
public class LeaderboardService {
    private final JsonStorage storage;
    private final LevelUpService levelUpService;
    private final AtomicBoolean scheduledEnabled = new AtomicBoolean(true);

    /**
     * Create a new leaderboard service.
     *
     * @param storage storage layer
     * @param levelUpService level service for data refresh
     */
    public LeaderboardService(JsonStorage storage, LevelUpService levelUpService) {
        this.storage = storage;
        this.levelUpService = levelUpService;
    }

    /**
     * Post a leaderboard message to the configured channel.
     *
     * @param jda active JDA client
     * @param scheduled true when invoked by the scheduler
     */
    public void postLeaderboard(JDA jda, boolean scheduled) {
        BotSettings settings = storage.loadSettings();
        MessageChannel channel = levelUpService.resolveChannel(jda, settings.getLeaderboardChannelId());
        if (channel == null) {
            return;
        }
        List<PlayerRecord> players = levelUpService.refreshLevels();
        if (players.isEmpty()) {
            channel.sendMessage("No linked players yet. Use /link to add someone.").queue();
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(scheduled ? "Scheduled" : "Manual").append(" leaderboard:\n");
        int rank = 1;
        for (PlayerRecord record : players) {
            builder.append(rank++)
                    .append(". ")
                    .append(record.getUsername())
                    .append(" — total level ")
                    .append(record.getLastTotalLevel())
                    .append("\n");
        }
        channel.sendMessage(builder.toString()).queue();
        Instant snapshotTime = Instant.now();
        storage.saveSettings(settings.withLastLeaderboardTimestamp(snapshotTime));
        levelUpService.recordLeaderboardSnapshot();
    }

    /**
     * Persist the leaderboard channel ID.
     *
     * @param channelId channel ID
     */
    public void setLeaderboardChannel(String channelId) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withLeaderboardChannelId(channelId));
    }

    /**
     * Check whether scheduled leaderboard posts are enabled.
     *
     * @return true if scheduled posts are enabled
     */
    public boolean isScheduledLeaderboardEnabled() {
        return scheduledEnabled.get();
    }

    /**
     * Return the last leaderboard timestamp, if any.
     *
     * @return last leaderboard timestamp or null
     */
    public Instant getLastLeaderboardTimestamp() {
        return storage.loadSettings().getLastLeaderboardTimestamp();
    }

    /**
     * Expose the configured leaderboard interval.
     *
     * @return leaderboard interval
     */
    public Duration leaderboardInterval() {
        return levelUpService.envConfig().leaderboardInterval();
    }
}
