package com.bobbot;

import com.bobbot.config.EnvConfig;
import com.bobbot.discord.SlashCommandListener;
import com.bobbot.service.LeaderboardService;
import com.bobbot.service.LevelUpService;
import com.bobbot.storage.JsonStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
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
        EnvConfig envConfig = EnvConfig.load();
        JsonStorage storage = new JsonStorage(envConfig.dataDirectory());
        LevelUpService levelUpService = new LevelUpService(storage, envConfig);
        LeaderboardService leaderboardService = new LeaderboardService(storage, levelUpService);

        JDA jda = JDABuilder.createDefault(envConfig.discordToken(), EnumSet.noneOf(GatewayIntent.class))
                .setActivity(Activity.playing("OSRS levels"))
                .addEventListeners(new SlashCommandListener(envConfig, leaderboardService, levelUpService))
                .build()
                .awaitReady();

        OptionData inviteTarget = new OptionData(OptionType.STRING, "target", "chat or discord", true)
                .addChoice("chat", "chat")
                .addChoice("discord", "discord");
        OptionData inviteTargetId = new OptionData(OptionType.STRING, "target_id", "chat or guild ID", true);

        jda.updateCommands()
                .addCommands(
                        Commands.slash("invitebot", "Get an invite link or info for chat installs")
                                .addOptions(inviteTarget, inviteTargetId),
                        Commands.slash("link", "Link an Old School RuneScape username")
                                .addOption(OptionType.STRING,
                                        "player_username",
                                        "Your OSRS username",
                                        true),
                        Commands.slash("postleaderboard", "Post the current OSRS leaderboard"),
                        Commands.slash("setleaderboard", "Set the channel for leaderboard posts")
                                .addOption(OptionType.STRING,
                                        "channel_id",
                                        "Discord channel ID",
                                        true)
                )
                .queue();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> runLevelUpScan(levelUpService, jda),
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
        }));
    }

    /**
     * Run a level-up scan with best-effort error handling.
     *
     * @param levelUpService service to scan levels
     * @param jda active JDA client
     */
    private static void runLevelUpScan(LevelUpService levelUpService, JDA jda) {
        try {
            levelUpService.scanForLevelUps(jda);
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
                leaderboardService.postLeaderboard(jda, true);
            }
        } catch (Exception e) {
            LOGGER.error("Scheduled leaderboard post failed", e);
        }
    }
}
