package com.bobbot.discord;

import com.bobbot.service.HealthService;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Locale;

/**
 * Responds to mention-based health checks.
 */
public class MentionHealthListener extends ListenerAdapter {
    private final HealthService healthService;

    /**
     * Create a new listener with the health service.
     *
     * @param healthService health service
     */
    public MentionHealthListener(HealthService healthService) {
        this.healthService = healthService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) {
            return;
        }
        if (event.isFromGuild() && !event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser())) {
            return;
        }
        String content = normalizedContent(event).toLowerCase(Locale.ROOT);
        if (!content.contains("you online") && !content.contains("online") && !content.contains("health")) {
            return;
        }

        MessageEmbed embed = healthService.buildHealthEmbed(event.getJDA(), "all");
        String prefix = healthService.isOnline(event.getJDA()) ? "Yes, I'm here!" : "Not quite, but I'm trying!";
        event.getChannel().sendMessage(prefix).setEmbeds(embed).queue();
    }

    private String normalizedContent(MessageReceivedEvent event) {
        String content = event.getMessage().getContentRaw();
        if (content != null && !content.isBlank()) {
            return content;
        }
        content = event.getMessage().getContentDisplay();
        if (content != null && !content.isBlank()) {
            return content;
        }
        content = event.getMessage().getContentStripped();
        return content == null ? "" : content;
    }
}
