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
        return apiClient.fetchWikiSummary(skill.displayName())
                .map(node -> node.path("url").asText())
                .orElse("https://oldschool.runescape.wiki/w/" + skill.displayName().replace(" ", "_"));
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
}
