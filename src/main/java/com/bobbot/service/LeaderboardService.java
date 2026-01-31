package com.bobbot.service;

import com.bobbot.osrs.Skill;
import com.bobbot.osrs.SkillStat;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.bobbot.storage.PlayerRecord;
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
     */
    public void postLeaderboard(JDA jda, boolean scheduled) {
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
        Skill skill = pickRandomSkill();
        Map<String, SkillStat> skillStats = fetchSkillStats(skill, players);
        Instant now = Instant.now();
        Instant weekStart = startOfWeek(now);
        Map<String, PlayerRecord> updatedPlayers = ensureWeeklySnapshots(players, weekStart);

        StringBuilder builder = new StringBuilder();
        builder.append("🏆 ").append(scheduled ? "Scheduled" : "Manual").append(" Leaderboard\n\n");
        builder.append(skillEmoji(skill)).append(" Skill Leaderboard — ").append(skill.displayName()).append("\n");
        List<PlayerEntry> skillEntries = buildSkillEntries(updatedPlayers, skillStats);
        int rank = 1;
        for (PlayerEntry entry : skillEntries) {
            builder.append(rank++)
                    .append(". ")
                    .append(formatLeaderboardName(entry.discordUserId(), entry.record().getUsername()))
                    .append(" — lvl ")
                    .append(entry.skillLevel())
                    .append("\n");
        }

        builder.append("\n🏆 Overall Leaderboard — total | Δ since last | 📈 this week\n");
        List<PlayerEntry> overallEntries = buildOverallEntries(updatedPlayers);
        rank = 1;
        for (PlayerEntry entry : overallEntries) {
            PlayerRecord record = entry.record();
            int totalLevel = record.getLastTotalLevel();
            int snapshotLevel = Optional.ofNullable(record.getLastLeaderboardTotalLevel()).orElse(totalLevel);
            int weeklySnapshot = Optional.ofNullable(record.getLastWeeklySnapshotTotalLevel()).orElse(totalLevel);
            int delta = totalLevel - snapshotLevel;
            int weeklyDelta = totalLevel - weeklySnapshot;
            builder.append(rank++)
                    .append(". ")
                    .append(formatLeaderboardName(entry.discordUserId(), record.getUsername()))
                    .append(" — ")
                    .append(totalLevel)
                    .append(" | ")
                    .append(formatDelta(delta))
                    .append(" | ")
                    .append(formatDelta(weeklyDelta))
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

    private Skill pickRandomSkill() {
        List<Skill> skills = Skill.ordered().stream()
                .filter(skill -> !skill.isOverall())
                .toList();
        return skills.get(ThreadLocalRandom.current().nextInt(skills.size()));
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
            } catch (Exception ignored) {
                // best effort
            }
        }
        return stats;
    }

    private Map<String, PlayerRecord> ensureWeeklySnapshots(Map<String, PlayerRecord> players, Instant weekStart) {
        Map<String, PlayerRecord> updated = new HashMap<>(players);
        boolean changed = false;
        for (Map.Entry<String, PlayerRecord> entry : players.entrySet()) {
            PlayerRecord record = entry.getValue();
            Instant lastWeeklyAt = record.getLastWeeklySnapshotAt();
            if (lastWeeklyAt == null || lastWeeklyAt.isBefore(weekStart)) {
                updated.put(entry.getKey(), record.withWeeklySnapshot(record.getLastTotalLevel(), weekStart));
                changed = true;
            }
        }
        if (changed) {
            storage.savePlayers(updated);
        }
        return updated;
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

    private String formatLeaderboardName(String discordUserId, String username) {
        if (isValidDiscordId(discordUserId)) {
            if (username != null && !username.isBlank()) {
                return "<@" + discordUserId + "> (" + username + ")";
            }
            return "<@" + discordUserId + ">";
        }
        if (username != null && !username.isBlank()) {
            return username;
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
            default -> "🎯";
        };
    }

    private record PlayerEntry(String discordUserId, PlayerRecord record, int skillLevel, long skillXp) {
    }
}
