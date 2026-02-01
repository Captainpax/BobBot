package com.bobbot.osrs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the Node.js OSRS API.
 */
public class OsrsApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(OsrsApiClient.class);
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OsrsApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public List<SkillStat> fetchPlayerStats(String username) throws IOException, InterruptedException {
        String url = baseUrl + "/api/player/" + username.replace(" ", "%20");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException("Player '" + username + "' not found on OSRS hiscores.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("OSRS API lookup failed with status " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        List<SkillStat> stats = new ArrayList<>();
        
        // osrs-json-hiscores returns an object where keys are skill names
        if (root.has("main")) {
            JsonNode skills = root.get("main").get("skills");
            Iterator<Map.Entry<String, JsonNode>> fields = skills.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String skillName = entry.getKey();
                JsonNode skillData = entry.getValue();
                
                Optional<Skill> skillOpt = Skill.findByName(skillName);
                if (skillOpt.isPresent()) {
                    int level = skillData.path("level").asInt();
                    long xp = skillData.path("xp").asLong();
                    stats.add(new SkillStat(skillOpt.get(), level, xp));
                }
            }
        }
        return stats;
    }

    public Optional<JsonNode> fetchItemPrice(String itemName) {
        try {
            String url = baseUrl + "/api/item/" + itemName.replace(" ", "%20");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            LOGGER.error("Failed to fetch item price for {}", itemName, e);
            return Optional.empty();
        }
    }

    public Optional<JsonNode> fetchWikiSummary(String title) {
        try {
            String url = baseUrl + "/api/wiki/" + title.replace(" ", "%20");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            LOGGER.error("Failed to fetch wiki summary for {}", title, e);
            return Optional.empty();
        }
    }

    public List<JsonNode> searchItems(String query, int limit) {
        try {
            String url = baseUrl + "/api/items/search/" + query.replace(" ", "%20") + "?limit=" + limit;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<JsonNode> results = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    results.add(node);
                }
            }
            return results;
        } catch (Exception e) {
            LOGGER.error("Failed to search items for {}", query, e);
            return List.of();
        }
    }

    public Optional<JsonNode> fetchQuestInfo(String questName) {
        try {
            String url = baseUrl + "/api/quests/" + questName.replace(" ", "%20");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            LOGGER.error("Failed to fetch quest info for {}", questName, e);
            return Optional.empty();
        }
    }

    public List<JsonNode> fetchSlayerTasks(String master) {
        try {
            String url = baseUrl + "/api/slayer/" + master.replace(" ", "%20");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<JsonNode> tasks = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    tasks.add(node);
                }
            }
            return tasks;
        } catch (Exception e) {
            LOGGER.error("Failed to fetch slayer tasks for {}", master, e);
            return List.of();
        }
    }
}
