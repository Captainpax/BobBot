package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
import com.bobbot.service.HealthService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDA listener that notifies the configured superuser when the bot is online.
 */
public class ReadyNotificationListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReadyNotificationListener.class);

    private final EnvConfig envConfig;
    private final HealthService healthService;

    /**
     * Create a new listener with configuration.
     *
     * @param envConfig     environment configuration
     * @param healthService health service for stats
     */
    public ReadyNotificationListener(EnvConfig envConfig, HealthService healthService) {
        this.envConfig = envConfig;
        this.healthService = healthService;
    }

    @Override
    public void onReady(ReadyEvent event) {
        String superuserId = envConfig.superuserId();
        if (superuserId == null || superuserId.isBlank()) {
            LOGGER.warn("Superuser ID is missing; skipping ready notification.");
            return;
        }
        JDA jda = event.getJDA();

        HealthService.HealthData stats = healthService.getHealthData(jda);
        StringBuilder sb = new StringBuilder("**BobBot is online**\n\n");
        sb.append("📊 **Stats:**\n");
        sb.append("- Environment: `").append(stats.environment()).append("`\n");
        sb.append("- Guilds: `").append(stats.guildCount()).append("`\n");
        sb.append("- Linked Players: `").append(stats.playerCount()).append("`\n");
        sb.append("- OSRS Status: `").append(stats.osrsStatus()).append("` (").append(stats.osrsPing()).append("ms)\n");

        String bobsChatId = stats.bobsChatChannelId();
        if (bobsChatId != null && !bobsChatId.isBlank()) {
            GuildChannel channel = jda.getGuildChannelById(bobsChatId);
            if (channel != null) {
                String link = String.format("https://discord.com/channels/%s/%s", channel.getGuild().getId(), bobsChatId);
                sb.append("\n🔗 **Bob's Chat:** [Join Here](").append(link).append(")");
            }
        }

        jda.retrieveUserById(superuserId).queue(user -> user.openPrivateChannel()
                        .flatMap(channel -> channel.sendMessage(sb.toString()))
                        .queue(
                                success -> LOGGER.info("Sent ready notification to superuser {}", superuserId),
                                error -> LOGGER.warn("Failed to send ready notification to superuser {}", superuserId, error)
                        ),
                error -> LOGGER.warn("Failed to retrieve superuser {} for ready notification", superuserId, error));
    }
}
