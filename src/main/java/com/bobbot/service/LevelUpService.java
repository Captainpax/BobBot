package com.bobbot.service;

import com.bobbot.config.EnvConfig;
import com.bobbot.osrs.HiscoreClient;
import com.bobbot.osrs.SkillStat;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service responsible for linking players and detecting level-ups.
 */
public class LevelUpService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LevelUpService.class);
    private final JsonStorage storage;
    private final EnvConfig envConfig;
    private final HiscoreClient hiscoreClient;

    /**
     * Create a new LevelUpService.
     *
     * @param storage storage layer
     * @param envConfig environment configuration
     */
    public LevelUpService(JsonStorage storage, EnvConfig envConfig) {
        this.storage = storage;
        this.envConfig = envConfig;
        this.hiscoreClient = new HiscoreClient();
    }

    /**
     * Link a Discord user to an OSRS username, storing the current total level.
     *
     * @param discordUserId Discord user ID
     * @param username OSRS username
     * @return updated player record
     * @throws IOException on hiscore lookup failure
     * @throws InterruptedException on interrupted HTTP requests
     */
    public PlayerRecord linkPlayer(String discordUserId, String username) throws IOException, InterruptedException {
        Map<String, PlayerRecord> players = new HashMap<>(storage.loadPlayers());
        SkillStat overall = hiscoreClient.fetchOverallStat(username);
        Instant snapshotTime = Instant.now();
        PlayerRecord updated = new PlayerRecord(username, overall.level(), overall.xp(), snapshotTime, null,
                overall.level(), overall.xp(), snapshotTime);
        players.put(discordUserId, updated);
        storage.savePlayers(players);
        return updated;
    }

    /**
     * Unlink a Discord user from their OSRS username.
     *
     * @param discordUserId Discord user ID
     * @return true if a player was unlinked, false if they weren't linked
     */
    public boolean unlinkPlayer(String discordUserId) {
        Map<String, PlayerRecord> players = new HashMap<>(storage.loadPlayers());
        if (players.containsKey(discordUserId)) {
            players.remove(discordUserId);
            storage.savePlayers(players);
            return true;
        }
        return false;
    }

    /**
     * Scan all linked players for total level increases and announce changes.
     *
     * @param jda active JDA client
     */
    public void scanForLevelUps(JDA jda) {
        Map<String, PlayerRecord> players = new HashMap<>(storage.loadPlayers());
        if (players.isEmpty()) {
            return;
        }
        BotSettings settings = storage.loadSettings();
        MessageChannel leaderboardChannel = resolveChannel(jda, settings.getLeaderboardChannelId());
        MessageChannel bobsChatChannel = resolveChannel(jda, settings.getBobsChatChannelId());
        Map<String, PlayerRecord> updated = new HashMap<>(players);
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            String discordUserId = entry.getKey();
            PlayerRecord record = entry.getValue();
            try {
                SkillStat overall = hiscoreClient.fetchOverallStat(record.getUsername());
                if (overall.level() > record.getLastTotalLevel()) {
                    int gained = overall.level() - record.getLastTotalLevel();
                    updated.put(discordUserId, record.withLevel(overall.level(), overall.xp()));

                    String message = String.format("%s leveled up! +%d total levels (now %d).",
                            record.getUsername(), gained, overall.level());

                    if (leaderboardChannel != null) {
                        leaderboardChannel.sendMessage(message).queue();
                    }

                    if (bobsChatChannel != null) {
                        String pingMessage = String.format("<@%s> you leveled up! %s is now total level %d (+%d).",
                                discordUserId, record.getUsername(), overall.level(), gained);
                        bobsChatChannel.sendMessage(pingMessage).queue();
                    }
                } else if (overall.xp() > record.getLastTotalXp()) {
                    updated.put(discordUserId, record.withLevel(overall.level(), overall.xp()));
                }
            } catch (IOException | InterruptedException e) {
                LOGGER.warn("Failed to fetch level for {}", record.getUsername());
            }
        }
        storage.savePlayers(updated);
    }

    /**
     * Refresh total levels for all linked players.
     *
     * @return sorted list of updated player records
     */
    public List<PlayerRecord> refreshLevels() {
        Map<String, PlayerRecord> refreshed = refreshLevelsWithIds();
        if (refreshed.isEmpty()) {
            return List.of();
        }
        List<PlayerRecord> records = new ArrayList<>(refreshed.values());
        records.sort(Comparator.comparingInt(PlayerRecord::getLastTotalLevel).reversed());
        return records;
    }

    /**
     * Refresh total levels for all linked players with Discord IDs.
     *
     * @return updated players keyed by Discord user ID
     */
    public Map<String, PlayerRecord> refreshLevelsWithIds() {
        Map<String, PlayerRecord> players = new HashMap<>(storage.loadPlayers());
        if (players.isEmpty()) {
            return Map.of();
        }
        Map<String, PlayerRecord> updated = new HashMap<>(players);
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            PlayerRecord record = entry.getValue();
            try {
                SkillStat overall = hiscoreClient.fetchOverallStat(record.getUsername());
                PlayerRecord newRecord = record.withLevel(overall.level(), overall.xp());
                updated.put(entry.getKey(), newRecord);
            } catch (IOException | InterruptedException e) {
                // keep existing record
            }
        }
        storage.savePlayers(updated);
        return updated;
    }

    /**
     * Refresh a single player's total level.
     *
     * @param discordUserId Discord user ID
     * @return refreshed player record, or null if not linked
     * @throws IOException on hiscore lookup failure
     * @throws InterruptedException on interrupted HTTP requests
     */
    public PlayerRecord refreshPlayer(String discordUserId) throws IOException, InterruptedException {
        Map<String, PlayerRecord> players = new HashMap<>(storage.loadPlayers());
        PlayerRecord record = players.get(discordUserId);
        if (record == null) {
            return null;
        }
        SkillStat overall = hiscoreClient.fetchOverallStat(record.getUsername());
        PlayerRecord updated = record.withLevel(overall.level(), overall.xp());
        players.put(discordUserId, updated);
        storage.savePlayers(players);
        return updated;
    }

    /**
     * Fetch a player's full hiscore stats.
     *
     * @param username OSRS username
     * @return ordered skill stats
     * @throws IOException on hiscore lookup failure
     * @throws InterruptedException on interrupted HTTP requests
     */
    public List<SkillStat> fetchSkillStats(String username) throws IOException, InterruptedException {
        return hiscoreClient.fetchSkillStats(username);
    }

    /**
     * Record a leaderboard snapshot for all linked players.
     */
    public void recordLeaderboardSnapshot() {
        Map<String, PlayerRecord> players = new HashMap<>(storage.loadPlayers());
        if (players.isEmpty()) {
            return;
        }
        Map<String, PlayerRecord> updated = new HashMap<>();
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            PlayerRecord record = entry.getValue();
            updated.put(entry.getKey(), record.withLeaderboardSnapshot(record.getLastTotalLevel()));
        }
        storage.savePlayers(updated);
    }

    /**
     * Resolve a Discord message channel from a channel ID.
     *
     * @param jda active JDA client
     * @param channelId channel ID to resolve
     * @return resolved channel or null
     */
    public MessageChannel resolveChannel(JDA jda, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return null;
        }
        return jda.getChannelById(MessageChannel.class, channelId);
    }

    /**
     * Expose configuration for other services.
     *
     * @return environment configuration
     */
    public EnvConfig envConfig() {
        return envConfig;
    }
}
