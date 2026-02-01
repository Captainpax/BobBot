package com.bobbot.service;

import com.bobbot.osrs.OsrsApiClient;
import com.bobbot.osrs.Skill;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Service to fetch information from the OSRS Wiki via the Node.js API.
 */
public class WikiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WikiService.class);
    private final OsrsApiClient apiClient;

    public WikiService(OsrsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Get the Wiki URL for a given skill.
     *
     * @param skill the skill
     * @return the wiki URL
     */
    public String getWikiUrl(Skill skill) {
        return getWikiUrl(skill.displayName());
    }

    /**
     * Get the Wiki URL for a given title.
     *
     * @param title the page title
     * @return the wiki URL
     */
    public String getWikiUrl(String title) {
        return apiClient.fetchWikiSummary(title)
                .map(node -> node.path("url").asText())
                .orElse("https://oldschool.runescape.wiki/w/" + title.replace(" ", "_"));
    }

    /**
     * Fetch a short summary for a given skill from the OSRS Wiki.
     *
     * @param skill the skill
     * @return an optional summary string
     */
    public Optional<String> getSkillSummary(Skill skill) {
        return apiClient.fetchWikiSummary(skill.displayName())
                .map(node -> node.path("summary").asText())
                .filter(s -> !s.isBlank());
    }

    /**
     * Fetch a full guide/extract for a given title from the OSRS Wiki.
     *
     * @param title the page title
     * @return an optional guide string
     */
    public Optional<String> getWikiGuide(String title) {
        return apiClient.fetchWikiGuide(title)
                .map(node -> node.path("guide").asText())
                .filter(s -> !s.isBlank());
    }

    /**
     * Fetch quest info from the OSRS API.
     *
     * @param questName name of the quest
     * @return optional quest info JSON
     */
    public Optional<JsonNode> fetchQuestInfo(String questName) {
        return apiClient.fetchQuestInfo(questName);
    }

    /**
     * Search the wiki for a given query.
     *
     * @param query search query
     * @return optional search result JSON
     */
    public Optional<JsonNode> searchWiki(String query) {
        return apiClient.searchWiki(query);
    }
}
