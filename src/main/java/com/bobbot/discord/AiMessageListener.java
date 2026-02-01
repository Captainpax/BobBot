package com.bobbot.discord;

import com.bobbot.service.AiService;
import com.bobbot.service.HealthService;
import com.bobbot.service.PaginationService;
import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
    private final PaginationService paginationService;

    public AiMessageListener(JsonStorage storage, AiService aiService, HealthService healthService, PaginationService paginationService) {
        this.storage = storage;
        this.aiService = aiService;
        this.healthService = healthService;
        this.paginationService = paginationService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }

        BotSettings settings = storage.loadSettings();
        String bobsChatId = settings.getBobsChatChannelId();

        String content = event.getMessage().getContentDisplay();
        if (content.isBlank()) {
            return;
        }

        boolean inBobsChat = bobsChatId != null && event.getChannel().getId().equals(bobsChatId);
        boolean mentioned = event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());
        
        boolean isReplyToMe = false;
        String referencedContent = null;
        String referencedMessageId = null;
        if (event.getMessage().getReferencedMessage() != null) {
            net.dv8tion.jda.api.entities.Message ref = event.getMessage().getReferencedMessage();
            if (ref.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                isReplyToMe = true;
                referencedContent = ref.getContentDisplay();
                referencedMessageId = ref.getId();
            }
        }

        // Handle on-demand thought request
        boolean isAuthorAdmin = event.getMember() != null ? healthService.isAdmin(event.getMember()) : healthService.isAdmin(event.getAuthor().getId());
        if (isReplyToMe && referencedMessageId != null && isAuthorAdmin) {
            String lower = content.toLowerCase();
            if (lower.contains("thoughts") || lower.contains("breakdown") || lower.contains("thinking") || lower.contains("tools")) {
                HealthService.AiExecutionLog log = healthService.getCachedThought(referencedMessageId);
                if (log != null) {
                    event.getJDA().retrieveUserById(log.authorId()).queue(originalAuthor -> {
                        healthService.sendThoughtsToUser(event.getJDA(), event.getAuthor(), originalAuthor, log.prompt(), log.thinking());
                    }, error -> {
                        healthService.sendThoughtsToUser(event.getJDA(), event.getAuthor(), event.getAuthor(), log.prompt(), log.thinking());
                    });
                    event.getMessage().reply("Sent my thoughts on that one to your DMs, mate. Don't go sharing my trade secrets!").queue();
                } else {
                    event.getMessage().reply("I don't have any recorded thoughts for that one, mate. I probably wasn't using any tools or my memory is a bit foggy (cache missed).").queue();
                }
                return;
            }
        }

        // Trigger conditions:
        // 1. Mentioned anywhere
        // 2. Reply to Bob anywhere
        // 3. Keyword "Bob" in Bob's Chat
        boolean shouldRespond = mentioned || isReplyToMe || (inBobsChat && content.toLowerCase().contains("bob"));

        if (!shouldRespond) {
            return;
        }

        event.getChannel().sendTyping().queue();
        String loadingMsg = AiService.getRandomLoadingMessage();

        final String finalReferencedContent = referencedContent;
        event.getMessage().reply(loadingMsg).queue(sentMsg -> {
            CompletableFuture.runAsync(() -> {
                try {
                    String guildId = event.isFromGuild() ? event.getGuild().getId() : null;
                    AiService.AiResult result = aiService.generateResponse(content, event.getAuthor().getId(), event.getChannel().getId(), guildId, finalReferencedContent);

                    String replyContent = result.content();
                    
                    if (result.paginationSessionId() != null) {
                        PaginationService.PagedSession session = paginationService.getSession(result.paginationSessionId());
                        if (session != null) {
                            paginationService.updateSessionResponse(result.paginationSessionId(), replyContent);
                            PaginationService.PagedSession updatedSession = paginationService.getSession(result.paginationSessionId());

                            sentMsg.editMessage(event.getAuthor().getAsMention())
                                    .setEmbeds(createPaginationEmbed(event.getJDA(), replyContent, updatedSession))
                                    .setComponents(createPaginationButtons(result.paginationSessionId(), updatedSession))
                                    .queue();
                        } else {
                            MessageEmbed embed = DiscordFormatUtils.createBobEmbed(event.getJDA())
                                    .setDescription(replyContent.length() > 4000 ? replyContent.substring(0, 3997) + "..." : replyContent)
                                    .build();
                            sentMsg.editMessage(event.getAuthor().getAsMention()).setEmbeds(embed).queue();
                        }
                    } else {
                        MessageEmbed embed = DiscordFormatUtils.createBobEmbed(event.getJDA())
                                .setDescription(replyContent.length() > 4000 ? replyContent.substring(0, 3997) + "..." : replyContent)
                                .build();
                        sentMsg.editMessage(event.getAuthor().getAsMention()).setEmbeds(embed).queue();
                    }

                    // Cache thoughts for on-demand requests
                    healthService.cacheThought(sentMsg.getId(), content, result.thinking(), event.getAuthor().getId());

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

    public static MessageEmbed createPaginationEmbed(JDA jda, String naturalResponse, PaginationService.PagedSession session) {
        EmbedBuilder eb = DiscordFormatUtils.createBobEmbed(jda);
        if (naturalResponse != null && !naturalResponse.isBlank()) {
            eb.setDescription(naturalResponse);
        }
        String pageInfo = String.format("Page %d/%d", session.currentPage() + 1, session.pages().size());
        eb.setTitle("📊 " + session.title() + " (" + pageInfo + ")");
        eb.appendDescription("\n\n" + session.pages().get(session.currentPage()));
        return eb.build();
    }

    public static List<ActionRow> createPaginationButtons(String sessionId, PaginationService.PagedSession session) {
        List<Button> buttons = new ArrayList<>();
        int totalPages = session.pages().size();
        int current = session.currentPage();

        buttons.add(Button.primary("ai:page:prev:" + sessionId, "Previous")
                .withEmoji(Emoji.fromFormatted("⬅️"))
                .withDisabled(current == 0));
        
        buttons.add(Button.secondary("ai:page:info:" + sessionId, String.format("Page %d/%d", current + 1, totalPages))
                .withDisabled(true));

        buttons.add(Button.primary("ai:page:next:" + sessionId, "Next")
                .withEmoji(Emoji.fromFormatted("➡️"))
                .withDisabled(current >= totalPages - 1));

        return List.of(ActionRow.of(buttons));
    }
}
