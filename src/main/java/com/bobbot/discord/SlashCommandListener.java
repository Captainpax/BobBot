package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
import com.bobbot.osrs.OsrsXpTable;
import com.bobbot.osrs.SkillStat;
import com.bobbot.service.HealthService;
import com.bobbot.service.LeaderboardService;
import com.bobbot.service.LevelUpService;
import com.bobbot.storage.PlayerRecord;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JDA listener that handles all slash command interactions.
 */
public class SlashCommandListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlashCommandListener.class);
    private static final int DISCORD_MESSAGE_LIMIT = 1800;
    private final EnvConfig envConfig;
    private final LeaderboardService leaderboardService;
    private final LevelUpService levelUpService;
    private final HealthService healthService;

    /**
     * Create a new listener with dependencies.
     *
     * @param envConfig environment configuration
     * @param leaderboardService leaderboard service
     * @param levelUpService level-up service
     * @param healthService health service
     */
    public SlashCommandListener(EnvConfig envConfig,
                                LeaderboardService leaderboardService,
                                LevelUpService levelUpService,
                                HealthService healthService) {
        this.envConfig = envConfig;
        this.leaderboardService = leaderboardService;
        this.levelUpService = levelUpService;
        this.healthService = healthService;
    }

    /**
     * Dispatch slash commands to handler methods.
     *
     * @param event slash command event
     */
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        logCommand(event);
        try {
            switch (event.getName()) {
                case "invitebot" -> handleInvite(event);
                case "link" -> handleLink(event);
                case "postleaderboard" -> handlePostLeaderboard(event);
                case "setleaderboard" -> handleSetLeaderboard(event);
                case "mystats" -> handleMyStats(event);
                case "health" -> handleHealth(event);
                case "power" -> handlePower(event);
                case "status" -> handleStatus(event);
                default -> {
                    LOGGER.debug("Unknown slash command name '{}'", event.getName());
                    event.reply("Unknown command.").setEphemeral(true).queue();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Slash command '{}' failed for user {}", event.getName(), event.getUser().getId(), e);
            event.reply("Command failed unexpectedly. Please try again.").setEphemeral(true).queue();
        }
    }

    /**
     * Handle the /invitebot command.
     *
     * @param event slash command event
     */
    private void handleInvite(SlashCommandInteractionEvent event) {
        String target = getRequiredOption(event, "target");
        String targetId = getRequiredOption(event, "target_id");
        if (target == null || targetId == null) {
            return;
        }
        target = target.toLowerCase(Locale.ROOT);
        if (!isSuperuser(event)) {
            event.reply("Only the configured superuser can request invites.").setEphemeral(true).queue();
            return;
        }
        JDA jda = event.getJDA();
        if (target.equals("discord")) {
            String inviteUrl = jda.getInviteUrl(Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY);
            event.reply("Use this invite link to add the bot to a server (requested guild: " + targetId + "): " + inviteUrl)
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (target.equals("chat")) {
            event.reply("Discord bots cannot be added to group DMs (requested chat: " + targetId + "). Please invite the bot to a server instead.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        event.reply("Target must be chat or discord.").setEphemeral(true).queue();
    }

    /**
     * Handle the /link command.
     *
     * @param event slash command event
     */
    private void handleLink(SlashCommandInteractionEvent event) {
        String username = getRequiredOption(event, "player_username");
        if (username == null) {
            return;
        }
        event.deferReply(true).queue();
        try {
            PlayerRecord record = levelUpService.linkPlayer(event.getUser().getId(), username);
            event.getHook().sendMessage(String.format("Linked %s at total level %d.",
                    record.getUsername(), record.getLastTotalLevel())).queue();
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to link player '{}' for user {}", username, event.getUser().getId(), e);
            event.getHook().sendMessage("Failed to fetch hiscore data. Check the username and try again.").queue();
        }
    }

    /**
     * Handle the /postleaderboard command.
     *
     * @param event slash command event
     */
    private void handlePostLeaderboard(SlashCommandInteractionEvent event) {
        if (!isSuperuser(event)) {
            event.reply("Only the configured superuser can post the leaderboard.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        leaderboardService.postLeaderboard(event.getJDA(), false);
        event.getHook().sendMessage("Posted the leaderboard.").queue();
    }

    /**
     * Handle the /setleaderboard command.
     *
     * @param event slash command event
     */
    private void handleSetLeaderboard(SlashCommandInteractionEvent event) {
        if (!isSuperuser(event)) {
            event.reply("Only the configured superuser can set the leaderboard channel.").setEphemeral(true).queue();
            return;
        }
        String channelId = getRequiredOption(event, "channel_id");
        if (channelId == null) {
            return;
        }
        MessageChannel channel = levelUpService.resolveChannel(event.getJDA(), channelId);
        if (channel == null) {
            event.reply("Unable to find a channel with that ID.").setEphemeral(true).queue();
            return;
        }
        leaderboardService.setLeaderboardChannel(channelId);
        event.reply("Leaderboard channel set.").setEphemeral(true).queue();
    }

    /**
     * Handle the /mystats command.
     *
     * @param event slash command event
     */
    private void handleMyStats(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        try {
            PlayerRecord record = levelUpService.refreshPlayer(event.getUser().getId());
            if (record == null) {
                event.getHook().sendMessage("You haven't linked a player yet. Use /link to get started.").queue();
                return;
            }
            List<SkillStat> stats = levelUpService.fetchSkillStats(record.getUsername());
            StringBuilder builder = new StringBuilder();
            builder.append("Stats for ").append(record.getUsername()).append(":\n");
            builder.append("- total level: ").append(record.getLastTotalLevel()).append("\n");
            Instant lastLeaderboardTimestamp = leaderboardService.getLastLeaderboardTimestamp();
            Integer lastLeaderboardTotal = record.getLastLeaderboardTotalLevel();
            if (lastLeaderboardTimestamp != null && lastLeaderboardTotal != null) {
                int gained = record.getLastTotalLevel() - lastLeaderboardTotal;
                String timestamp = "<t:" + lastLeaderboardTimestamp.getEpochSecond() + ":F>";
                builder.append("- levels gained since ").append(timestamp).append(": ").append(gained).append("\n");
            } else {
                builder.append("- levels gained since last leaderboard: no snapshot yet.\n");
            }
            List<String> messages = new ArrayList<>();
            StringBuilder current = new StringBuilder(builder);
            if (!stats.isEmpty()) {
                String skillsHeader = "Skills:\n";
                if (current.length() + skillsHeader.length() > DISCORD_MESSAGE_LIMIT) {
                    messages.add(current.toString());
                    current = new StringBuilder(skillsHeader);
                } else {
                    current.append(skillsHeader);
                }
                for (SkillStat stat : stats) {
                    if (stat.skill().isOverall()) {
                        continue;
                    }
                    String line = formatSkillLine(stat);
                    if (current.length() + line.length() + 1 > DISCORD_MESSAGE_LIMIT) {
                        messages.add(current.toString());
                        current = new StringBuilder("Skills (cont.):\n");
                    }
                    current.append(line).append("\n");
                }
            }
            messages.add(current.toString());
            for (String message : messages) {
                event.getHook().sendMessage(message).queue();
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to fetch stats for user {}", event.getUser().getId(), e);
            event.getHook().sendMessage("Failed to fetch your latest stats. Try again in a bit.").queue();
        }
    }

    /**
     * Handle the /health command.
     *
     * @param event slash command event
     */
    private void handleHealth(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        String report = healthService.buildHealthReport(event.getJDA());
        event.getHook().sendMessage(report).queue();
    }

    /**
     * Handle the /power command.
     *
     * @param event slash command event
     */
    private void handlePower(SlashCommandInteractionEvent event) {
        if (!isSuperuser(event)) {
            event.reply("Only the configured superuser can control power actions.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String action = getRequiredOption(event, "action");
        if (action == null) {
            return;
        }
        String normalizedAction = action.toLowerCase(Locale.ROOT);
        if (!normalizedAction.equals("restart") && !normalizedAction.equals("shutdown")) {
            event.reply("Action must be restart or shutdown.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        int exitCode = normalizedAction.equals("restart") ? 2 : 0;
        String message = normalizedAction.equals("restart")
                ? "Restarting bot now."
                : "Shutting down bot now.";
        event.reply(message)
                .setEphemeral(true)
                .queue(success -> shutdown(event.getJDA(), normalizedAction, exitCode));
    }

    /**
     * Handle the /status command.
     *
     * @param event slash command event
     */
    private void handleStatus(SlashCommandInteractionEvent event) {
        if (!isSuperuser(event)) {
            event.reply("Only the configured superuser can update bot status.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String status = getRequiredOption(event, "state");
        if (status == null) {
            return;
        }
        String normalized = healthService.updateBotStatus(event.getJDA(), status);
        event.reply("Bot status set to " + normalized + ".")
                .setEphemeral(true)
                .queue();
    }

    private void shutdown(JDA jda, String action, int exitCode) {
        LOGGER.info("Power action '{}' requested. Shutting down JDA with exit code {}", action, exitCode);
        jda.shutdown();
        System.exit(exitCode);
    }

    /**
     * Check if the invoking user is the configured superuser.
     *
     * @param event slash command event
     * @return true if the user is the superuser
     */
    private boolean isSuperuser(SlashCommandInteractionEvent event) {
        String superuser = envConfig.superuserId();
        return superuser != null && !superuser.isBlank() && superuser.equals(event.getUser().getId());
    }

    private String getRequiredOption(SlashCommandInteractionEvent event, String name) {
        if (event.getOption(name) == null) {
            LOGGER.warn("Slash command '{}' missing required option '{}' for user {}",
                    event.getName(), name, event.getUser().getId());
            event.reply("Missing required option: " + name + ".").setEphemeral(true).queue();
            return null;
        }
        return event.getOption(name).getAsString();
    }

    private void logCommand(SlashCommandInteractionEvent event) {
        String guildId = event.getGuild() != null ? event.getGuild().getId() : "DM";
        LOGGER.debug("Slash command '{}' received from user {} in {}",
                event.getName(), event.getUser().getId(), guildId);
    }

    private String formatSkillLine(SkillStat stat) {
        if (stat.level() < 1) {
            return String.format("%s: unranked", stat.skill().displayName());
        }
        long xpToNext = OsrsXpTable.xpToNextLevel(stat.level(), stat.xp());
        return String.format(Locale.US, "%s: %d (xp to next: %,d)",
                stat.skill().displayName(),
                stat.level(),
                xpToNext);
    }
}
