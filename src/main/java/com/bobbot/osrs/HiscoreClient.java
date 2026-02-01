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
 * Client for the OSRS hiscore lite endpoint.
 */
public class HiscoreClient {
    private static final String HISCORE_URL = "https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player=";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Fetch a player's total level and XP (Overall stat) from the OSRS hiscore lite endpoint.
     *
     * @param username OSRS username
     * @return overall stat
     * @throws IOException on HTTP or parse failures
     * @throws InterruptedException on interrupted HTTP requests
     */
    public SkillStat fetchOverallStat(String username) throws IOException, InterruptedException {
        HttpResponse<String> response = fetchResponse(username);
        if (response.statusCode() == 404) {
            throw new IOException("Player '" + username + "' not found on OSRS hiscores. They might be unranked or have changed their name.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("OSRS hiscore lookup failed for '" + username + "' with HTTP status " + response.statusCode() + ". Jagex might be having issues.");
        }
        String body = response.body();
        String firstLine = body.split("\\R", 2)[0];
        return parseSkillLine(Skill.TOTAL, firstLine);
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
        return fetchResponse(username).statusCode();
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
        HttpResponse<String> response = fetchResponse(username);
        if (response.statusCode() == 404) {
            throw new IOException("Player '" + username + "' not found on OSRS hiscores. They might be unranked or have changed their name.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("OSRS hiscore lookup failed for '" + username + "' with HTTP status " + response.statusCode() + ". Jagex might be having issues.");
        }
        String[] lines = response.body().split("\\R");
        List<SkillStat> stats = new ArrayList<>();
        for (Skill skill : Skill.ordered()) {
            int index = skill.lineIndex();
            if (index >= lines.length) {
                break;
            }
            try {
                stats.add(parseSkillLine(skill, lines[index]));
            } catch (Exception e) {
                // If a specific skill line fails to parse (e.g. Activity instead of Skill, or non-numeric data), skip it
                // This handles cases like Sailing (index 24) currently pointing to an Activity line or partial data
            }
        }
        return stats;
    }

    private HttpResponse<String> fetchResponse(String username) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(HISCORE_URL + encoded))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private SkillStat parseSkillLine(Skill skill, String line) throws IOException {
        String[] parts = line.split(",");
        if (parts.length < 3) {
            throw new IOException("Unexpected hiscore format");
        }
        int level = Integer.parseInt(parts[1]);
        long xp = Long.parseLong(parts[2]);
        return new SkillStat(skill, level, xp);
    }
}
