package com.bobbot.osrs;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for OSRS hiscores, now delegating to the Node.js API.
 */
public class HiscoreClient {
    private final OsrsApiClient apiClient;

    public HiscoreClient(OsrsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Fetch a player's total level and XP (Overall stat) from the OSRS hiscore lite endpoint.
     *
     * @param username OSRS username
     * @return overall stat
     * @throws IOException on HTTP or parse failures
     * @throws InterruptedException on interrupted HTTP requests
     */
    public SkillStat fetchOverallStat(String username) throws IOException, InterruptedException {
        return fetchSkillStats(username).stream()
                .filter(s -> s.skill().isOverall())
                .findFirst()
                .orElseThrow(() -> new IOException("Overall stat missing for " + username));
    }

    /**
     * Fetch a player's total level from the OSRS hiscore lite endpoint.
     *
     * @param username OSRS username
     * @return total level
     * @throws IOException on HTTP or parse failures
     * @throws InterruptedException on interrupted HTTP requests
     */
    public int fetchTotalLevel(String username) throws IOException, InterruptedException {
        return fetchOverallStat(username).level();
    }

    /**
     * Fetch the response status code for a player lookup.
     *
     * @param username OSRS username
     * @return HTTP status code
     * @throws IOException on HTTP failures
     * @throws InterruptedException on interrupted HTTP requests
     */
    public int fetchStatusCode(String username) throws IOException, InterruptedException {
        try {
            fetchOverallStat(username);
            return 200;
        } catch (IOException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("not found")) return 404;
            return 500;
        }
    }

    /**
     * Fetch a player's hiscore lite data and parse it into skill stats.
     *
     * @param username OSRS username
     * @return ordered list of skill stats
     * @throws IOException on HTTP or parse failures
     * @throws InterruptedException on interrupted HTTP requests
     */
    public List<SkillStat> fetchSkillStats(String username) throws IOException, InterruptedException {
        return apiClient.fetchPlayerStats(username);
    }
}
