package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
import com.bobbot.service.HealthService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
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
        healthService.announceToBobsChat(jda, "Just logged in at the GE. Did I miss a drop, legends?");

        MessageEmbed embed = healthService.buildHealthEmbed(jda, "all");

        jda.retrieveUserById(superuserId).queue(user -> user.openPrivateChannel()
                        .flatMap(channel -> channel.sendMessage("**BobBot is online**").setEmbeds(embed))
                        .queue(
                                success -> LOGGER.info("Sent ready notification to superuser {}", superuserId),
                                error -> LOGGER.warn("Failed to send ready notification to superuser {}", superuserId, error)
                        ),
                error -> LOGGER.warn("Failed to retrieve superuser {} for ready notification", superuserId, error));
    }
}
