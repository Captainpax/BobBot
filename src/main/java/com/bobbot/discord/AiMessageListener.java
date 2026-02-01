package com.bobbot.discord;

import com.bobbot.service.AiService;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener that handles AI chat in the designated channel.
 */
public class AiMessageListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiMessageListener.class);
    private final JsonStorage storage;
    private final AiService aiService;

    public AiMessageListener(JsonStorage storage, AiService aiService) {
        this.storage = storage;
        this.aiService = aiService;
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
            try {
                String response = aiService.generateResponse(content);
                event.getMessage().reply(response).queue();
            } catch (Exception e) {
                LOGGER.error("Failed to generate AI response", e);
                event.getMessage().reply("Sorry, I'm having trouble thinking right now.").queue();
            }
        }
    }
}
