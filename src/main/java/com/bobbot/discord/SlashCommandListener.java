package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
import com.bobbot.osrs.OsrsXpTable;
import com.bobbot.osrs.Skill;
import com.bobbot.osrs.SkillStat;
import com.bobbot.service.HealthService;
import com.bobbot.service.LeaderboardService;
import com.bobbot.service.LevelUpService;
import com.bobbot.service.PriceService;
import com.bobbot.storage.PlayerRecord;
import com.bobbot.util.FormatUtils;
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
import java.util.Optional;

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
    private final PriceService priceService;

    /**
     * Create a new listener with dependencies.
     *
     * @param envConfig environment configuration
     * @param leaderboardService leaderboard service
     * @param levelUpService level-up service
     * @param healthService health service
     * @param priceService price lookup service
     */
    public SlashCommandListener(EnvConfig envConfig,
                                LeaderboardService leaderboardService,
                                LevelUpService levelUpService,
                                HealthService healthService,
                                PriceService priceService) {
        this.envConfig = envConfig;
        this.leaderboardService = leaderboardService;
        this.levelUpService = levelUpService;
        this.healthService = healthService;
        this.priceService = priceService;
    }

    /**
     * Dispatch slash commands to handler methods.
     *
     * @param event slash command event
     */
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        logCommand(event);
        String name = event.getName();
        String subcommand = event.getSubcommandName();
        String group = event.getSubcommandGroup();
        try {
            if ("os".equals(name)) {
                switch (subcommand != null ? subcommand : "") {
                    case "link" -> handleLink(event);
                    case "unlink" -> handleUnlink(event);
                    case "stats" -> handleStats(event);
                    case "pricelookup" -> handlePriceLookup(event);
                    default -> {
                        LOGGER.debug("Unknown OS subcommand '{}'", subcommand);
                        event.reply("Unknown subcommand.").setEphemeral(true).queue();
                    }
                }
            } else if ("admin".equals(name)) {
                if (!healthService.isAdmin(event.getUser().getId())) {
                    event.reply("You do not have permission to use admin commands.").setEphemeral(true).queue();
                    return;
                }
                if ("set".equals(group)) {
                    handleAdminSet(event);
                } else {
                    switch (subcommand != null ? subcommand : "") {
                        case "invite" -> handleInvite(event);
                        case "postleaderboard" -> handlePostLeaderboard(event);
                        case "setleaderboard" -> handleSetLeaderboard(event);
                        case "health" -> handleHealth(event);
                        case "power" -> handlePower(event);
                        case "status" -> handleStatus(event);
                        case "addadmin" -> handleAdminAddAdmin(event);
                        case "removeadmin" -> handleAdminRemoveAdmin(event);
                        default -> {
                            LOGGER.debug("Unknown admin subcommand '{}'", subcommand);
                            event.reply("Unknown subcommand.").setEphemeral(true).queue();
                        }
                    }
                }
            } else {
                LOGGER.debug("Unknown slash command name '{}'", name);
                event.reply("Unknown command.").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            LOGGER.error("Slash command '{}/{}' failed for user {}", name, subcommand, event.getUser().getId(), e);
            event.reply("Command failed unexpectedly. Please try again.").setEphemeral(true).queue();
        }
    }

    /**
     * Handle the /admin invite command.
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
     * Handle the /os link command.
     *
     * @param event slash command event
     */
    private void handleLink(SlashCommandInteractionEvent event) {
        String username = getRequiredOption(event, "player_name");
        if (username == null) {
            return;
        }
        event.deferReply(true).queue();
        try {
            PlayerRecord record = levelUpService.linkPlayer(event.getUser().getId(), username);
            leaderboardService.updateBotActivity(event.getJDA());
            event.getHook().sendMessage(String.format("Linked %s at total level %d.",
                    record.getUsername(), record.getLastTotalLevel())).queue();
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to link player '{}' for user {}", username, event.getUser().getId(), e);
            event.getHook().sendMessage("Failed to fetch hiscore data. Check the username and try again.").queue();
        }
    }

    /**
     * Handle the /os unlink command.
     *
     * @param event slash command event
     */
    private void handleUnlink(SlashCommandInteractionEvent event) {
        boolean unlinked = levelUpService.unlinkPlayer(event.getUser().getId());
        if (unlinked) {
            leaderboardService.updateBotActivity(event.getJDA());
            event.reply("Successfully unlinked your OSRS account.").setEphemeral(true).queue();
        } else {
            event.reply("You don't have an OSRS account linked.").setEphemeral(true).queue();
        }
    }

    /**
     * Handle the /admin postleaderboard command.
     *
     * @param event slash command event
     */
    private void handlePostLeaderboard(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        Skill forcedSkill = null;
        if (event.getOption("skill") != null) {
            String skillVal = event.getOption("skill").getAsString();
            if (!"all".equals(skillVal)) {
                try {
                    forcedSkill = Skill.valueOf(skillVal.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // fall back to random
                }
            }
        }

        leaderboardService.postLeaderboard(event.getJDA(), false, forcedSkill);
        event.getHook().sendMessage("Posted the leaderboard.").queue();
    }

    /**
     * Handle the /admin setleaderboard command.
     *
     * @param event slash command event
     */
    private void handleSetLeaderboard(SlashCommandInteractionEvent event) {
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
     * Handle the /os stats command.
     *
     * @param event slash command event
     */
    private void handleStats(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        try {
            PlayerRecord record = levelUpService.refreshPlayer(event.getUser().getId());
            if (record == null) {
                event.getHook().sendMessage("You haven't linked a player yet. Use /os link to get started.").queue();
                return;
            }
            List<SkillStat> stats = levelUpService.fetchSkillStats(record.getUsername());
            String skillFilter = "all";
            if (event.getOption("skill") != null) {
                skillFilter = event.getOption("skill").getAsString().toLowerCase(Locale.ROOT);
            }

            StringBuilder builder = new StringBuilder();
            builder.append("Stats for ").append(record.getUsername()).append(":\n");
            Instant lastLeaderboardTimestamp = leaderboardService.getLastLeaderboardTimestamp();
            Integer lastLeaderboardTotal = record.getLastLeaderboardTotalLevel();
            List<String> messages = new ArrayList<>();
            StringBuilder current = new StringBuilder(builder);

            if (skillFilter.equals("all")) {
                List<String> totalsRows = new ArrayList<>();
                totalsRows.add(String.format("| ⭐ Total Level | %d |", record.getLastTotalLevel()));
                if (lastLeaderboardTimestamp != null && lastLeaderboardTotal != null) {
                    int gained = record.getLastTotalLevel() - lastLeaderboardTotal;
                    String timestamp = "<t:" + lastLeaderboardTimestamp.getEpochSecond() + ":F>";
                    totalsRows.add(String.format("| ⬆️ Levels Gained (since %s) | %+d |", timestamp, gained));
                } else {
                    totalsRows.add("| ⬆️ Levels Gained (since last leaderboard) | — |");
                }
                current = DiscordFormatUtils.appendTableSection(messages,
                        current,
                        "📊 **Totals**",
                        "| 📊 Stat | ⭐ Value |",
                        "|:--|--:|",
                        totalsRows,
                        false);
                if (!stats.isEmpty()) {
                    List<String> skillRows = new ArrayList<>();
                    for (SkillStat stat : stats) {
                        if (stat.skill().isOverall()) {
                            continue;
                        }
                        skillRows.add(formatSkillRow(stat));
                    }
                    current = DiscordFormatUtils.appendTableSection(messages,
                            current,
                            "📊 **Skills**",
                            "| ⭐ Skill | ⬆️ Level | 📈 XP to Next |",
                            "|:--|--:|--:|",
                            skillRows,
                            true);
                }
            } else {
                final String filter = skillFilter;
                SkillStat targetStat = stats.stream()
                        .filter(s -> s.skill().name().toLowerCase(Locale.ROOT).equals(filter))
                        .findFirst()
                        .orElse(null);

                if (targetStat == null) {
                    event.getHook().sendMessage("Skill '" + skillFilter + "' not found.").queue();
                    return;
                }

                List<String> skillRows = new ArrayList<>();
                skillRows.add(formatSkillRow(targetStat));
                current = DiscordFormatUtils.appendTableSection(messages,
                        current,
                        "📊 **Skill: " + targetStat.skill().displayName() + "**",
                        "| ⭐ Skill | ⬆️ Level | 📈 XP to Next |",
                        "|:--|--:|--:|",
                        skillRows,
                        true);
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
     * Handle the /os pricelookup command.
     *
     * @param event slash command event
     */
    private void handlePriceLookup(SlashCommandInteractionEvent event) {
        String itemQuery = getRequiredOption(event, "item");
        if (itemQuery == null) {
            return;
        }
        event.deferReply().queue();
        try {
            Optional<PriceService.PriceInfo> infoOpt = priceService.lookupPrice(itemQuery);
            if (infoOpt.isEmpty()) {
                event.getHook().sendMessage("Item '" + itemQuery + "' not found.").queue();
                return;
            }

            PriceService.PriceInfo info = infoOpt.get();
            if (info.price() == null) {
                event.getHook().sendMessage("Price data for '" + info.itemName() + "' is currently unavailable.").queue();
                return;
            }

            StringBuilder builder = new StringBuilder();
            builder.append("💰 **G.E. Price: ").append(info.itemName()).append("**\n");
            if (info.price().high() != null) {
                builder.append("- High: `").append(formatGp(info.price().high())).append("`\n");
            }
            if (info.price().low() != null) {
                builder.append("- Low: `").append(formatGp(info.price().low())).append("`\n");
            }
            event.getHook().sendMessage(builder.toString()).queue();
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Price lookup failed for query '{}'", itemQuery, e);
            event.getHook().sendMessage("Failed to fetch price data. Please try again later.").queue();
        }
    }

    private String formatGp(long gp) {
        return String.format(Locale.US, "%,d GP", gp);
    }

    /**
     * Handle the /admin set command.
     *
     * @param event slash command event
     */
    private void handleAdminSet(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        if ("env".equals(subcommand)) {
            String env = getRequiredOption(event, "value");
            if (env != null) {
                String updated = healthService.updateEnvironment(env);
                event.reply("Bot environment set to " + updated + ".")
                        .setEphemeral(true)
                        .queue();
            }
        } else if ("bobschat".equals(subcommand)) {
            String channelId = getRequiredOption(event, "channel_id");
            if (channelId != null) {
                MessageChannel channel = levelUpService.resolveChannel(event.getJDA(), channelId);
                if (channel == null) {
                    event.reply("Unable to find a channel with that ID.").setEphemeral(true).queue();
                    return;
                }
                String updated = healthService.updateBobsChatChannel(channelId);
                event.reply("Bob's main chat channel set to " + updated + ".")
                        .setEphemeral(true)
                        .queue();
            }
        }
    }

    /**
     * Handle the /admin health command.
     *
     * @param event slash command event
     */
    private void handleHealth(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        HealthService.HealthData data = healthService.getHealthData(event.getJDA());
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder("BobBot Health Report:\n");

        // Section: Instance
        List<String> instanceRows = new ArrayList<>();
        instanceRows.add(String.format("| Environment | %s |", data.environment() == null ? "not set" : data.environment()));
        instanceRows.add(String.format("| Status | %s |", data.botStatus()));
        instanceRows.add(String.format("| Uptime | %s |", FormatUtils.formatDuration(data.uptime())));
        current = DiscordFormatUtils.appendTableSection(messages, current, "🤖 **Instance**",
                "| Property | Value |", "|:--|--:|", instanceRows, false);

        // Section: Connectivity
        List<String> connRows = new ArrayList<>();
        connRows.add(String.format("| Discord | %s | %dms |", data.discordStatus(), data.discordPing()));
        connRows.add(String.format("| Gateway | — | %dms |", data.discordPing()));
        connRows.add(String.format("| OSRS | %s | %dms |", data.osrsStatus(), data.osrsPing()));
        current = DiscordFormatUtils.appendTableSection(messages, current, "🌐 **Connectivity**",
                "| Service | Status | Ping |", "|:--|:--|--:|", connRows, false);

        // Section: Statistics
        List<String> statsRows = new ArrayList<>();
        statsRows.add(String.format("| Guilds | %d |", data.guildCount()));
        statsRows.add(String.format("| Linked Players | %d |", data.playerCount()));
        statsRows.add(String.format("| Additional Admins | %d |", data.adminCount()));
        statsRows.add(String.format("| Top XP User | %s |", data.topXpUser()));
        current = DiscordFormatUtils.appendTableSection(messages, current, "📊 **Statistics**",
                "| Metric | Value |", "|:--|--:|", statsRows, false);

        // Section: Configuration
        List<String> configRows = new ArrayList<>();
        configRows.add(String.format("| Leaderboard Ch | %s |", data.leaderboardChannelId() == null ? "not set" : data.leaderboardChannelId()));
        configRows.add(String.format("| Bob's Chat Ch | %s |", data.bobsChatChannelId() == null ? "not set" : data.bobsChatChannelId()));
        configRows.add(String.format("| Scheduled Lb | %s |", data.scheduledLeaderboard()));
        configRows.add(String.format("| Lb Interval | %s |", data.leaderboardInterval()));
        configRows.add(String.format("| Poll Interval | %s |", data.pollInterval()));
        current = DiscordFormatUtils.appendTableSection(messages, current, "⚙️ **Configuration**",
                "| Setting | Value |", "|:--|--:|", configRows, false);

        messages.add(current.toString());
        for (String message : messages) {
            event.getHook().sendMessage(message).queue();
        }
    }

    /**
     * Handle the /admin power command.
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
     * Handle the /admin status command.
     *
     * @param event slash command event
     */
    private void handleStatus(SlashCommandInteractionEvent event) {
        String status = getRequiredOption(event, "state");
        if (status == null) {
            return;
        }
        String normalized = healthService.updateBotStatus(event.getJDA(), status);
        event.reply("Bot status set to " + normalized + ".")
                .setEphemeral(true)
                .queue();
    }

    /**
     * Handle the /admin addadmin command.
     *
     * @param event slash command event
     */
    private void handleAdminAddAdmin(SlashCommandInteractionEvent event) {
        if (!healthService.isSuperuser(event.getUser().getId())) {
            event.reply("Only the superuser can add admins.").setEphemeral(true).queue();
            return;
        }
        String userId = getRequiredOption(event, "user_id");
        if (userId == null) {
            return;
        }
        if (healthService.addAdmin(userId)) {
            event.reply("Added <@" + userId + "> to the admin list.").setEphemeral(true).queue();
        } else {
            event.reply("User is already an admin or superuser.").setEphemeral(true).queue();
        }
    }

    /**
     * Handle the /admin removeadmin command.
     *
     * @param event slash command event
     */
    private void handleAdminRemoveAdmin(SlashCommandInteractionEvent event) {
        if (!healthService.isSuperuser(event.getUser().getId())) {
            event.reply("Only the superuser can remove admins.").setEphemeral(true).queue();
            return;
        }
        String userId = getRequiredOption(event, "user_id");
        if (userId == null) {
            return;
        }
        if (healthService.removeAdmin(userId)) {
            event.reply("Removed <@" + userId + "> from the admin list.").setEphemeral(true).queue();
        } else {
            event.reply("User was not in the admin list.").setEphemeral(true).queue();
        }
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
        return healthService.isSuperuser(event.getUser().getId());
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

    private String formatSkillRow(SkillStat stat) {
        if (stat.level() < 1) {
            return String.format("| %s | unranked | — |", stat.skill().displayName());
        }
        long xpToNext = OsrsXpTable.xpToNextLevel(stat.level(), stat.xp());
        return String.format(Locale.US, "| %s | %d | %,d |",
                stat.skill().displayName(),
                stat.level(),
                xpToNext);
    }

}
