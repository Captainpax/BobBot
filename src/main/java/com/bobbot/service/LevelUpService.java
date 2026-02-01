package com.bobbot.service;

import com.bobbot.config.EnvConfig;
import com.bobbot.discord.DiscordFormatUtils;
import com.bobbot.osrs.HiscoreClient;
import com.bobbot.osrs.OsrsXpTable;
import com.bobbot.osrs.SkillStat;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
import net.dv8tion.jda.api.EmbedBuilder;
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
        List<SkillStat> stats = hiscoreClient.fetchSkillStats(username);
        SkillStat overall = stats.stream()
                .filter(s -> s.skill().isOverall())
                .findFirst()
                .orElseThrow(() -> new IOException("Overall stat missing"));

        Map<String, Integer> skillLevels = new HashMap<>();
        for (SkillStat stat : stats) {
            if (!stat.skill().isOverall()) {
                skillLevels.put(stat.skill().name(), stat.level());
            }
        }

        Instant snapshotTime = Instant.now();
        PlayerRecord updated = new PlayerRecord(username, overall.level(), overall.xp(), snapshotTime, null,
                overall.level(), overall.xp(), snapshotTime, skillLevels, true, true);
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
     * Toggle a notification ping setting for a player.
     *
     * @param discordUserId Discord user ID
     * @param type "leaderboard" or "levelup"
     * @return updated player record, or null if not linked
     */
    public PlayerRecord togglePing(String discordUserId, String type) {
        Map<String, PlayerRecord> players = new HashMap<>(storage.loadPlayers());
        PlayerRecord record = players.get(discordUserId);
        if (record == null) {
            return null;
        }

        PlayerRecord updated;
        if ("leaderboard".equals(type)) {
            updated = record.withPingOnLeaderboard(!record.isPingOnLeaderboard());
        } else if ("levelup".equals(type)) {
            updated = record.withPingOnLevelUp(!record.isPingOnLevelUp());
        } else {
            return null;
        }

        players.put(discordUserId, updated);
        storage.savePlayers(players);
        return updated;
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
        MessageChannel bobsChatChannel = resolveChannel(jda, settings.getBobsChatChannelId());
        Map<String, PlayerRecord> updated = new HashMap<>(players);
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            String discordUserId = entry.getKey();
            PlayerRecord record = entry.getValue();
            try {
                List<SkillStat> stats = hiscoreClient.fetchSkillStats(record.getUsername());
                SkillStat overall = stats.stream()
                        .filter(s -> s.skill().isOverall())
                        .findFirst()
                        .orElseThrow(() -> new IOException("Overall stat missing"));

                boolean levelIncreased = overall.level() > record.getLastTotalLevel();
                boolean firstTimeStats = record.getSkillLevels().isEmpty();

                if (levelIncreased || firstTimeStats) {
                    Map<String, Integer> currentSkillLevels = new HashMap<>();
                    List<String> skillUps = new ArrayList<>();

                    for (SkillStat stat : stats) {
                        if (stat.skill().isOverall()) continue;
                        String skillName = stat.skill().name();
                        int currentLevel = stat.level();
                        currentSkillLevels.put(skillName, currentLevel);

                        Integer lastLevel = record.getSkillLevels().get(skillName);
                        if (lastLevel != null && currentLevel > lastLevel) {
                            long xpToNext = OsrsXpTable.xpToNextLevel(currentLevel, stat.xp());
                            String xpInfo = xpToNext > 0
                                    ? String.format(" (%,d XP until %d)", xpToNext, currentLevel + 1)
                                    : " (Max Level!)";
                            skillUps.add(String.format("- **%s**: %d -> **%d**%s",
                                    stat.skill().displayName(), lastLevel, currentLevel, xpInfo));
                        }
                    }

                    updated.put(discordUserId, record.withLevel(overall.level(), overall.xp(), currentSkillLevels));

                    if (levelIncreased && bobsChatChannel != null) {
                        int totalGained = overall.level() - record.getLastTotalLevel();
                        EmbedBuilder eb = DiscordFormatUtils.createBobEmbed(jda)
                                .setTitle("🎉 Level Up!")
                                .setDescription(String.format("**%s** gained **+%d** total levels!", record.getUsername(), totalGained))
                                .addField("New Total Level", String.valueOf(overall.level()), true);

                        if (!skillUps.isEmpty()) {
                            eb.addField("Skills Gained", String.join("\n", skillUps), false);
                        }
                        
                        String mention = record.isPingOnLevelUp() ? String.format("<@%s>", discordUserId) : "**" + record.getUsername() + "**";
                        String pingContent = String.format("%s GZ on the level up!", mention);
                        bobsChatChannel.sendMessage(pingContent).setEmbeds(eb.build()).queue();
                    }
                } else if (overall.xp() > record.getLastTotalXp()) {
                    updated.put(discordUserId, record.withLevel(overall.level(), overall.xp()));
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
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
                List<SkillStat> stats = hiscoreClient.fetchSkillStats(record.getUsername());
                SkillStat overall = stats.stream()
                        .filter(s -> s.skill().isOverall())
                        .findFirst()
                        .orElseThrow(() -> new IOException("Overall stat missing"));

                Map<String, Integer> skillLevels = new HashMap<>();
                for (SkillStat stat : stats) {
                    if (!stat.skill().isOverall()) {
                        skillLevels.put(stat.skill().name(), stat.level());
                    }
                }

                PlayerRecord newRecord = record.withLevel(overall.level(), overall.xp(), skillLevels);
                updated.put(entry.getKey(), newRecord);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
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
        List<SkillStat> stats = hiscoreClient.fetchSkillStats(record.getUsername());
        SkillStat overall = stats.stream()
                .filter(s -> s.skill().isOverall())
                .findFirst()
                .orElseThrow(() -> new IOException("Overall stat missing"));

        Map<String, Integer> skillLevels = new HashMap<>();
        for (SkillStat stat : stats) {
            if (!stat.skill().isOverall()) {
                skillLevels.put(stat.skill().name(), stat.level());
            }
        }

        PlayerRecord updated = record.withLevel(overall.level(), overall.xp(), skillLevels);
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
