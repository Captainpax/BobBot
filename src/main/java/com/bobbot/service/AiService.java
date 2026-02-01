package com.bobbot.service;

import com.bobbot.storage.BotSettings;
import com.bobbot.storage.JsonStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Service that handles communication with a local AI API.
 */
public class AiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiService.class);
    private final JsonStorage storage;
    private final Path dataDir;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public AiService(JsonStorage storage, Path dataDir) {
        this.storage = storage;
        this.dataDir = dataDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Generate a response from the AI for the given prompt.
     *
     * @param prompt user input
     * @return AI response
     * @throws IOException on network or API failures
     * @throws InterruptedException if the request is interrupted
     */
    public String generateResponse(String prompt) throws IOException, InterruptedException {
        BotSettings settings = storage.loadSettings();
        String url = settings.getAiUrl();
        String model = settings.getAiModel();

        if (url == null || url.isBlank()) {
            return "AI URL is not configured. Use /admin ai url to set it up.";
        }

        if (model == null || model.isBlank()) {
            return "AI model is not configured. Use /admin ai model to set it up.";
        }

        String personality = loadPersonality();

        // Prepare OpenAI-compatible chat completion request
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        ArrayNode messages = root.putArray("messages");

        if (!personality.isEmpty()) {
            messages.addObject()
                    .put("role", "system")
                    .put("content", personality);
        }

        messages.addObject()
                .put("role", "user")
                .put("content", prompt);

        String jsonBody = mapper.writeValueAsString(root);

        // Ensure URL ends correctly for OpenAI-compatible endpoint
        String endpoint = url;
        if (!endpoint.endsWith("/v1/chat/completions")) {
            if (endpoint.endsWith("/")) {
                endpoint += "v1/chat/completions";
            } else {
                endpoint += "/v1/chat/completions";
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMinutes(2))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOGGER.error("AI API returned status {}: {}", response.statusCode(), response.body());
            return "Error from AI API: " + response.statusCode();
        }

        JsonNode responseJson = mapper.readTree(response.body());
        return responseJson.path("choices").path(0).path("message").path("content").asText("I'm not sure how to respond to that.");
    }

    /**
     * Update and persist the AI URL.
     *
     * @param url AI API URL
     */
    public void updateAiUrl(String url) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withAiUrl(url));
    }

    /**
     * Update and persist the AI model name.
     *
     * @param model model name
     */
    public void updateAiModel(String model) {
        BotSettings settings = storage.loadSettings();
        storage.saveSettings(settings.withAiModel(model));
    }

    /**
     * Save the personality content to personality.txt.
     *
     * @param content personality content
     * @throws IOException if writing fails
     */
    public void savePersonality(String content) throws IOException {
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }
        Files.writeString(dataDir.resolve("personality.txt"), content);
    }

    /**
     * Load the personality content from personality.txt.
     *
     * @return personality content or empty string if not found
     */
    public String loadPersonality() {
        Path path = dataDir.resolve("personality.txt");
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
