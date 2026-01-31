package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
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
import java.util.Locale;

/**
 * JDA listener that handles all slash command interactions.
 */
public class SlashCommandListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlashCommandListener.class);
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
        switch (event.getName()) {
            case "invitebot" -> handleInvite(event);
            case "link" -> handleLink(event);
            case "postleaderboard" -> handlePostLeaderboard(event);
            case "setleaderboard" -> handleSetLeaderboard(event);
            case "health" -> handleHealth(event);
            case "power" -> handlePower(event);
            default -> event.reply("Unknown command.").setEphemeral(true).queue();
        }
    }

    /**
     * Handle the /invitebot command.
     *
     * @param event slash command event
     */
    private void handleInvite(SlashCommandInteractionEvent event) {
        String target = event.getOption("target").getAsString().toLowerCase(Locale.ROOT);
        String targetId = event.getOption("target_id").getAsString();
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
        String username = event.getOption("player_username").getAsString();
        event.deferReply(true).queue();
        try {
            PlayerRecord record = levelUpService.linkPlayer(event.getUser().getId(), username);
            event.getHook().sendMessage(String.format("Linked %s at total level %d.",
                    record.getUsername(), record.getLastTotalLevel())).queue();
        } catch (IOException | InterruptedException e) {
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
        String channelId = event.getOption("channel_id").getAsString();
        MessageChannel channel = levelUpService.resolveChannel(event.getJDA(), channelId);
        if (channel == null) {
            event.reply("Unable to find a channel with that ID.").setEphemeral(true).queue();
            return;
        }
        leaderboardService.setLeaderboardChannel(channelId);
        event.reply("Leaderboard channel set.").setEphemeral(true).queue();
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
        String action = event.getOption("action").getAsString().toLowerCase(Locale.ROOT);
        if (!action.equals("restart") && !action.equals("shutdown")) {
            event.reply("Action must be restart or shutdown.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        int exitCode = action.equals("restart") ? 2 : 0;
        String message = action.equals("restart")
                ? "Restarting bot now."
                : "Shutting down bot now.";
        event.reply(message)
                .setEphemeral(true)
                .queue(success -> shutdown(event.getJDA(), action, exitCode));
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
}
