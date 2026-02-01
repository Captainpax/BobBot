package com.bobbot;

import com.bobbot.config.EnvConfig;
import com.bobbot.discord.BotStatus;
import com.bobbot.discord.ReadyNotificationListener;
import com.bobbot.discord.SlashCommandListener;
import com.bobbot.discord.MentionHealthListener;
import com.bobbot.health.HealthHttpServer;
import com.bobbot.osrs.Skill;
import com.bobbot.service.HealthService;
import com.bobbot.service.LeaderboardService;
import com.bobbot.service.LevelUpService;
import com.bobbot.service.PriceService;
import com.bobbot.storage.JsonStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;
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
        LevelUpService levelUpService = new LevelUpService(storage, envConfig);
        LeaderboardService leaderboardService = new LeaderboardService(storage, levelUpService);
        PriceService priceService = new PriceService();
        HealthService healthService = new HealthService(envConfig, storage, leaderboardService);
        HealthHttpServer healthHttpServer = new HealthHttpServer(envConfig, healthService);
        healthHttpServer.start(Optional.empty());

        if (!envConfig.hasDiscordToken()) {
            LOGGER.error("Discord token not configured. Bot will remain offline until a valid token is provided.");
            return;
        }

        LOGGER.info("Building JDA client");
        String configuredStatus = BotStatus.normalize(storage.loadSettings().getBotStatus());
        JDA jda;
        try {
            jda = JDABuilder.createDefault(envConfig.discordToken(),
                            EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
                    .setStatus(BotStatus.toOnlineStatus(configuredStatus))
                    .setActivity(Activity.playing("OSRS levels"))
                    .addEventListeners(
                            new SlashCommandListener(envConfig, leaderboardService, levelUpService, healthService, priceService),
                            new ReadyNotificationListener(envConfig),
                            new MentionHealthListener(healthService)
                    )
                    .build()
                    .awaitReady();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize JDA. Check that the Discord token is valid and the gateway is reachable.", e);
            return;
        }
        LOGGER.info("JDA client ready");
        healthHttpServer.setJda(Optional.of(jda));
        leaderboardService.updateBotActivity(jda);

        OptionData inviteTarget = new OptionData(OptionType.STRING, "target", "chat or discord", true)
                .addChoice("chat", "chat")
                .addChoice("discord", "discord");
        OptionData inviteTargetId = new OptionData(OptionType.STRING, "target_id", "chat or guild ID", true);
        OptionData powerAction = new OptionData(OptionType.STRING, "action", "restart or shutdown", true)
                .addChoice("restart", "restart")
                .addChoice("shutdown", "shutdown");
        OptionData statusState = new OptionData(OptionType.STRING, "state", "online, busy, or offline", true)
                .addChoice("online", "online")
                .addChoice("busy", "busy")
                .addChoice("offline", "offline");
        OptionData skillOption = new OptionData(OptionType.STRING, "skill", "Specific skill or 'all'", false)
                .addChoice("all", "all");
        for (Skill skill : Skill.values()) {
            skillOption.addChoice(skill.displayName(), skill.name().toLowerCase());
        }

        LOGGER.info("Queueing slash command registration");
        EnumSet<InteractionContextType> dmAndGuild = EnumSet.of(
                InteractionContextType.GUILD,
                InteractionContextType.BOT_DM
        );
        EnumSet<IntegrationType> installTypes = EnumSet.of(
                IntegrationType.GUILD_INSTALL,
                IntegrationType.USER_INSTALL
        );
        jda.updateCommands()
                .addCommands(
                        Commands.slash("os", "OSRS player commands")
                                .setContexts(dmAndGuild)
                                .setIntegrationTypes(installTypes)
                                .addSubcommands(
                                        new SubcommandData("link", "Link an Old School RuneScape username")
                                                .addOption(OptionType.STRING, "player_name", "Your OSRS username", true),
                                        new SubcommandData("unlink", "Unlink your Old School RuneScape username"),
                                        new SubcommandData("stats", "Show your current level and gains since the last leaderboard")
                                                .addOptions(skillOption),
                                        new SubcommandData("pricelookup", "Look up the current G.E. price of an item")
                                                .addOption(OptionType.STRING, "item", "The name of the item", true)
                                ),
                        Commands.slash("admin", "Administrative commands")
                                .setContexts(dmAndGuild)
                                .setIntegrationTypes(installTypes)
                                .addSubcommands(
                                        new SubcommandData("invite", "Get an invite link or info for chat installs")
                                                .addOptions(inviteTarget, inviteTargetId),
                                        new SubcommandData("postleaderboard", "Post the current OSRS leaderboard")
                                                .addOptions(skillOption),
                                        new SubcommandData("setleaderboard", "Set the channel for leaderboard posts")
                                                .addOption(OptionType.STRING, "channel_id", "Discord channel ID", true),
                                        new SubcommandData("health", "Check bot health and stats"),
                                        new SubcommandData("power", "Restart or shutdown the bot")
                                                .addOptions(powerAction),
                                        new SubcommandData("status", "Update the bot presence status")
                                                .addOptions(statusState),
                                        new SubcommandData("addadmin", "Add a user to the admin list")
                                                .addOption(OptionType.STRING, "user_id", "Discord user ID", true),
                                        new SubcommandData("removeadmin", "Remove a user from the admin list")
                                                .addOption(OptionType.STRING, "user_id", "Discord user ID", true)
                                )
                                .addSubcommandGroups(
                                        new SubcommandGroupData("set", "Update bot settings")
                                                .addSubcommands(
                                                        new SubcommandData("env", "Bot environment setting")
                                                                .addOption(OptionType.STRING, "value", "Bot environment setting", true),
                                                        new SubcommandData("bobschat", "Main channel for Bob's pings")
                                                                .addOption(OptionType.STRING, "channel_id", "Discord channel ID", true)
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
