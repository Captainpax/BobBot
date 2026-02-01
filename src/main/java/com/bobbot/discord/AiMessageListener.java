package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
import com.bobbot.service.AiService;
import com.bobbot.service.HealthService;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Listener that handles AI chat in the designated channel.
 */
public class AiMessageListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiMessageListener.class);
    private final JsonStorage storage;
    private final AiService aiService;
    private final HealthService healthService;

    public AiMessageListener(JsonStorage storage, AiService aiService, HealthService healthService) {
        this.storage = storage;
        this.aiService = aiService;
        this.healthService = healthService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        BotSettings settings = storage.loadSettings();
        String bobsChatId = settings.getBobsChatChannelId();

        if (bobsChatId == null || bobsChatId.isBlank()) {
            return;
        }

        if (event.getChannel().getId().equals(bobsChatId)) {
            String content = event.getMessage().getContentDisplay();
            if (content.isBlank()) {
                return;
            }

            boolean mentioned = event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());
            boolean containsBob = content.toLowerCase().contains("bob");

            if (!mentioned && !containsBob) {
                return;
            }

            event.getChannel().sendTyping().queue();
            String loadingMsg = AiService.getRandomLoadingMessage();

            event.getMessage().reply(loadingMsg).queue(sentMsg -> {
                CompletableFuture.runAsync(() -> {
                    try {
                        String guildId = event.isFromGuild() ? event.getGuild().getId() : null;
                        AiService.AiResult result = aiService.generateResponse(content, event.getAuthor().getId(), event.getChannel().getId(), guildId);

                        String replyContent = result.content();
                        if (replyContent.length() > 1990) {
                            replyContent = replyContent.substring(0, 1987) + "...";
                        }
                        sentMsg.editMessage(replyContent).queue();

                        if (!result.thinking().isBlank()) {
                            healthService.sendThinkingLog(event.getJDA(), event.getAuthor(), content, result.thinking());
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to generate AI response", e);
                        sentMsg.editMessage("Sorry, I'm having trouble thinking right now. Blame it on the server lag.").queue();
                    }
                });
            });
        }
    }
}
