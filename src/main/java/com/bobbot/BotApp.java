package com.bobbot;

import com.bobbot.config.EnvConfig;
import com.bobbot.discord.BotStatus;
import com.bobbot.discord.ReadyNotificationListener;
import com.bobbot.discord.SlashCommandListener;
import com.bobbot.discord.MentionHealthListener;
import com.bobbot.discord.AiMessageListener;
import com.bobbot.health.HealthHttpServer;
import com.bobbot.osrs.HiscoreClient;
import com.bobbot.osrs.OsrsApiClient;
import com.bobbot.osrs.OsrsItemClient;
import com.bobbot.osrs.Skill;
import com.bobbot.service.AiService;
import com.bobbot.service.ConfigService;
import com.bobbot.service.HealthService;
import com.bobbot.service.LeaderboardService;
import com.bobbot.service.LevelUpService;
import com.bobbot.service.PaginationService;
import com.bobbot.service.PriceService;
import com.bobbot.service.RoleService;
import com.bobbot.service.WikiService;
import com.bobbot.storage.JsonStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bobbot.discord.RoleListener;

import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Application entry point that wires configuration, storage, services, and Discord JDA.
 */
public class BotApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(BotApp.class);

    /**
     * Boots the bot, registers slash commands, and schedules background tasks.
     *
     * @param args CLI args (unused)
     * @throws InterruptedException if JDA fails to initialize
     */
    public static void main(String[] args) throws InterruptedException {
        LOGGER.info("Booting BobBot");
        EnvConfig envConfig = EnvConfig.load();
        LOGGER.info("Initializing storage at {}", envConfig.dataDirectory().toAbsolutePath());
        JsonStorage storage = new JsonStorage(envConfig.dataDirectory());
        
        OsrsApiClient apiClient = new OsrsApiClient(envConfig.osrsApiUrl());
        HiscoreClient hiscoreClient = new HiscoreClient(apiClient);
        OsrsItemClient osrsItemClient = new OsrsItemClient(apiClient);
        
        LevelUpService levelUpService = new LevelUpService(storage, envConfig, hiscoreClient);
        LeaderboardService leaderboardService = new LeaderboardService(storage, levelUpService);
        PriceService priceService = new PriceService(osrsItemClient);
        RoleService roleService = new RoleService();
        ConfigService configService = new ConfigService();
        PaginationService paginationService = new PaginationService();
        WikiService wikiService = new WikiService(apiClient);
        HealthService healthService = new HealthService(envConfig, storage, leaderboardService, hiscoreClient, apiClient);
        AiService aiService = new AiService(storage, envConfig.dataDirectory(), priceService, levelUpService, leaderboardService, healthService, paginationService, wikiService, apiClient);
        HealthHttpServer healthHttpServer = new HealthHttpServer(envConfig, healthService);
        healthHttpServer.start(Optional.empty());

        if (!envConfig.hasDiscordToken()) {
            LOGGER.error("Discord token not configured. Bot will remain offline until a valid token is provided.");
            return;
        }

        LOGGER.info("Building JDA client");
        String configuredStatus = BotStatus.normalize(storage.loadSettings().getBotStatus());
        ExecutorService eventPool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() * 4));
        JDA jda;
        try {
            jda = JDABuilder.createDefault(envConfig.discordToken(),
                            EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                    .setStatus(BotStatus.toOnlineStatus(configuredStatus))
                    .setActivity(Activity.playing("OSRS levels"))
                    .setEventPool(eventPool)
                    .addEventListeners(
                            new SlashCommandListener(envConfig, leaderboardService, levelUpService, healthService, priceService, aiService, roleService, configService, paginationService, wikiService),
                            new ReadyNotificationListener(envConfig, healthService),
                            new MentionHealthListener(healthService),
                            new AiMessageListener(storage, aiService, healthService, paginationService),
                            new RoleListener(roleService, healthService, storage, envConfig)
                    )
                    .build()
                    .awaitReady();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize JDA. Check that the Discord token is valid and the gateway is reachable.", e);
            return;
        }
        LOGGER.info("JDA client ready");
        aiService.setJda(jda);
        healthHttpServer.setJda(Optional.of(jda));
        leaderboardService.updateBotActivity(jda);

        OptionData inviteTarget = new OptionData(OptionType.STRING, "target", "chat or discord", true)
                .addChoice("chat", "chat")
                .addChoice("discord", "discord");
        OptionData inviteTargetId = new OptionData(OptionType.STRING, "target_id", "chat or guild ID", true);
        OptionData powerAction = new OptionData(OptionType.STRING, "action", "reboot or stop the bot", true)
                .addChoice("reboot", "reboot")
                .addChoice("stop", "stop")
                .addChoice("restart", "restart")
                .addChoice("shutdown", "shutdown");
        OptionData statusState = new OptionData(OptionType.STRING, "state", "online, busy, or offline", true)
                .addChoice("online", "online")
                .addChoice("busy", "busy")
                .addChoice("offline", "offline");
        OptionData skillOption = new OptionData(OptionType.STRING, "skill", "Specific skill (leave empty for all/random)", false, true);

        LOGGER.info("Queueing slash command registration");
        jda.updateCommands()
                .addCommands(
                        Commands.slash("os", "OSRS player commands")
                                .addSubcommands(
                                        new SubcommandData("link", "Link an Old School RuneScape username")
                                                .addOption(OptionType.STRING, "player_name", "Your OSRS username", true),
                                        new SubcommandData("unlink", "Unlink your Old School RuneScape username"),
                                        new SubcommandData("stats", "Show your current level and gains since the last leaderboard")
                                                .addOptions(skillOption),
                                        new SubcommandData("questlookup", "Look up a quest and get details or an AI checklist")
                                                .addOption(OptionType.STRING, "quest_name", "The name of the quest", true),
                                        new SubcommandData("pricelookup", "Look up the current G.E. price of an item")
                                                .addOption(OptionType.STRING, "item", "The name of the item", true),
                                        new SubcommandData("wikilookup", "Search the OSRS Wiki for a link")
                                                .addOption(OptionType.STRING, "search", "The term to search for", true)
                                )
                                .addSubcommandGroups(
                                        new SubcommandGroupData("compare", "Compare items or skills")
                                                .addSubcommands(
                                                        new SubcommandData("price", "Compare prices of two items")
                                                                .addOption(OptionType.STRING, "item1", "First item name", true)
                                                                .addOption(OptionType.STRING, "item2", "Second item name", true),
                                                        new SubcommandData("level", "Compare two of your skills")
                                                                .addOptions(
                                                                        new OptionData(OptionType.STRING, "skill1", "First skill", true, true),
                                                                        new OptionData(OptionType.STRING, "skill2", "Second skill", true, true)
                                                                )
                                        ),
                                        new SubcommandGroupData("toggle", "Toggle your player settings")
                                                .addSubcommands(
                                                        new SubcommandData("ping", "Toggle pings for OSRS notifications")
                                                                .addOptions(
                                                                        new OptionData(OptionType.STRING, "type", "Notification type", true)
                                                                                .addChoice("Leaderboard", "leaderboard")
                                                                                .addChoice("Level Up", "levelup")
                                                                )
                                                )
                                ),
                        Commands.slash("admin", "Administrative commands")
                                .addSubcommandGroups(
                                        new SubcommandGroupData("manage", "Bot management and health")
                                                .addSubcommands(
                                                        new SubcommandData("invite", "Get an invite link or info for chat installs")
                                                                .addOptions(inviteTarget, inviteTargetId),
                                                        new SubcommandData("health", "Check bot health and stats"),
                                                        new SubcommandData("power", "Restart or shutdown the bot")
                                                                .addOptions(powerAction),
                                                        new SubcommandData("status", "Update the bot presence status")
                                                                .addOptions(statusState),
                                                        new SubcommandData("addadmin", "Add a user to the admin list")
                                                                .addOption(OptionType.STRING, "user_id", "Discord user ID", true),
                                                        new SubcommandData("removeadmin", "Remove a user from the admin list")
                                                                .addOption(OptionType.STRING, "user_id", "Discord user ID", true)
                                                ),
                                        new SubcommandGroupData("set", "Update bot settings")
                                                .addSubcommands(
                                                        new SubcommandData("env", "Set environment variable for next boot")
                                                                .addOption(OptionType.STRING, "variable", "The variable name", true, true)
                                                                .addOption(OptionType.STRING, "value", "The value to set", true),
                                                        new SubcommandData("bobschat", "Main channel for Bob's pings")
                                                                .addOption(OptionType.STRING, "channel_id", "Discord channel ID", true),
                                                        new SubcommandData("leaderboard", "Set the channel for leaderboard posts")
                                                                .addOption(OptionType.STRING, "channel_id", "Discord channel ID", true),
                                                        new SubcommandData("adminrole", "Set the custom admin role ID")
                                                                .addOption(OptionType.STRING, "role_id", "Discord role ID", true)
                                                ),
                                        new SubcommandGroupData("ai", "AI configuration")
                                                .addSubcommands(
                                                        new SubcommandData("url", "Set the AI API URL")
                                                                .addOption(OptionType.STRING, "url", "URL:Port or FQDN", true),
                                                        new SubcommandData("model", "Set the AI model name")
                                                                .addOption(OptionType.STRING, "model", "Model name", true),
                                                        new SubcommandData("personality", "Upload a personality.txt file")
                                                                .addOption(OptionType.ATTACHMENT, "file", "The personality.txt file", true),
                                                        new SubcommandData("test", "Test the AI configuration")
                                                                .addOption(OptionType.STRING, "prompt", "Test prompt (default: Hello!)", false),
                                                        new SubcommandData("toggle", "Toggle AI features")
                                                                .addOptions(new OptionData(OptionType.STRING, "feature", "The feature to toggle", true)
                                                                        .addChoice("thoughts", "thoughts"))
                                                ),
                                        new SubcommandGroupData("osrs", "OSRS related administrative tasks")
                                                .addSubcommands(
                                                        new SubcommandData("postleaderboard", "Post the current OSRS leaderboard")
                                                                .addOptions(skillOption)
                                                )
                                )
                )
                .queue(
                        success -> LOGGER.info("Slash command registration succeeded"),
                        failure -> LOGGER.error("Slash command registration failed", failure)
                );

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        LOGGER.info("Scheduling background tasks with poll interval {} and leaderboard interval {}",
                envConfig.pollInterval(),
                envConfig.leaderboardInterval());
        scheduler.scheduleAtFixedRate(() -> runLevelUpScan(levelUpService, leaderboardService, jda),
                0,
                envConfig.pollInterval().toSeconds(),
                TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> runScheduledLeaderboard(leaderboardService, jda),
                envConfig.leaderboardInterval().toSeconds(),
                envConfig.leaderboardInterval().toSeconds(),
                TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down scheduler and JDA");
            scheduler.shutdownNow();
            eventPool.shutdownNow();
            jda.shutdown();
            healthHttpServer.stop();
        }));
    }

    /**
     * Run a level-up scan with best-effort error handling.
     *
     * @param levelUpService service to scan levels
     * @param leaderboardService service to update activity
     * @param jda active JDA client
     */
    private static void runLevelUpScan(LevelUpService levelUpService, LeaderboardService leaderboardService, JDA jda) {
        try {
            levelUpService.scanForLevelUps(jda);
            leaderboardService.updateBotActivity(jda);
        } catch (Exception e) {
            LOGGER.error("Level-up scan failed", e);
        }
    }

    /**
     * Run a scheduled leaderboard post if enabled.
     *
     * @param leaderboardService service to post leaderboard
     * @param jda active JDA client
     */
    private static void runScheduledLeaderboard(LeaderboardService leaderboardService, JDA jda) {
        try {
            if (leaderboardService.isScheduledLeaderboardEnabled()) {
                leaderboardService.postLeaderboard(jda, true, null);
            }
        } catch (Exception e) {
            LOGGER.error("Scheduled leaderboard post failed", e);
        }
    }
}
