package com.bobbot.service;

import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
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
            "Hold on, need to drink a dose of prayer pot..."
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

    private JDA jda;
    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();
    private ChatLanguageModel cachedModel;
    private Assistant assistantProxy;
    private String lastUrl;
    private String lastModelName;

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_GUILD_ID = new ThreadLocal<>();
    private static final ThreadLocal<StringBuilder> THINKING_ACCUMULATOR = ThreadLocal.withInitial(StringBuilder::new);

    private final ChatModelListener thinkingListener = new ChatModelListener() {
        @Override
        public void onResponse(ChatModelResponseContext context) {
            AiMessage aiMessage = context.response().aiMessage();
            
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

            // 2. Fallback: Extract from text if it contains <think> tags
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
            // No thinking to capture on error
        }

        @Override
        public void onRequest(ChatModelRequestContext context) {
            // No thinking to capture on request
        }
    };

    public AiService(JsonStorage storage, Path dataDir, PriceService priceService, LevelUpService levelUpService, LeaderboardService leaderboardService, HealthService healthService) {
        this.storage = storage;
        this.dataDir = dataDir;
        this.priceService = priceService;
        this.levelUpService = levelUpService;
        this.leaderboardService = leaderboardService;
        this.healthService = healthService;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    public record AiResult(String thinking, String content) {}

    interface Assistant {
        @SystemMessage("{{systemPrompt}}")
        String chat(@MemoryId String memoryId, @V("systemPrompt") String systemPrompt, @UserMessage String userMessage);
    }

    public class BobTools {
        @Tool("Get the current Grand Exchange price for an OSRS item")
        public String get_item_price(String item_name) {
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
        public String get_my_skill(String skillName) {
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

        @Tool("Get the OSRS stats (all skills) for a specific player by their username")
        public String get_player_stats(String username) {
            try {
                var stats = levelUpService.fetchSkillStats(username);
                return formatStats(username, stats);
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_player_stats tool", e);
                return "Error fetching stats for " + username + ": " + e.getMessage();
            }
        }

        @Tool("Get the level and XP for a specific skill for a specific player by their username")
        public String get_player_skill(String username, String skillName) {
            try {
                String normalizedSkill = skillName.toLowerCase().trim();
                // Common aliases
                if (normalizedSkill.equals("wc")) normalizedSkill = "woodcutting";
                if (normalizedSkill.equals("rc")) normalizedSkill = "runecraft";
                if (normalizedSkill.equals("hp")) normalizedSkill = "hitpoints";
                if (normalizedSkill.equals("con")) normalizedSkill = "construction";
                if (normalizedSkill.equals("fm")) normalizedSkill = "firemaking";
                if (normalizedSkill.equals("herb")) normalizedSkill = "herblore";
                if (normalizedSkill.equals("agil")) normalizedSkill = "agility";
                if (normalizedSkill.equals("thiev")) normalizedSkill = "thieving";
                if (normalizedSkill.equals("slay")) normalizedSkill = "slayer";
                if (normalizedSkill.equals("farm")) normalizedSkill = "farming";
                if (normalizedSkill.equals("hunt")) normalizedSkill = "hunter";
                if (normalizedSkill.equals("str")) normalizedSkill = "strength";
                if (normalizedSkill.equals("att")) normalizedSkill = "attack";
                if (normalizedSkill.equals("def")) normalizedSkill = "defence";
                if (normalizedSkill.equals("pray")) normalizedSkill = "prayer";
                if (normalizedSkill.equals("mage")) normalizedSkill = "magic";
                if (normalizedSkill.equals("cook")) normalizedSkill = "cooking";
                if (normalizedSkill.equals("fish")) normalizedSkill = "fishing";
                if (normalizedSkill.equals("fletch")) normalizedSkill = "fletching";
                if (normalizedSkill.equals("smith")) normalizedSkill = "smithing";
                if (normalizedSkill.equals("mine")) normalizedSkill = "mining";
                if (normalizedSkill.equals("craft")) normalizedSkill = "crafting";

                final String finalSkill = normalizedSkill;
                var stats = levelUpService.fetchSkillStats(username);
                return stats.stream()
                        .filter(s -> s.skill().name().equalsIgnoreCase(finalSkill) || s.skill().displayName().equalsIgnoreCase(finalSkill))
                        .findFirst()
                        .map(s -> String.format("Player: %s, Skill: %s, Level: %d, XP: %,d", username, s.skill().displayName(), s.level(), s.xp()))
                        .orElse("Skill '" + skillName + "' not found. Valid skills: " + 
                                java.util.Arrays.stream(com.bobbot.osrs.Skill.values()).map(com.bobbot.osrs.Skill::displayName).collect(java.util.stream.Collectors.joining(", ")));
            } catch (Exception e) {
                if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return "Error: Request was interrupted.";
                }
                LOGGER.error("Error in get_player_skill tool", e);
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
        public String get_stats_by_discord_name(String nameOrMention) {
            if (jda == null) return "Error: JDA not initialized.";
            String guildId = CURRENT_GUILD_ID.get();
            
            // Clean up name/mention
            String query = nameOrMention.replace("@", "").replace("<", "").replace(">", "").replace("!", "").trim();
            
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
        public String update_ai_model(String modelName) {
            String userId = CURRENT_USER_ID.get();
            if (!healthService.isAdmin(userId)) return "Sorry mate, only admins can change my brain settings. You don't have the requirements for this quest.";
            updateAiModel(modelName);
            return "Alright, I'll try using the " + modelName + " model from now on. Hope it's got more XP than the last one!";
        }

        @Tool("Update the AI API URL (Requires Admin)")
        public String update_ai_url(String url) {
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
                    if (jda != null) jda.shutdown();
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
                    if (jda != null) jda.shutdown();
                    System.exit(0);
                } catch (Exception e) {
                    LOGGER.error("Failed to stop via tool", e);
                }
            }).start();

            return "Stopping the bot. Logging out... see you in Lumbridge!";
        }
    }

    /**
     * Generate a response from the AI for the given prompt.
     *
     * @param prompt user input
     * @param userId Discord user ID of the sender
     * @param channelId Discord channel ID where the message was sent
     * @param guildId Discord guild ID (optional)
     * @return AI response
     */
    public AiResult generateResponse(String prompt, String userId, String channelId, String guildId) {
        BotSettings settings = storage.loadSettings();
        String url = settings.getAiUrl();
        String modelName = settings.getAiModel();

        if (url == null || url.isBlank()) {
            return new AiResult("", "AI URL is not configured. Use /admin ai url to set it up.");
        }

        if (modelName == null || modelName.isBlank()) {
            return new AiResult("", "AI model is not configured. Use /admin ai model to set it up.");
        }

        String personality = loadPersonality();
        String systemPrompt = "You are Bob, a seasoned Old School RuneScape (OSRS) veteran and helpful assistant. " +
                "You have access to tools to look up item prices, player stats, and the bot's health/configuration. " +
                "GUIDELINES:\n" +
                "1. If a user asks about their own stats or skills, use 'get_my_stats' or 'get_my_skill'.\n" +
                "2. If they ask about another player by Discord name or @mention, use 'get_stats_by_discord_name'.\n" +
                "3. If they ask about a specific OSRS username that is NOT a Discord mention, use 'get_player_stats' or 'get_player_skill'.\n" +
                "4. If they ask who is winning or about the leaderboard, use 'get_leaderboard'.\n" +
                "5. If they ask about the bot's health, status, uptime, or power state, use 'get_bot_health'.\n" +
                "6. If an admin asks to reboot or stop the bot, use 'reboot_bot' or 'stop_bot' respectively.\n" +
                "7. If they ask about your AI configuration or personality, use 'get_ai_config' or 'get_ai_personality'.\n" +
                "8. Admins can update your model or URL using 'update_ai_model' or 'update_ai_url'.\n" +
                "9. Always try to be helpful and accurate, but maintain Bob's personality.\n" +
                "10. Bob (you) is an AI assistant and does not have an OSRS account. If asked about 'your' level, clarify you don't play but can look them up.\n" +
                "11. If a tool returns an error (e.g., 'Player not found'), explain it in Bob's voice (e.g., blaming lag or the Wilderness).\n" +
                "12. IMPORTANT: Do not repeatedly call the same tool with similar or slightly varied parameters if it continues to fail. If you can't find a skill or player after one try, just tell the user you couldn't find it and move on.\n" +
                "13. Recognize common OSRS slang (like 'pl0x', 'plz', 'gz') and do not mistake them for player names or skills.";

        if (!personality.isEmpty()) {
            systemPrompt += "\n\nBOB'S PERSONALITY:\n" + personality;
        }

        try {
            CURRENT_USER_ID.set(userId);
            CURRENT_GUILD_ID.set(guildId);

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
            String response = assistantProxy.chat(channelId, systemPrompt, prompt);
            
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

            return new AiResult(thinking, cleanContent);
        } catch (Exception e) {
            if (Thread.interrupted() || e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return new AiResult("", "I was interrupted while thinking. Blame it on a world dc.");
            }
            
            String message = e.getMessage();
            if (message != null && message.contains("sequential tool executions")) {
                LOGGER.warn("AI exceeded tool execution limit: {}", message);
                return new AiResult("", "I'm trying to do too many things at once! I got stuck in a loop trying to find that for you. Maybe try being a bit more specific or check your spelling, mate.");
            }

            LOGGER.error("AI generation failed with LangChain4j", e);
            return new AiResult("", "I'm sorry, but something went wrong while I was thinking: " + e.getMessage());
        } finally {
            CURRENT_USER_ID.remove();
            CURRENT_GUILD_ID.remove();
        }
    }

    private String buildBaseUrl(String url) {
        String base = url;
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
