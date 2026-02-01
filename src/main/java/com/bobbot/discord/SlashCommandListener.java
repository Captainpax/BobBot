package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
import com.bobbot.osrs.OsrsXpTable;
import com.bobbot.osrs.Skill;
import com.bobbot.osrs.SkillStat;
import com.bobbot.service.AiService;
import com.bobbot.service.ConfigService;
import com.bobbot.service.HealthService;
import com.bobbot.service.LeaderboardService;
import com.bobbot.service.LevelUpService;
import com.bobbot.service.PaginationService;
import com.bobbot.service.PriceService;
import com.bobbot.service.RoleService;
import com.bobbot.service.WikiService;
import com.bobbot.storage.PlayerRecord;
import com.bobbot.util.FormatUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * JDA listener that handles all slash command interactions.
 */
public class SlashCommandListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlashCommandListener.class);
    private final EnvConfig envConfig;
    private final LeaderboardService leaderboardService;
    private final LevelUpService levelUpService;
    private final HealthService healthService;
    private final PriceService priceService;
    private final AiService aiService;
    private final RoleService roleService;
    private final ConfigService configService;
    private final PaginationService paginationService;
    private final WikiService wikiService;

    /**
     * Create a new listener with dependencies.
     *
     * @param envConfig environment configuration
     * @param leaderboardService leaderboard service
     * @param levelUpService level-up service
     * @param healthService health service
     * @param priceService price lookup service
     * @param aiService AI service
     * @param roleService role service
     * @param configService config service
     * @param paginationService pagination service
     * @param wikiService wiki service
     */
    public SlashCommandListener(EnvConfig envConfig,
                                LeaderboardService leaderboardService,
                                LevelUpService levelUpService,
                                HealthService healthService,
                                PriceService priceService,
                                AiService aiService,
                                RoleService roleService,
                                ConfigService configService,
                                PaginationService paginationService,
                                WikiService wikiService) {
        this.envConfig = envConfig;
        this.leaderboardService = leaderboardService;
        this.levelUpService = levelUpService;
        this.healthService = healthService;
        this.priceService = priceService;
        this.aiService = aiService;
        this.roleService = roleService;
        this.configService = configService;
        this.paginationService = paginationService;
        this.wikiService = wikiService;
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        String optionName = event.getFocusedOption().getName();
        if ("skill".equals(optionName) || "skill1".equals(optionName) || "skill2".equals(optionName)) {
            String input = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
            List<Command.Choice> options = Arrays.stream(Skill.values())
                    .filter(skill -> skill.displayName().toLowerCase(Locale.ROOT).startsWith(input)
                            || skill.name().toLowerCase(Locale.ROOT).startsWith(input))
                    .map(skill -> new Command.Choice(skill.displayName(), skill.name().toLowerCase()))
                    .limit(25)
                    .collect(Collectors.toList());
            event.replyChoices(options).queue();
            return;
        }

        if ("admin".equals(event.getName()) && "set".equals(event.getSubcommandGroup()) && "env".equals(event.getSubcommandName())) {
            if ("variable".equals(event.getFocusedOption().getName())) {
                String input = event.getFocusedOption().getValue().toUpperCase(Locale.ROOT);
                List<Command.Choice> options = configService.getSupportedVariables().stream()
                        .filter(var -> var.startsWith(input))
                        .map(var -> new Command.Choice(var, var))
                        .limit(25)
                        .collect(Collectors.toList());
                event.replyChoices(options).queue();
            }
        }
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
                if ("compare".equals(group)) {
                    switch (subcommand != null ? subcommand : "") {
                        case "price" -> handleComparePrice(event);
                        case "level" -> handleCompareLevel(event);
                        default -> {
                            LOGGER.debug("Unknown compare subcommand '{}'", subcommand);
                            event.reply("Unknown subcommand.").setEphemeral(true).queue();
                        }
                    }
                    return;
                }
                if ("toggle".equals(group)) {
                    if ("ping".equals(subcommand)) {
                        handleTogglePing(event);
                    } else {
                        LOGGER.debug("Unknown toggle subcommand '{}'", subcommand);
                        event.reply("Unknown subcommand.").setEphemeral(true).queue();
                    }
                    return;
                }
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
                boolean isAdmin = event.getMember() != null ? healthService.isAdmin(event.getMember()) : healthService.isAdmin(event.getUser().getId());
                if (!isAdmin) {
                    event.reply("You do not have permission to use admin commands.").setEphemeral(true).queue();
                    return;
                }
                if ("set".equals(group)) {
                    handleAdminSet(event);
                } else if ("ai".equals(group)) {
                    handleAdminAi(event);
                } else {
                    switch (subcommand != null ? subcommand : "") {
                        case "invite" -> handleInvite(event);
                        case "postleaderboard" -> handlePostLeaderboard(event);
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
            MessageEmbed embed = DiscordFormatUtils.createBobEmbed(jda)
                    .setTitle("📩 Bot Invite")
                    .setDescription("Use the link below to add BobBot to your server.")
                    .addField("Invite Link", "[Click Here to Invite](" + inviteUrl + ")", false)
                    .addField("Requested Guild ID", "`" + targetId + "`", false)
                    .build();
            event.replyEmbeds(embed)
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
     * Handle the /os toggle ping command.
     *
     * @param event slash command event
     */
    private void handleTogglePing(SlashCommandInteractionEvent event) {
        String type = getRequiredOption(event, "type");
        if (type == null) return;

        PlayerRecord updated = levelUpService.togglePing(event.getUser().getId(), type);
        if (updated == null) {
            event.reply("You haven't linked your OSRS account yet! Use `/os link` to get started.").setEphemeral(true).queue();
            return;
        }

        boolean enabled = "leaderboard".equals(type) ? updated.isPingOnLeaderboard() : updated.isPingOnLevelUp();
        String status = enabled ? "enabled" : "disabled";
        String typeDisplay = "leaderboard".equals(type) ? "leaderboard updates" : "level ups";

        event.reply(String.format("Pings for **%s** have been **%s**. I'll remember that next time you gain some XP!", typeDisplay, status)).queue();
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
        String userId = event.getUser().getId();
        String skillFilter = "all";
        if (event.getOption("skill") != null) {
            skillFilter = event.getOption("skill").getAsString().toLowerCase(Locale.ROOT);
        }
        sendStats(event, userId, skillFilter, true);
    }

    private void sendStats(IReplyCallback event, String userId, String skillFilter, boolean ephemeral) {
        try {
            PlayerRecord record = levelUpService.refreshPlayer(userId);
            if (record == null) {
                if (ephemeral) {
                    event.getHook().sendMessage("You haven't linked a player yet. Use /os link to get started.").queue();
                }
                return;
            }
            List<SkillStat> stats = levelUpService.fetchSkillStats(record.getUsername());

            EmbedBuilder eb = DiscordFormatUtils.createBobEmbed(event.getJDA())
                    .setAuthor("Stats for " + record.getUsername(), null, event.getUser().getEffectiveAvatarUrl());

            if (skillFilter.equals("all")) {
                eb.setTitle("📊 OSRS Stats Summary");
                eb.addField("⭐ Total Level", String.valueOf(record.getLastTotalLevel()), true);

                Instant lastLbTs = leaderboardService.getLastLeaderboardTimestamp();
                Integer lastLbTotal = record.getLastLeaderboardTotalLevel();
                if (lastLbTs != null && lastLbTotal != null) {
                    int gained = record.getLastTotalLevel() - lastLbTotal;
                    eb.addField("⬆️ Gained", String.format("`%+d` levels", gained), true);
                } else {
                    eb.addField("⬆️ Gained", "`--`", true);
                }
                eb.addBlankField(true);

                for (SkillStat stat : stats) {
                    if (stat.skill().isOverall()) continue;
                    String levelStr = stat.level() < 1 ? "unranked" : String.format("Level %d", stat.level());
                    eb.addField(stat.skill().displayName(), levelStr, true);
                }
            } else {
                final String filter = skillFilter;
                SkillStat targetStat = stats.stream()
                        .filter(s -> s.skill().name().toLowerCase(Locale.ROOT).equals(filter))
                        .findFirst()
                        .orElse(null);

                if (targetStat == null) {
                    if (ephemeral) {
                        event.getHook().sendMessage("Skill '" + skillFilter + "' not found.").queue();
                    }
                    return;
                }

                eb.setTitle("📊 Skill: " + targetStat.skill().displayName());
                eb.addField("Level", String.valueOf(targetStat.level()), true);
                eb.addField("Experience", String.format("%,d XP", targetStat.xp()), true);

                if (targetStat.level() < 99 && targetStat.level() > 0) {
                    long xpToNext = OsrsXpTable.xpToNextLevel(targetStat.level(), targetStat.xp());
                    eb.addField("Next Level In", String.format("%,d XP", xpToNext), true);
                }

                String wikiUrl = wikiService.getWikiUrl(targetStat.skill());
                eb.setDescription("**Wiki:** " + wikiUrl);
                wikiService.getSkillSummary(targetStat.skill()).ifPresent(summary ->
                        eb.addField("About " + targetStat.skill().displayName(), summary, false)
                );
            }

            var action = event.getHook().sendMessageEmbeds(eb.build());
            if (ephemeral) {
                action.addComponents(ActionRow.of(Button.primary("share:stats:" + userId + ":" + skillFilter, "Show to all")));
            }
            action.queue();

        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to fetch stats for user {}", userId, e);
            if (ephemeral) {
                event.getHook().sendMessage("Failed to fetch your latest stats. Try again in a bit.").queue();
            }
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

            MessageEmbed embed = DiscordFormatUtils.createBobEmbed(event.getJDA())
                    .setTitle("💰 G.E. Price: " + info.itemName())
                    .addField("High Price", info.price().high() != null ? "`" + formatGp(info.price().high()) + "`" : "`unknown`", true)
                    .addField("Low Price", info.price().low() != null ? "`" + formatGp(info.price().low()) + "`" : "`unknown`", true)
                    .build();

            event.getHook().sendMessageEmbeds(embed).queue();
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Price lookup failed for query '{}'", itemQuery, e);
            event.getHook().sendMessage("Failed to fetch price data. Please try again later.").queue();
        }
    }

    private void handleComparePrice(SlashCommandInteractionEvent event) {
        String item1 = getRequiredOption(event, "item1");
        String item2 = getRequiredOption(event, "item2");
        if (item1 == null || item2 == null) return;

        event.deferReply().queue();
        try {
            Optional<PriceService.PriceInfo> info1Opt = priceService.lookupPrice(item1);
            Optional<PriceService.PriceInfo> info2Opt = priceService.lookupPrice(item2);

            if (info1Opt.isEmpty()) {
                event.getHook().sendMessage("Item '" + item1 + "' not found.").queue();
                return;
            }
            if (info2Opt.isEmpty()) {
                event.getHook().sendMessage("Item '" + item2 + "' not found.").queue();
                return;
            }

            PriceService.PriceInfo info1 = info1Opt.get();
            PriceService.PriceInfo info2 = info2Opt.get();

            if (info1.price() == null || info2.price() == null) {
                event.getHook().sendMessage("Price data is missing for one of the items.").queue();
                return;
            }

            long price1 = info1.price().high() != null ? info1.price().high() : info1.price().low();
            long price2 = info2.price().high() != null ? info2.price().high() : info2.price().low();

            long diff = price1 - price2;
            String diffStr = diff > 0 ? String.format("+%,d GP", diff) : String.format("%,d GP", diff);

            MessageEmbed embed = DiscordFormatUtils.createBobEmbed(event.getJDA())
                    .setTitle("⚖️ Price Comparison")
                    .addField(info1.itemName(), formatGp(price1), true)
                    .addField(info2.itemName(), formatGp(price2), true)
                    .addField("Difference", "`" + diffStr + "`", false)
                    .build();

            event.getHook().sendMessageEmbeds(embed).queue();
        } catch (Exception e) {
            LOGGER.error("Price comparison failed", e);
            event.getHook().sendMessage("Failed to compare prices. Try again later.").queue();
        }
    }

    private void handleCompareLevel(SlashCommandInteractionEvent event) {
        String skill1Str = getRequiredOption(event, "skill1");
        String skill2Str = getRequiredOption(event, "skill2");
        if (skill1Str == null || skill2Str == null) return;

        event.deferReply(true).queue();
        try {
            PlayerRecord record = levelUpService.refreshPlayer(event.getUser().getId());
            if (record == null) {
                event.getHook().sendMessage("You haven't linked your OSRS account yet. Use /os link to get started.").queue();
                return;
            }

            List<SkillStat> stats = levelUpService.fetchSkillStats(record.getUsername());

            Optional<Skill> skill1Opt = Skill.findByName(skill1Str);
            Optional<Skill> skill2Opt = Skill.findByName(skill2Str);

            if (skill1Opt.isEmpty() || skill2Opt.isEmpty()) {
                event.getHook().sendMessage("Could not find one of the skills specified.").queue();
                return;
            }

            Skill skill1 = skill1Opt.get();
            Skill skill2 = skill2Opt.get();

            SkillStat s1 = stats.stream().filter(s -> s.skill() == skill1).findFirst().orElse(null);
            SkillStat s2 = stats.stream().filter(s -> s.skill() == skill2).findFirst().orElse(null);

            if (s1 == null || s2 == null) {
                event.getHook().sendMessage("Skill data missing for one of the skills (likely unranked).").queue();
                return;
            }

            int levelDiff = s1.level() - s2.level();
            long xpDiff = s1.xp() - s2.xp();

            String levelDiffStr = levelDiff > 0 ? "+" + levelDiff : String.valueOf(levelDiff);
            String xpDiffStr = xpDiff > 0 ? String.format("+%,d", xpDiff) : String.format("%,d", xpDiff);

            MessageEmbed embed = DiscordFormatUtils.createBobEmbed(event.getJDA())
                    .setTitle("⚖️ Skill Comparison for " + record.getUsername())
                    .addField(s1.skill().displayName(), String.format("Level %d\n(%,d XP)", s1.level(), s1.xp()), true)
                    .addField(s2.skill().displayName(), String.format("Level %d\n(%,d XP)", s2.level(), s2.xp()), true)
                    .addField("Differences", String.format("**Level:** `%s`\n**XP:** `%s`", levelDiffStr, xpDiffStr), false)
                    .build();

            event.getHook().sendMessageEmbeds(embed).queue();
        } catch (Exception e) {
            LOGGER.error("Skill comparison failed", e);
            event.getHook().sendMessage("Failed to compare skills. Try again later.").queue();
        }
    }

    private String formatGp(long gp) {
        return String.format(Locale.US, "%,d GP", gp);
    }

    /**
     * Handle the /admin ai command group.
     *
     * @param event slash command event
     */
    private void handleAdminAi(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        if ("url".equals(subcommand)) {
            String url = getRequiredOption(event, "url");
            if (url != null) {
                aiService.updateAiUrl(url);
                event.reply("AI API URL updated to: " + url).setEphemeral(true).queue();
            }
        } else if ("model".equals(subcommand)) {
            String model = getRequiredOption(event, "model");
            if (model != null) {
                aiService.updateAiModel(model);
                event.reply("AI model updated to: " + model).setEphemeral(true).queue();
            }
        } else if ("personality".equals(subcommand)) {
            net.dv8tion.jda.api.interactions.commands.OptionMapping fileOpt = event.getOption("file");
            if (fileOpt == null) {
                event.reply("Missing required file attachment.").setEphemeral(true).queue();
                return;
            }
            net.dv8tion.jda.api.entities.Message.Attachment attachment = fileOpt.getAsAttachment();
            if (!attachment.getFileName().equalsIgnoreCase("personality.txt")) {
                event.reply("Only files named 'personality.txt' are accepted.").setEphemeral(true).queue();
                return;
            }

            event.deferReply(true).queue();
            attachment.getProxy().download().thenAccept(inputStream -> {
                try {
                    String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    aiService.savePersonality(content);
                    event.getHook().sendMessage("Personality updated from personality.txt.").queue();
                } catch (IOException e) {
                    LOGGER.error("Failed to read uploaded personality.txt", e);
                    event.getHook().sendMessage("Failed to read the uploaded file.").queue();
                }
            }).exceptionally(throwable -> {
                LOGGER.error("Failed to download personality.txt", throwable);
                event.getHook().sendMessage("Failed to download the uploaded file.").queue();
                return null;
            });
        } else if ("test".equals(subcommand)) {
            handleAdminAiTest(event);
        } else if ("toggle".equals(subcommand)) {
            handleAdminAiToggle(event);
        }
    }

    /**
     * Handle the /admin ai toggle command.
     *
     * @param event slash command event
     */
    private void handleAdminAiToggle(SlashCommandInteractionEvent event) {
        boolean isAdmin = event.getMember() != null ? healthService.isAdmin(event.getMember()) : healthService.isAdmin(event.getUser().getId());
        if (!isAdmin) {
            event.reply("Only admins can toggle AI features.").setEphemeral(true).queue();
            return;
        }

        String feature = getRequiredOption(event, "feature");
        if ("thoughts".equals(feature)) {
            boolean enabled = healthService.toggleThoughts(event.getUser().getId());
            String status = enabled ? "enabled" : "disabled";
            event.reply("AI thought DMs are now **" + status + "** for you.").setEphemeral(true).queue();
        } else {
            event.reply("Unknown feature: " + feature).setEphemeral(true).queue();
        }
    }

    /**
     * Handle the /admin ai test command.
     *
     * @param event slash command event
     */
    private void handleAdminAiTest(SlashCommandInteractionEvent event) {
        String prompt = "Hello!";
        if (event.getOption("prompt") != null) {
            prompt = event.getOption("prompt").getAsString();
        }

        final String finalPrompt = prompt;
        event.deferReply(true).queue(hook -> {
            hook.editOriginal(AiService.getRandomLoadingMessage()).queue();
            CompletableFuture.runAsync(() -> {
                try {
                    String guildId = event.isFromGuild() ? event.getGuild().getId() : null;
                    AiService.AiResult result = aiService.generateResponse(finalPrompt, event.getUser().getId(), event.getChannel().getId(), guildId, null);

                    EmbedBuilder eb = DiscordFormatUtils.createBobEmbed(event.getJDA())
                            .setTitle("🤖 AI Test Result")
                            .addField("Prompt", finalPrompt, false)
                            .addField("Response", result.content(), false);

                    hook.editOriginal(event.getUser().getAsMention()).setEmbeds(eb.build()).queue(sentMsg -> {
                        healthService.cacheThought(sentMsg.getId(), finalPrompt, result.thinking(), event.getUser().getId());
                    });

                    if (!result.thinking().isBlank()) {
                        healthService.sendThinkingLog(event.getJDA(), event.getUser(), finalPrompt, result.thinking());
                    }
                } catch (Exception e) {
                    LOGGER.error("AI test failed", e);
                    hook.editOriginal("AI test failed: " + e.getMessage()).queue();
                }
            });
        });
    }

    /**
     * Handle the /admin set command.
     *
     * @param event slash command event
     */
    private void handleAdminSet(SlashCommandInteractionEvent event) {
        String subcommand = event.getSubcommandName();
        if ("env".equals(subcommand)) {
            String variable = getRequiredOption(event, "variable");
            String value = getRequiredOption(event, "value");
            if (variable != null && value != null) {
                try {
                    configService.updateEnvFile(variable, value);
                    event.reply("Updated `" + variable + "` in `.env`. This will take effect on the next boot.")
                            .setEphemeral(true)
                            .queue();
                } catch (IOException e) {
                    LOGGER.error("Failed to update .env file", e);
                    event.reply("Failed to update `.env` file.").setEphemeral(true).queue();
                }
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
        } else if ("leaderboard".equals(subcommand)) {
            handleSetLeaderboard(event);
        } else if ("adminrole".equals(subcommand)) {
            String roleId = getRequiredOption(event, "role_id");
            if (roleId != null) {
                // Try to resolve the role to verify it exists
                net.dv8tion.jda.api.entities.Role role = event.getGuild().getRoleById(roleId);
                String updated = healthService.updateAdminRole(roleId);
                String mention = role != null ? role.getAsMention() : "`" + updated + "`";
                event.reply("Custom admin role set to " + mention + ".")
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
        String activeTab = "instance";
        MessageEmbed embed = healthService.buildHealthEmbed(event.getJDA(), activeTab);
        event.getHook().sendMessageEmbeds(embed)
                .setComponents(getHealthButtons(activeTab))
                .queue();
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
        if (!normalizedAction.equals("restart") && !normalizedAction.equals("shutdown")
                && !normalizedAction.equals("reboot") && !normalizedAction.equals("stop")) {
            event.reply("Action must be reboot, stop, restart, or shutdown.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        boolean isRestart = normalizedAction.equals("restart") || normalizedAction.equals("reboot");
        int exitCode = isRestart ? 2 : 0;
        String message = isRestart
                ? "Rebooting bot now."
                : "Stopping bot now.";
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
            roleService.assignRoleToAdmin(event.getJDA(), userId);
            event.reply("Added <@" + userId + "> to the admin list and synced roles.").setEphemeral(true).queue();
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
            roleService.removeRoleFromAdmin(event.getJDA(), userId);
            event.reply("Removed <@" + userId + "> from the admin list and synced roles.").setEphemeral(true).queue();
        } else {
            event.reply("User was not in the admin list.").setEphemeral(true).queue();
        }
    }


    private ActionRow getHealthButtons(String activeTab) {
        return ActionRow.of(
                Button.primary("health:tab:instance", "Instance").withDisabled("instance".equals(activeTab)),
                Button.primary("health:tab:connectivity", "Connectivity").withDisabled("connectivity".equals(activeTab)),
                Button.primary("health:tab:statistics", "Statistics").withDisabled("statistics".equals(activeTab)),
                Button.primary("health:tab:configuration", "Configuration").withDisabled("configuration".equals(activeTab)),
                Button.primary("health:tab:ai", "AI").withDisabled("ai".equals(activeTab))
        );
    }

    private void shutdown(JDA jda, String action, int exitCode) {
        LOGGER.info("Power action '{}' requested. Shutting down JDA with exit code {}", action, exitCode);
        boolean isRestart = exitCode == 2;
        String announcement = isRestart 
                ? "Logging out to hop worlds. See you in a tick! (Rebooting...)" 
                : "World DC incoming! Bracing for impact... (Shutting down...)";
        healthService.announceToBobsChatBlocking(jda, announcement);
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

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        if (componentId.startsWith("share:stats:")) {
            String[] parts = componentId.split(":");
            if (parts.length >= 4) {
                String userId = parts[2];
                String skillFilter = parts[3];
                event.deferReply().queue();
                sendStats(event, userId, skillFilter, false);
            }
        } else if (componentId.startsWith("health:tab:")) {
            String tab = componentId.substring("health:tab:".length());
            MessageEmbed embed = healthService.buildHealthEmbed(event.getJDA(), tab);
            event.editMessageEmbeds(embed)
                    .setComponents(getHealthButtons(tab))
                    .queue();
        } else if (componentId.startsWith("ai:page:")) {
            handleAiPageButton(event);
        }
    }

    private void handleAiPageButton(ButtonInteractionEvent event) {
        String componentId = event.getComponentId();
        String[] parts = componentId.split(":");
        if (parts.length < 4) return;

        String action = parts[2];
        String sessionId = parts[3];

        PaginationService.PagedSession session = paginationService.getSession(sessionId);
        if (session == null) {
            event.reply("This pagination session has expired, mate. Try asking me again.").setEphemeral(true).queue();
            return;
        }

        int newPage = session.currentPage();
        if ("next".equals(action)) {
            newPage++;
        } else if ("prev".equals(action)) {
            newPage--;
        }

        if (newPage < 0 || newPage >= session.pages().size()) {
            event.deferEdit().queue();
            return;
        }

        paginationService.updateSessionPage(sessionId, newPage);
        PaginationService.PagedSession updatedSession = paginationService.getSession(sessionId);

        MessageEmbed newEmbed = AiMessageListener.createPaginationEmbed(event.getJDA(), updatedSession.naturalResponse(), updatedSession);

        event.editMessageEmbeds(newEmbed)
                .setComponents(AiMessageListener.createPaginationButtons(sessionId, updatedSession))
                .queue();
    }

}
