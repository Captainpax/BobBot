package com.bobbot.osrs;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Client for the OSRS hiscore lite endpoint.
 */
public class HiscoreClient {
    private static final String HISCORE_URL = "https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player=";
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Fetch a player's total level from the OSRS hiscore lite endpoint.
     *
     * @param username OSRS username
     * @return total level
     * @throws IOException on HTTP or parse failures
     * @throws InterruptedException on interrupted HTTP requests
     */
    public int fetchTotalLevel(String username) throws IOException, InterruptedException {
        HttpResponse<String> response = fetchResponse(username);
        if (response.statusCode() != 200) {
            throw new IOException("Hiscore lookup failed with status " + response.statusCode());
        }
        String body = response.body();
        String firstLine = body.split("\\R", 2)[0];
        String[] parts = firstLine.split(",");
        if (parts.length < 2) {
            throw new IOException("Unexpected hiscore format");
        }
        return Integer.parseInt(parts[1]);
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

    private HttpResponse<String> fetchResponse(String username) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(HISCORE_URL + encoded))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
