package com.bobbot.service;

import com.bobbot.discord.DiscordFormatUtils;
import com.bobbot.osrs.Skill;
import com.bobbot.osrs.SkillStat;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
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
     * @param forcedSkill optional skill to force on the leaderboard
     */
    public void postLeaderboard(JDA jda, boolean scheduled, Skill forcedSkill) {
        BotSettings settings = storage.loadSettings();
        MessageChannel channel = levelUpService.resolveChannel(jda, settings.getLeaderboardChannelId());
        if (channel == null) {
            return;
        }
        Map<String, PlayerRecord> players = levelUpService.refreshLevelsWithIds();
        if (players.isEmpty()) {
            channel.sendMessage("No linked players yet. Use /link to add someone.").queue();
            return;
        }
        Skill skill = (forcedSkill != null) ? forcedSkill : pickRandomSkill();
        Map<String, SkillStat> skillStats = fetchSkillStats(skill, players);
        Instant now = Instant.now();
        Instant weekStart = startOfWeek(now);
        Map<String, PlayerRecord> updatedPlayers = ensureWeeklySnapshots(players, weekStart);

        EmbedBuilder eb = DiscordFormatUtils.createBobEmbed(jda)
                .setTitle("🏆 " + (scheduled ? "Scheduled" : "Manual") + " Leaderboard")
                .setDescription("Current OSRS rankings for linked players.");

        StringBuilder skillLbBuilder = new StringBuilder();
        List<PlayerEntry> skillEntries = buildSkillEntries(updatedPlayers, skillStats);
        int rank = 1;
        for (PlayerEntry entry : skillEntries) {
            skillLbBuilder.append(String.format("`%d.` %s — lvl **%d**\n",
                    rank++, formatLeaderboardName(entry.discordUserId(), entry.record()), entry.skillLevel()));
        }
        eb.addField(skillEmoji(skill) + " " + skill.displayName() + " Rankings", skillLbBuilder.toString(), false);

        StringBuilder overallBuilder = new StringBuilder();
        List<PlayerEntry> overallEntries = buildOverallEntries(updatedPlayers);
        rank = 1;
        for (PlayerEntry entry : overallEntries) {
            PlayerRecord record = entry.record();
            int totalLevel = record.getLastTotalLevel();
            int snapshotLevel = Optional.ofNullable(record.getLastLeaderboardTotalLevel()).orElse(totalLevel);
            int weeklySnapshot = Optional.ofNullable(record.getLastWeeklySnapshotTotalLevel()).orElse(totalLevel);
            int delta = totalLevel - snapshotLevel;
            int weeklyDelta = totalLevel - weeklySnapshot;

            overallBuilder.append(String.format("`%d.` %s — **%d** (%s | %s)\n",
                    rank++, formatLeaderboardName(entry.discordUserId(), record), totalLevel, formatDelta(delta), formatDelta(weeklyDelta)));
        }
        eb.addField("🏆 Overall Leaderboard", "*Format: Rank. Name — Total (Δ Last | Δ Week)*\n" + overallBuilder.toString(), false);

        channel.sendMessageEmbeds(eb.build()).queue();
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

    private Skill pickRandomSkill() {
        List<Skill> skills = Skill.ordered().stream()
                .filter(skill -> !skill.isOverall())
                .toList();
        return skills.get(ThreadLocalRandom.current().nextInt(skills.size()));
    }

    /**
     * Get a string representation of the current leaderboard for the AI.
     *
     * @return formatted leaderboard string
     */
    public String getLeaderboardSummary() {
        Map<String, PlayerRecord> players = storage.loadPlayers();
        if (players.isEmpty()) {
            return "No players are currently linked to the bot.";
        }

        List<PlayerRecord> records = new ArrayList<>(players.values());
        records.sort(Comparator.comparingInt(PlayerRecord::getLastTotalLevel).reversed());

        StringBuilder sb = new StringBuilder("Current OSRS Leaderboard:\n");
        int rank = 1;
        for (PlayerRecord record : records) {
            sb.append(String.format("%d. %s - Total Level: %d (%,d XP)\n", 
                rank++, record.getUsername(), record.getLastTotalLevel(), record.getLastTotalXp()));
        }
        
        String weeklyTop = getHighestWeeklyXpGain();
        if (!"none".equals(weeklyTop)) {
            sb.append("\nWeekly Top XP Gain: ").append(weeklyTop);
        }
        
        return sb.toString();
    }

    private Map<String, SkillStat> fetchSkillStats(Skill skill, Map<String, PlayerRecord> players) {
        Map<String, SkillStat> stats = new HashMap<>();
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            PlayerRecord record = entry.getValue();
            try {
                List<SkillStat> fetched = levelUpService.fetchSkillStats(record.getUsername());
                SkillStat stat = fetched.stream()
                        .filter(item -> item.skill() == skill)
                        .findFirst()
                        .orElse(null);
                if (stat != null) {
                    stats.put(entry.getKey(), stat);
                }
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // best effort
            }
        }
        return stats;
    }

    /**
     * Update the bot's presence activity with the highest weekly XP gain.
     *
     * @param jda active JDA client
     */
    public void updateBotActivity(JDA jda) {
        if (jda == null) {
            return;
        }
        Instant now = Instant.now();
        Instant weekStart = startOfWeek(now);
        Map<String, PlayerRecord> players = storage.loadPlayers();
        ensureWeeklySnapshots(players, weekStart);

        String highestGain = getHighestWeeklyXpGain();
        if ("none".equals(highestGain)) {
            jda.getPresence().setActivity(net.dv8tion.jda.api.entities.Activity.playing("OSRS levels"));
        } else {
            jda.getPresence().setActivity(net.dv8tion.jda.api.entities.Activity.playing("Weekly Top: " + highestGain));
        }
    }

    private Map<String, PlayerRecord> ensureWeeklySnapshots(Map<String, PlayerRecord> players, Instant weekStart) {
        Map<String, PlayerRecord> updated = new HashMap<>(players);
        boolean changed = false;
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            PlayerRecord record = entry.getValue();
            Instant lastWeeklyAt = record.getLastWeeklySnapshotAt();
            if (lastWeeklyAt == null || lastWeeklyAt.isBefore(weekStart)) {
                updated.put(entry.getKey(), record.withWeeklySnapshot(record.getLastTotalLevel(),
                        record.getLastTotalXp(), weekStart));
                changed = true;
            }
        }
        if (changed) {
            storage.savePlayers(updated);
        }
        return updated;
    }

    /**
     * Resolve the highest weekly XP gain among all players.
     *
     * @return formatted string of the highest gain, or "none"
     */
    public String getHighestWeeklyXpGain() {
        Map<String, PlayerRecord> players = storage.loadPlayers();
        if (players.isEmpty()) {
            return "none";
        }
        PlayerRecord best = null;
        long maxGain = -1;

        for (PlayerRecord record : players.values()) {
            if (record.getLastWeeklySnapshotTotalXp() != null) {
                long gain = record.getLastTotalXp() - record.getLastWeeklySnapshotTotalXp();
                if (gain > maxGain) {
                    maxGain = gain;
                    best = record;
                }
            }
        }

        if (best == null || maxGain <= 0) {
            return "none";
        }

        return String.format("%s: +%,d XP", best.getUsername(), maxGain);
    }

    private Instant startOfWeek(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime weekStart = utc.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC);
        return weekStart.toInstant();
    }

    private List<PlayerEntry> buildSkillEntries(Map<String, PlayerRecord> players, Map<String, SkillStat> stats) {
        List<PlayerEntry> entries = new ArrayList<>();
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            SkillStat stat = stats.get(entry.getKey());
            int level = stat != null ? stat.level() : 0;
            long xp = stat != null ? stat.xp() : 0;
            entries.add(new PlayerEntry(entry.getKey(), entry.getValue(), level, xp));
        }
        entries.sort(Comparator.comparingInt(PlayerEntry::skillLevel)
                .thenComparingLong(PlayerEntry::skillXp)
                .reversed());
        return entries;
    }

    private List<PlayerEntry> buildOverallEntries(Map<String, PlayerRecord> players) {
        List<PlayerEntry> entries = new ArrayList<>();
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            entries.add(new PlayerEntry(entry.getKey(), entry.getValue(), 0, 0));
        }
        entries.sort(Comparator.comparingInt((PlayerEntry entry) -> entry.record().getLastTotalLevel())
                .reversed());
        return entries;
    }

    private String formatLeaderboardName(String discordUserId, PlayerRecord record) {
        if (isValidDiscordId(discordUserId)) {
            if (record.isPingOnLeaderboard()) {
                if (record.getUsername() != null && !record.getUsername().isBlank()) {
                    return "<@" + discordUserId + "> (" + record.getUsername() + ")";
                }
                return "<@" + discordUserId + ">";
            } else {
                return "**" + record.getUsername() + "**";
            }
        }
        if (record.getUsername() != null && !record.getUsername().isBlank()) {
            return record.getUsername();
        }
        return "@unknown";
    }

    private boolean isValidDiscordId(String discordUserId) {
        return discordUserId != null && discordUserId.matches("\\d{17,20}");
    }

    private String formatDelta(int delta) {
        return delta >= 0 ? "+" + delta : String.valueOf(delta);
    }

    private String skillEmoji(Skill skill) {
        return switch (skill) {
            case ATTACK -> "⚔️";
            case DEFENCE -> "🛡️";
            case STRENGTH -> "💪";
            case HITPOINTS -> "❤️";
            case RANGED -> "🏹";
            case PRAYER -> "🙏";
            case MAGIC -> "✨";
            case COOKING -> "🍳";
            case WOODCUTTING -> "🪓";
            case FLETCHING -> "🪶";
            case FISHING -> "🎣";
            case FIREMAKING -> "🔥";
            case CRAFTING -> "🧵";
            case SMITHING -> "⚒️";
            case MINING -> "⛏️";
            case HERBLORE -> "🧪";
            case AGILITY -> "🤸";
            case THIEVING -> "🗡️";
            case SLAYER -> "☠️";
            case FARMING -> "🌾";
            case RUNECRAFT -> "🔮";
            case HUNTER -> "🪤";
            case CONSTRUCTION -> "🏗️";
            case SAILING -> "⛵";
            default -> "🎯";
        };
    }

    private record PlayerEntry(String discordUserId, PlayerRecord record, int skillLevel, long skillXp) {
    }
}
