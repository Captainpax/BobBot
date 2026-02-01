package com.bobbot.service;

import com.bobbot.osrs.OsrsApiClient;
import com.bobbot.osrs.SkillStat;
import com.bobbot.storage.BotSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.bobbot.storage.JsonStorage;
import com.bobbot.util.FormatUtils;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.data.message.AiMessage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service that handles communication with a local AI API using LangChain4j.
 */
public class AiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiService.class);
    
    public static final String[] LOADING_MESSAGES = {
            "One moment, just checking the G.E. prices...",
            "Consulting the Wise Old Man... hold your capes.",
            "Just finishing this herb run, I'll be with you in a tick!",
            "Hold on, I've got a random event to deal with... sandwich lady is persistent.",
            "Checking the highscores... don't expect too much.",
            "RNG is taking its time today, one second...",
            "Lagging a bit, must be a world DC coming. Hang on...",
            "Let me just bank these logs first...",
            "One sec, trying to find my spade. It's always in the last place you look!",
            "Just hopping worlds to find a quiet spot to think...",
            "Hold on, need to drink a dose of prayer pot...",
            "Panic selling my bank to afford the latest meta, be right with you...",
            "Just getting a quick trim for my armor, should be done soon...",
            "Buying GF 10k, hang on...",
            "Getting sit by Zulrah, one moment...",
            "Drinking a dose of stamina pot for the long thinking sprint...",
            "Looking for a world that isn't full of bots...",
            "Adjusting my mouse sensitivity for some tick-perfect responses..."
    };

    public static String getRandomLoadingMessage() {
        return LOADING_MESSAGES[ThreadLocalRandom.current().nextInt(LOADING_MESSAGES.length)];
    }

    private final JsonStorage storage;
    private final Path dataDir;
    private final PriceService priceService;
    private final LevelUpService levelUpService;
    private final LeaderboardService leaderboardService;
    private final HealthService healthService;
    private final PaginationService paginationService;
    private final WikiService wikiService;
    private final OsrsApiClient apiClient;

    private JDA jda;
    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();
    private ChatLanguageModel cachedModel;
    private Assistant assistantProxy;
    private String lastUrl;
    private String lastModelName;

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_GUILD_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> LAST_PAGINATION_ID = new ThreadLocal<>();
    private static final ThreadLocal<StringBuilder> THINKING_ACCUMULATOR = ThreadLocal.withInitial(StringBuilder::new);
    private static final ThreadLocal<Map<String, Integer>> TOOL_CALL_MAP = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Integer> TOOL_CALL_COUNT = ThreadLocal.withInitial(() -> 0);

    private static class LoopDetectedException extends RuntimeException {
        public LoopDetectedException(String message) {
            super(message);
        }
    }

    private final ChatModelListener thinkingListener = new ChatModelListener() {
        @Override
        public void onResponse(ChatModelResponseContext context) {
            if (context == null || context.response() == null) return;
            AiMessage aiMessage = context.response().aiMessage();
            if (aiMessage == null) return;
            
            // 1. Try to capture reasoning via reflection (LangChain4j 0.35+)
            try {
                java.lang.reflect.Method reasoningMethod = aiMessage.getClass().getMethod("reasoning");
                String reasoning = (String) reasoningMethod.invoke(aiMessage);
                if (reasoning != null && !reasoning.isBlank()) {
                    THINKING_ACCUMULATOR.get().append(reasoning.trim()).append("\n");
                }
            } catch (Exception ignored) {
                // Method might not exist in this version or named differently
                try {
                    java.lang.reflect.Method reasoningContentMethod = aiMessage.getClass().getMethod("reasoningContent");
                    String reasoning = (String) reasoningContentMethod.invoke(aiMessage);
                    if (reasoning != null && !reasoning.isBlank()) {
                        THINKING_ACCUMULATOR.get().append(reasoning.trim()).append("\n");
                    }
                } catch (Exception ignored2) {}
            }

            // 2. Capture tool calls and check for loops
            if (aiMessage.hasToolExecutionRequests() && aiMessage.toolExecutionRequests() != null) {
                aiMessage.toolExecutionRequests().forEach(request -> {
                    if (request == null) return;
                    
                    // Increment total count
                    int total = TOOL_CALL_COUNT.get() + 1;
                    TOOL_CALL_COUNT.set(total);
                    if (total > 5) {
                        throw new LoopDetectedException("I've tried too many tools (5) to answer this. I'm getting confused, mate!");
                    }

                    String name = request.name() != null ? request.name() : "unknown";
                    String args = request.arguments() != null ? request.arguments() : "";
                    String key = name + ":" + args;
                    int count = TOOL_CALL_MAP.get().merge(key, 1, Integer::sum);
                    if (count > 2) {
                        throw new LoopDetectedException("Detected repetitive tool call: " + key);
                    }

                    THINKING_ACCUMULATOR.get()
                        .append("[Tool Call] ")
                        .append(name)
                        .append(" with args: ")
                        .append(args)
                        .append("\n");
                });
            }

            // 3. Fallback: Extract from text if it contains <think> tags
            String text = aiMessage.text();
            if (text != null && text.contains("<think>")) {
                int start = text.indexOf("<think>");
                while (start != -1) {
                    int end = text.indexOf("</think>", start);
                    if (end != -1) {
                        String thinking = text.substring(start + 7, end).trim();
                        if (!thinking.isEmpty()) {
                            THINKING_ACCUMULATOR.get().append(thinking).append("\n");
                        }
                        start = text.indexOf("<think>", end);
                    } else {
                        String thinking = text.substring(start + 7).trim();
                        if (!thinking.isEmpty()) {
                            THINKING_ACCUMULATOR.get().append(thinking).append("\n");
                        }
                        break;
                    }
                }
            }
        }

        @Override
        public void onError(dev.langchain4j.model.chat.listener.ChatModelErrorContext context) {
            if (context != null && context.error() != null) {
                LOGGER.warn("AI Model Error: {}", context.error().getMessage());
                THINKING_ACCUMULATOR.get().append("[Model Error] ").append(context.error().getMessage()).append("\n");
            }
        }

        @Override
        public void onRequest(ChatModelRequestContext context) {
            if (context == null || context.request() == null || context.request().messages() == null) return;
            context.request().messages().forEach(message -> {
                if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage toolResult) {
                    THINKING_ACCUMULATOR.get()
                        .append("[Tool Result] ")
                        .append(toolResult.toolName() != null ? toolResult.toolName() : "unknown")
                        .append(": ")
                        .append(toolResult.text() != null ? toolResult.text() : "null")
                        .append("\n");
                }
            });
        }
    };

    public AiService(JsonStorage storage, Path dataDir, PriceService priceService, LevelUpService levelUpService, LeaderboardService leaderboardService, HealthService healthService, PaginationService paginationService, WikiService wikiService, OsrsApiClient apiClient) {
        this.storage = storage;
        this.dataDir = dataDir;
        this.priceService = priceService;
        this.levelUpService = levelUpService;
        this.leaderboardService = leaderboardService;
        this.healthService = healthService;
        this.paginationService = paginationService;
        this.wikiService = wikiService;
        this.apiClient = apiClient;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    public record AiResult(String thinking, String content, String paginationSessionId) {}

    interface Assistant {
        @SystemMessage("{{systemPrompt}}")
        String chat(@MemoryId String memoryId, @V("systemPrompt") String systemPrompt, @UserMessage String userMessage);
    }

    public class BobTools {
        @Tool("Get the current Grand Exchange price for an OSRS item")
        public String get_item_price(@P("item_name") String item_name) {
            try {
                return priceService.lookupPrice(item_name)
                        .map(info -> String.format("Item: %s, High: %s, Low: %s",
                                info.itemName(),
                                info.price() != null && info.price().high() != null ? info.price().high() + " GP" : "unknown",
                                info.price() != null && info.price().low() != null ? info.price().low() + " GP" : "unknown"))
                        .orElse("I couldn't find an item named '" + item_name + "'. Maybe check the spelling or check if it's tradeable?");
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_item_price tool", e);
                return "Error fetching price for '" + item_name + "': " + e.getMessage();
            }
        }

        @Tool("Get the OSRS stats (all skills) for the user who is speaking")
        public String get_my_stats() {
            String userId = CURRENT_USER_ID.get();
            if (userId == null) return "Error: No user context found.";
            try {
                var record = levelUpService.refreshPlayer(userId);
                if (record == null) return "You haven't linked your OSRS account yet! Use /os link to get started.";
                var stats = levelUpService.fetchSkillStats(record.getUsername());
                return formatStats(record.getUsername(), stats);
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_my_stats tool", e);
                return "Error fetching your stats: " + e.getMessage();
            }
        }

        @Tool("Get the level and XP for a specific skill for the user who is speaking")
        public String get_my_skill(@P("skill_name") String skillName) {
            String userId = CURRENT_USER_ID.get();
            if (userId == null) return "Error: No user context found.";
            try {
                var record = levelUpService.refreshPlayer(userId);
                if (record == null) return "You haven't linked your OSRS account yet! Use /os link to get started.";
                return get_player_skill(record.getUsername(), skillName);
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_my_skill tool", e);
                return "Error fetching your " + skillName + " skill: " + e.getMessage();
            }
        }

        @Tool("Get the OSRS stats (all skills) for a specific player by their OSRS username. OSRS usernames are max 12 characters. Do NOT use this for NPCs, lore characters (like Wise Old Man), your own stats, skill names, or general chat.")
        public String get_player_stats(@P("username") String username) {
            if (username != null && (username.equalsIgnoreCase("bob") || username.equalsIgnoreCase("wise old man") || username.equalsIgnoreCase("wise_old_man"))) {
                return "That character is part of OSRS lore, mate! They aren't on the hiscore scrolls. Try checking a real player's stats instead.";
            }
            if (!FormatUtils.isValidOsrsUsername(username)) {
                return String.format("'%s' does not look like a valid OSRS username, mate. Usernames are max 12 characters and only have letters, numbers, spaces, underscores, or hyphens. If you're just chatting with me, don't use this tool.", username);
            }
            if (com.bobbot.osrs.Skill.findByName(username).isPresent()) {
                return String.format("'%s' is an OSRS skill, not a player name. If you meant to check YOUR OWN stats, use 'get_my_stats'. If you meant another player, use their OSRS username.", username);
            }
            try {
                var stats = levelUpService.fetchSkillStats(username);
                return formatStats(username, stats);
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_player_stats tool", e);
                String msg = e.getMessage();
                if (msg != null && msg.contains("not found on OSRS hiscores")) {
                    return String.format("Player '%s' not found on OSRS hiscores. They might be unranked, have changed their name, or it might not be a valid player name. I've double checked the hiscore scrolls and they aren't there. Do NOT keep trying to look up this exact name as a player.", username);
                }
                return "Error fetching stats for " + username + ": " + e.getMessage();
            }
        }

        @Tool("Get the level and XP for a specific skill for a specific player by their OSRS username. OSRS usernames are max 12 characters. Do NOT use this for NPCs, lore characters, your own stats, or general chat.")
        public String get_player_skill(@P("username") String username, @P("skill_name") String skillName) {
            if (username != null && (username.equalsIgnoreCase("bob") || username.equalsIgnoreCase("wise old man") || username.equalsIgnoreCase("wise_old_man"))) {
                return "That character is part of OSRS lore, mate! They aren't on the hiscore scrolls. Try checking a real player's level instead.";
            }
            if (!FormatUtils.isValidOsrsUsername(username)) {
                return String.format("'%s' does not look like a valid OSRS username, mate. Usernames are max 12 characters and only have letters, numbers, spaces, underscores, or hyphens. If you're just chatting with me, don't use this tool.", username);
            }
            if (com.bobbot.osrs.Skill.findByName(username).isPresent()) {
                return String.format("'%s' is an OSRS skill, not a player name. If you meant to check YOUR OWN level, use 'get_my_skill' and specify '%s' as the skill.", username, skillName);
            }
            try {
                var skillOpt = com.bobbot.osrs.Skill.findByName(skillName);
                if (skillOpt.isEmpty()) {
                    return "Skill '" + skillName + "' not found. Valid skills: " + 
                            java.util.Arrays.stream(com.bobbot.osrs.Skill.values()).map(com.bobbot.osrs.Skill::displayName).collect(java.util.stream.Collectors.joining(", "));
                }
                
                com.bobbot.osrs.Skill finalSkill = skillOpt.get();
                var stats = levelUpService.fetchSkillStats(username);
                return stats.stream()
                        .filter(s -> s.skill() == finalSkill)
                        .findFirst()
                        .map(s -> {
                            String base = String.format("Player: %s, Skill: %s, Level: %d, XP: %,d", username, s.skill().displayName(), s.level(), s.xp());
                            String wikiUrl = wikiService.getWikiUrl(s.skill());
                            String summary = wikiService.getSkillSummary(s.skill()).orElse("");
                            return base + "\nWiki: " + wikiUrl + (summary.isEmpty() ? "" : "\nSummary: " + summary);
                        })
                        .orElse("Player '" + username + "' is unranked in " + finalSkill.displayName() + " (they have less than 15 XP or aren't on hiscores). I've checked the official hiscores and they aren't listed for this skill. Do NOT keep retrying this exact lookup.");
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_player_skill tool", e);
                String msg = e.getMessage();
                if (msg != null && msg.contains("not found on OSRS hiscores")) {
                    return String.format("Player '%s' not found on OSRS hiscores. They might be unranked, have changed their name, or it might not be a valid player name. I've double checked the hiscore scrolls and they aren't there. Do NOT keep trying to look up this exact name as a player.", username);
                }
                return "Error fetching " + skillName + " for " + username + ": " + e.getMessage();
            }
        }

        @Tool("Get the OSRS username linked to the current Discord user")
        public String get_my_linked_username() {
            String userId = CURRENT_USER_ID.get();
            if (userId == null) return "Error: No user context found.";
            var players = storage.loadPlayers();
            var record = players.get(userId);
            if (record == null) return "You don't have an OSRS account linked.";
            return "Your linked OSRS username is: " + record.getUsername();
        }

        @Tool("Get the current OSRS leaderboard showing all linked players and their total levels")
        public String get_leaderboard() {
            try {
                return leaderboardService.getLeaderboardSummary();
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_leaderboard tool", e);
                return "Error fetching leaderboard: " + e.getMessage();
            }
        }

        @Tool("Get the OSRS stats for a Discord user mentioned by name, nickname, or @mention")
        public String get_stats_by_discord_name(@P("name_or_mention") String nameOrMention) {
            if (jda == null) return "Error: JDA not initialized.";
            String guildId = CURRENT_GUILD_ID.get();
            
            // Clean up name/mention
            String query = nameOrMention.replace("@", "").replace("<", "").replace(">", "").replace("!", "").trim();

            if (query.equalsIgnoreCase("bob") || query.equalsIgnoreCase("wise old man") || query.equalsIgnoreCase("wise_old_man")) {
                return "That character is part of OSRS lore, mate! They aren't on the hiscore scrolls. Try checking a real player's stats instead.";
            }
            
            // 1. Try to find by ID if it's a mention
            if (query.matches("\\d+")) {
                var record = storage.loadPlayers().get(query);
                if (record != null) {
                    try {
                        var stats = levelUpService.fetchSkillStats(record.getUsername());
                        return formatStats(record.getUsername(), stats);
                    } catch (Exception e) {
                        return "Error fetching stats for linked account " + record.getUsername() + ": " + e.getMessage();
                    }
                }
            }

            // 2. Try to find by name/nickname in the guild
            if (guildId != null) {
                Guild guild = jda.getGuildById(guildId);
                if (guild != null) {
                    List<Member> members = guild.getMembersByEffectiveName(query, true);
                    if (members.isEmpty()) {
                        members = guild.getMembersByName(query, true);
                    }
                    if (members.isEmpty()) {
                        members = guild.getMembersByNickname(query, true);
                    }

                    if (!members.isEmpty()) {
                        Member member = members.get(0);
                        var record = storage.loadPlayers().get(member.getId());
                        if (record != null) {
                            try {
                                var stats = levelUpService.fetchSkillStats(record.getUsername());
                                return "Found linked account for " + member.getEffectiveName() + " (" + record.getUsername() + "):\n" + formatStats(record.getUsername(), stats);
                            } catch (Exception e) {
                                return "Error fetching stats for linked account " + record.getUsername() + ": " + e.getMessage();
                            }
                        }
                        return "I found Discord user " + member.getEffectiveName() + ", but they haven't linked an OSRS account yet.";
                    }
                }
            }

            // 3. Fallback: maybe it's just an OSRS username?
            return get_player_stats(query);
        }

        @Tool("Get the current health and power state of the bot including uptime, status, and basic connectivity")
        public String get_bot_health() {
            if (jda == null) return "Error: JDA not initialized.";
            return healthService.buildHealthReport(jda);
        }

        @Tool("Get the current AI assistant configuration including API URL and model name")
        public String get_ai_config() {
            BotSettings settings = storage.loadSettings();
            return String.format("AI Configuration:\n- URL: %s\n- Model: %s",
                    settings.getAiUrl() != null ? settings.getAiUrl() : "not set",
                    settings.getAiModel() != null ? settings.getAiModel() : "not set");
        }

        @Tool("Get the current personality profile of Bob")
        public String get_ai_personality() {
            String p = loadPersonality();
            return p.isEmpty() ? "No custom personality loaded. I'm using my default OSRS veteran persona." : p;
        }

        @Tool("Update the AI model being used (Requires Admin)")
        public String update_ai_model(@P("model_name") String modelName) {
            String userId = CURRENT_USER_ID.get();
            if (!healthService.isAdmin(userId)) return "Sorry mate, only admins can change my brain settings. You don't have the requirements for this quest.";
            updateAiModel(modelName);
            return "Alright, I'll try using the " + modelName + " model from now on. Hope it's got more XP than the last one!";
        }

        @Tool("Update the AI API URL (Requires Admin)")
        public String update_ai_url(@P("url") String url) {
            String userId = CURRENT_USER_ID.get();
            if (!healthService.isAdmin(userId)) return "Nice try, but you need higher levels to change my connection settings. Admins only!";
            updateAiUrl(url);
            return "Connection updated to " + url + ". Hope the ping is better over there.";
        }

        @Tool("Reboot the bot application. The Docker container will stay up, but the JAR will restart. (Requires Admin)")
        public String reboot_bot() {
            String userId = CURRENT_USER_ID.get();
            if (!healthService.isAdmin(userId)) return "You don't have the Agility level to pull that lever. Admins only!";

            new Thread(() -> {
                try {
                    Thread.sleep(1000); // Give Bob time to say goodbye
                    if (jda != null) {
                        healthService.announceToBobsChatBlocking(jda, "Logging out to hop worlds. See you in a tick! (Rebooting...)");
                        jda.shutdown();
                    }
                    System.exit(2);
                } catch (Exception e) {
                    LOGGER.error("Failed to reboot via tool", e);
                }
            }).start();

            return "Rebooting the bot now. I'll be back at the GE in a tick!";
        }

        @Tool("Stop the bot and the Docker container. (Requires Admin)")
        public String stop_bot() {
            String userId = CURRENT_USER_ID.get();
            if (!healthService.isAdmin(userId)) return "Sit. Only admins can shut me down.";

            new Thread(() -> {
                try {
                    Thread.sleep(1000); // Give Bob time to say goodbye
                    if (jda != null) {
                        healthService.announceToBobsChatBlocking(jda, "World DC incoming! Bracing for impact... (Shutting down...)");
                        jda.shutdown();
                    }
                    System.exit(0);
                } catch (Exception e) {
                    LOGGER.error("Failed to stop via tool", e);
                }
            }).start();

            return "Stopping the bot. Logging out... see you in Lumbridge!";
        }

        @Tool("Display a long list of items or information in a paginated view with buttons. Use this when you have a lot of data to show (e.g. many player stats, price lists, or search results).")
        public String display_paginated_report(@P("title") String title, @P("items") List<String> items) {
            if (items == null || items.isEmpty()) return "Nothing to show in the report, mate.";
            String sessionId = paginationService.createSession(title, "", items, 10);
            LAST_PAGINATION_ID.set(sessionId);
            return "Paginated report created with title '" + title + "' and " + items.size() + " items. I will attach the interactive buttons to my response automatically. Just tell the user you've generated the report and summarize what's in it.";
        }

        @Tool("Compare the current Grand Exchange prices of two OSRS items")
        public String compare_prices(@P("item1") String item1, @P("item2") String item2) {
            try {
                var info1Opt = priceService.lookupPrice(item1);
                var info2Opt = priceService.lookupPrice(item2);

                if (info1Opt.isEmpty()) return "I couldn't find an item named '" + item1 + "'.";
                if (info2Opt.isEmpty()) return "I couldn't find an item named '" + item2 + "'.";

                var info1 = info1Opt.get();
                var info2 = info2Opt.get();

                if (info1.price() == null || info2.price() == null) return "Price data is missing for one of those items.";

                long p1 = info1.price().high() != null ? info1.price().high() : info1.price().low();
                long p2 = info2.price().high() != null ? info2.price().high() : info2.price().low();
                long diff = p1 - p2;

                return String.format("Comparison: %s is %,d GP, %s is %,d GP. Difference: %s%,d GP.",
                        info1.itemName(), p1,
                        info2.itemName(), p2,
                        (diff > 0 ? "+" : ""), diff);
            } catch (Exception e) {
                return "Error comparing prices: " + e.getMessage();
            }
        }

        @Tool("Compare two of the user's own OSRS skills (level and XP)")
        public String compare_my_skills(@P("skill1") String skill1, @P("skill2") String skill2) {
            String userId = CURRENT_USER_ID.get();
            if (userId == null) return "Error: No user context found.";
            try {
                var record = levelUpService.refreshPlayer(userId);
                if (record == null) return "You haven't linked your OSRS account yet!";

                var stats = levelUpService.fetchSkillStats(record.getUsername());
                var s1Opt = com.bobbot.osrs.Skill.findByName(skill1);
                var s2Opt = com.bobbot.osrs.Skill.findByName(skill2);

                if (s1Opt.isEmpty()) return "I couldn't find the skill '" + skill1 + "'.";
                if (s2Opt.isEmpty()) return "I couldn't find the skill '" + skill2 + "'.";

                com.bobbot.osrs.Skill skill1Enum = s1Opt.get();
                com.bobbot.osrs.Skill skill2Enum = s2Opt.get();

                var s1 = stats.stream().filter(s -> s.skill() == skill1Enum).findFirst().orElse(null);
                var s2 = stats.stream().filter(s -> s.skill() == skill2Enum).findFirst().orElse(null);

                if (s1 == null) return "You are unranked in " + skill1Enum.displayName() + ".";
                if (s2 == null) return "You are unranked in " + skill2Enum.displayName() + ".";

                int levelDiff = s1.level() - s2.level();
                long xpDiff = s1.xp() - s2.xp();

                return String.format("Comparison for %s: %s is Level %d (%,d XP), %s is Level %d (%,d XP). Level Diff: %s%d, XP Diff: %s%,d.",
                        record.getUsername(),
                        s1.skill().displayName(), s1.level(), s1.xp(),
                        s2.skill().displayName(), s2.level(), s2.xp(),
                        (levelDiff > 0 ? "+" : ""), levelDiff,
                        (xpDiff > 0 ? "+" : ""), xpDiff);
            } catch (Exception e) {
                return "Error comparing skills: " + e.getMessage();
            }
        }

        @Tool("Get detailed information about an OSRS quest by its name (e.g., 'Dragon Slayer')")
        public String get_quest_info(@P("quest_name") String quest_name) {
            try {
                return apiClient.fetchQuestInfo(quest_name)
                        .map(node -> {
                            String name = node.path("name").asText();
                            String wikiUrl = wikiService.getWikiUrl(name);
                            StringBuilder sb = new StringBuilder();
                            sb.append("Quest: ").append(name).append("\n");
                            sb.append("Difficulty: ").append(node.path("difficulty").asText()).append("\n");
                            sb.append("Length: ").append(node.path("length").asText()).append("\n");
                            sb.append("Requirements: ").append(node.path("requirements").toString()).append("\n");
                            sb.append("Rewards: ").append(node.path("rewards").toString()).append("\n");
                            sb.append("Wiki: ").append(wikiUrl);
                            return sb.toString();
                        })
                        .orElse("I couldn't find any info on a quest named '" + quest_name + "'. Maybe check the spelling, mate?");
            } catch (Exception e) {
                return "Error fetching quest info: " + e.getMessage();
            }
        }

        @Tool("Get the list of possible slayer tasks for a specific slayer master (Duradel, Nieve, Konar)")
        public String get_slayer_tasks(@P("master_name") String master_name) {
            try {
                List<JsonNode> tasks = apiClient.fetchSlayerTasks(master_name);
                if (tasks.isEmpty()) {
                    return "I couldn't find any tasks for " + master_name + ". I only know about Duradel, Nieve, and Konar, mate.";
                }
                List<String> taskNames = tasks.stream()
                        .map(t -> t.path("name").asText())
                        .distinct()
                        .sorted()
                        .toList();
                
                return "Slayer tasks for " + master_name + ":\n" + String.join(", ", taskNames);
            } catch (Exception e) {
                return "Error fetching slayer tasks: " + e.getMessage();
            }
        }

        @Tool("Roll for a pet to see if the user got lucky. Purely for fun/roleplay. Do NOT use this if the user is just asking about boss levels or stats.")
        public String roll_for_pet(@P("boss_name") String bossName) {
            int roll = ThreadLocalRandom.current().nextInt(3000) + 1;
            if (roll == 1) {
                return "OH MY GUTHIX! You just got the " + bossName + " pet! Gz, mate! Huge spoon! 🥄";
            } else {
                return "Bad luck, mate. You rolled a " + roll + " / 3000. No pet for you this time. Back to the grind!";
            }
        }
    }

    /**
     * Generate a response from the AI for the given prompt.
     *
     * @param prompt user input
     * @param userId Discord user ID of the sender
     * @param channelId Discord channel ID where the message was sent
     * @param guildId Discord guild ID (optional)
     * @param referencedContent content of the message being replied to (optional)
     * @return AI response
     */
    public AiResult generateResponse(String prompt, String userId, String channelId, String guildId, String referencedContent) {
        BotSettings settings = storage.loadSettings();
        String url = settings.getAiUrl();
        String modelName = settings.getAiModel();

        if (url == null || url.isBlank()) {
            return new AiResult("", "AI URL is not configured. Use /admin ai url to set it up.", null);
        }

        if (modelName == null || modelName.isBlank()) {
            return new AiResult("", "AI model is not configured. Use /admin ai model to set it up.", null);
        }

        String userName = "unknown user";
        String userNickname = "none";
        String guildName = "Direct Message";
        String channelName = "unknown channel";
        String osrsUsername = "None";

        if (jda != null) {
            User user = jda.getUserById(userId);
            if (user != null) {
                userName = user.getName();
            }

            if (guildId != null) {
                Guild guild = jda.getGuildById(guildId);
                if (guild != null) {
                    guildName = guild.getName();
                    Member member = guild.getMemberById(userId);
                    if (member != null) {
                        userNickname = member.getEffectiveName();
                    }
                }
            }

            var channel = jda.getGuildChannelById(channelId);
            if (channel != null) {
                channelName = channel.getName();
            }
        }

        var playerRecord = storage.loadPlayers().get(userId);
        if (playerRecord != null) {
            osrsUsername = playerRecord.getUsername();
        }

        String personality = loadPersonality();
        String systemPrompt = "You are Bob, a seasoned Old School RuneScape (OSRS) veteran and helpful assistant.\n" +
                "You have access to tools to look up item prices, player stats, and the bot's health/configuration.\n" +
                "Always maintain your character and follow the tool usage guidelines.\n\n" +
                "CONTEXT INFORMATION:\n" +
                "- Current User: " + userName + " (Nickname: " + userNickname + ")\n" +
                "- Linked OSRS Name: " + osrsUsername + "\n" +
                "- Server: " + guildName + "\n" +
                "- Channel: " + channelName + "\n\n" +
                "INTENT DETECTION & CORE RULES:\n" +
                "1. CHAT/LORE/RP INTENT: If the user is greeting you, joking, talking about OSRS lore (NPCs like Wise Old Man, King Roald, Gods), or roleplaying, DO NOT use any tools. Respond in character with your veteran wit.\n" +
                "2. DATA LOOKUP INTENT: If the user explicitly asks for a price, a player's level/stats, quest info, or slayer tasks, use the appropriate tool.\n" +
                "3. UNCERTAINTY: If you aren't 100% sure if they want data or a joke, lean towards a character-driven chat response first.\n" +
                "4. NPCs ARE NOT PLAYERS: Do not attempt to look up stats for OSRS NPCs or bosses (e.g. Wise Old Man, Zulrah) using player tools.\n" +
                "5. NO LOOPS: If a tool fails once, do not keep trying the same thing. Blame RNG or lag and move on.\n\n" +
                "IMPORTANT:\n" +
                "- DO NOT use tools for simple greetings or general chat.\n" +
                "- If the user is just saying 'hi', 'how are you', or asking about you (Bob), respond in character without calling any tools.\n" +
                "- Do not repeat the same tool call if it already failed or returned the same info.";

        if (!personality.isEmpty()) {
            systemPrompt += "\n\nCORE GUIDELINES & PERSONALITY:\n" + personality;
        }

        try {
            CURRENT_USER_ID.set(userId);
            CURRENT_GUILD_ID.set(guildId);
            LAST_PAGINATION_ID.remove();
            TOOL_CALL_MAP.get().clear();
            TOOL_CALL_COUNT.set(0);

            // Lazy initialization of model and proxy
            if (cachedModel == null || assistantProxy == null || !url.equals(lastUrl) || !modelName.equals(lastModelName)) {
                cachedModel = OpenAiChatModel.builder()
                        .baseUrl(buildBaseUrl(url))
                        .apiKey("no-key")
                        .modelName(modelName)
                        .timeout(Duration.ofMinutes(2))
                        .listeners(java.util.List.of(thinkingListener))
                        .build();

                assistantProxy = AiServices.builder(Assistant.class)
                        .chatLanguageModel(cachedModel)
                        .chatMemoryProvider(memoryId -> memories.computeIfAbsent(memoryId.toString(), 
                                id -> MessageWindowChatMemory.withMaxMessages(20)))
                        .tools(new BobTools())
                        .build();
                
                lastUrl = url;
                lastModelName = modelName;
            }

            THINKING_ACCUMULATOR.get().setLength(0);
            String effectivePrompt = prompt;
            if (referencedContent != null && !referencedContent.isBlank()) {
                effectivePrompt = String.format("(Replying to: \"%s\")\n%s", referencedContent, prompt);
            }
            String response = assistantProxy.chat(channelId, systemPrompt, effectivePrompt);
            if (response == null) {
                return new AiResult(THINKING_ACCUMULATOR.get().toString().trim(), "I'm sorry, I'm drawing a blank right now. (Model returned no response)", null);
            }
            
            // Extract thinking from accumulator (collected by listener)
            String thinking = THINKING_ACCUMULATOR.get().toString().trim();
            
            // If accumulator is empty, try extracting from the final response as fallback
            if (thinking.isEmpty() && response.contains("<think>")) {
                int start = response.indexOf("<think>");
                int end = response.indexOf("</think>");
                if (end != -1) {
                    thinking = response.substring(start + 7, end).trim();
                } else {
                    thinking = response.substring(start + 7).trim();
                }
            }

            // Clean the content of think tags
            String cleanContent = response;
            while (cleanContent.contains("<think>")) {
                int start = cleanContent.indexOf("<think>");
                int end = cleanContent.indexOf("</think>", start);
                if (end != -1) {
                    cleanContent = (cleanContent.substring(0, start) + cleanContent.substring(end + 8)).trim();
                } else {
                    cleanContent = cleanContent.substring(0, start).trim();
                }
            }

            if (cleanContent.isEmpty() && !thinking.isEmpty()) {
                cleanContent = "I've thought about it, but I'm not sure how to put it into words. Could you try asking in a different way?";
            } else if (cleanContent.isEmpty()) {
                cleanContent = "I'm not sure how to respond to that. (Model returned no content)";
            }

            String paginationId = LAST_PAGINATION_ID.get();
            return new AiResult(thinking, cleanContent, paginationId);
        } catch (LoopDetectedException e) {
            String thinking = THINKING_ACCUMULATOR.get().toString().trim();
            LOGGER.warn("Custom loop detection triggered: {}. Thinking length: {}", e.getMessage(), thinking.length());
            return new AiResult(thinking, "I'm trying to do too many things at once! I got stuck in a loop trying to find that for you. Maybe try being a bit more specific or check your spelling, mate.", null);
        } catch (Exception e) {
            if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new AiResult("", "I was interrupted while thinking. Blame it on a world dc.", null);
            }
            
            String message = e.getMessage();
            String thinking = THINKING_ACCUMULATOR.get().toString().trim();
            if (message != null && message.contains("sequential tool executions")) {
                LOGGER.warn("AI exceeded tool execution limit: {}. Thinking length: {}", message, thinking.length());
                return new AiResult(thinking, "I'm trying to do too many things at once! I got stuck in a loop trying to find that for you. Maybe try being a bit more specific or check your spelling, mate.", null);
            }

            LOGGER.error("AI generation failed with LangChain4j", e);
            return new AiResult(thinking, "I'm sorry, but something went wrong while I was thinking: " + e.getMessage(), null);
        } finally {
            CURRENT_USER_ID.remove();
            CURRENT_GUILD_ID.remove();
            LAST_PAGINATION_ID.remove();
        }
    }

    private String buildBaseUrl(String url) {
        String base = url.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            LOGGER.info("AI URL '{}' missing scheme, prepending http://", base);
            base = "http://" + base;
        }
        if (base.endsWith("/v1/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        } else if (base.endsWith("/v1/")) {
            base = base.substring(0, base.length() - 1);
        } else if (base.endsWith("/v1")) {
            // keep it
        } else {
            if (base.endsWith("/")) {
                base += "v1";
            } else {
                base += "/v1";
            }
        }
        return base;
    }

    private String formatStats(String username, List<com.bobbot.osrs.SkillStat> stats) {
        if (stats.isEmpty()) return "No stats found for " + username;
        StringBuilder sb = new StringBuilder("Stats for ").append(username).append(":\n");
        for (var stat : stats) {
            sb.append(String.format("- %s: Level %d (%,d XP)\n", stat.skill().displayName(), stat.level(), stat.xp()));
        }
        return sb.toString();
    }

    public void updateAiUrl(String url) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withAiUrl(url));
    }

    public void updateAiModel(String model) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withAiModel(model));
    }

    public void savePersonality(String content) throws IOException {
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }
        Files.writeString(dataDir.resolve("personality.txt"), content);
    }

    public String loadPersonality() {
        // First check data directory (where uploaded files go)
        Path path = dataDir.resolve("personality.txt");
        if (!Files.exists(path)) {
            // Fallback to project root (default template)
            path = Path.of("personality.txt");
        }

        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to read personality.txt", e);
            return "";
        }
    }
}
