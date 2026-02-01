package com.bobbot.osrs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

/**
 * Client for the OSRS Wiki Prices API.
 */
public class OsrsItemClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(OsrsItemClient.class);
    private static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest?id=";
    private static final String USER_AGENT = "BobBot OSRS Price Lookup - @yourdiscord";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private List<ItemMapping> mappingCache = null;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemMapping(int id, String name) {}

    public record ItemPrice(Long high, Long low) {}

    /**
     * Find an item by name (case-insensitive).
     *
     * @param name item name
     * @return optional item mapping
     * @throws IOException on API failures
     * @throws InterruptedException on interrupted requests
     */
    public Optional<ItemMapping> findItem(String name) throws IOException, InterruptedException {
        ensureMappingLoaded();
        return mappingCache.stream()
                .filter(item -> item.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Search for items matching a query.
     *
     * @param query search query
     * @param limit maximum number of results
     * @return list of matching item mappings
     * @throws IOException on API failures
     * @throws InterruptedException on interrupted requests
     */
    public List<ItemMapping> searchItems(String query, int limit) throws IOException, InterruptedException {
        ensureMappingLoaded();
        String lowerQuery = query.toLowerCase();
        return mappingCache.stream()
                .filter(item -> item.name().toLowerCase().contains(lowerQuery))
                .limit(limit)
                .toList();
    }

    /**
     * Fetch the latest price for an item ID.
     *
     * @param id item ID
     * @return optional item price
     * @throws IOException on API failures
     * @throws InterruptedException on interrupted requests
     */
    public Optional<ItemPrice> fetchPrice(int id) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_URL + id))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return Optional.empty();
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode data = root.path("data").path(String.valueOf(id));
        if (data.isMissingNode()) {
            return Optional.empty();
        }
        Long high = data.has("high") && !data.get("high").isNull() ? data.get("high").asLong() : null;
        Long low = data.has("low") && !data.get("low").isNull() ? data.get("low").asLong() : null;
        return Optional.of(new ItemPrice(high, low));
    }

    private synchronized void ensureMappingLoaded() throws IOException, InterruptedException {
        if (mappingCache != null) {
            return;
        }
        LOGGER.info("Fetching OSRS item mapping from Wiki API");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MAPPING_URL))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch item mapping: " + response.statusCode());
        }
        mappingCache = mapper.readValue(response.body(), new TypeReference<>() {});
        LOGGER.info("Loaded {} items into mapping cache", mappingCache.size());
    }
}
