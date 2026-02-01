package com.bobbot.service;

import com.bobbot.osrs.Skill;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Service to fetch information from the OSRS Wiki.
 */
public class WikiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WikiService.class);
    private static final String WIKI_BASE_URL = "https://oldschool.runescape.wiki/w/";
    private static final String API_URL = "https://oldschool.runescape.wiki/api.php?action=query&prop=extracts&exintro&explaintext&format=json&titles=";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get the Wiki URL for a given skill.
     *
     * @param skill the skill
     * @return the wiki URL
     */
    public String getWikiUrl(Skill skill) {
        return WIKI_BASE_URL + URLEncoder.encode(skill.displayName(), StandardCharsets.UTF_8).replace("+", "_");
    }

    /**
     * Fetch a short summary for a given skill from the OSRS Wiki.
     *
     * @param skill the skill
     * @return an optional summary string
     */
    public Optional<String> getSkillSummary(Skill skill) {
        try {
            String title = skill.displayName();
            String url = API_URL + URLEncoder.encode(title, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "BobBot/0.1.0 (Contact: via Discord)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("Wiki API returned status {} for {}", response.statusCode(), title);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode pages = root.path("query").path("pages");
            if (pages.isObject() && !pages.isEmpty()) {
                JsonNode firstPage = pages.elements().next();
                if (firstPage.has("extract")) {
                    String extract = firstPage.get("extract").asText();
                    if (extract != null && !extract.isBlank()) {
                        // Clean up multiple newlines and limit length
                        extract = extract.split("\n")[0]; // Just take the first paragraph
                        if (extract.length() > 300) {
                            extract = extract.substring(0, 297) + "...";
                        }
                        return Optional.of(extract.trim());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to fetch wiki summary for {}", skill.displayName(), e);
        }
        return Optional.empty();
    }
}
