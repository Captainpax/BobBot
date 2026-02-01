package com.bobbot.osrs;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Client for OSRS items, now delegating to the Node.js API.
 */
public class OsrsItemClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(OsrsItemClient.class);
    private final OsrsApiClient apiClient;

    public OsrsItemClient(OsrsApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public record ItemMapping(int id, String name) {}

    public record ItemPrice(Long high, Long low) {}

    /**
     * Find an item by name (case-insensitive) or alias.
     *
     * @param name item name or alias
     * @return optional item mapping
     * @throws IOException on API failures
     * @throws InterruptedException on interrupted requests
     */
    public Optional<ItemMapping> findItem(String name) throws IOException, InterruptedException {
        Optional<JsonNode> nodeOpt = apiClient.fetchItemPrice(name);
        if (nodeOpt.isEmpty()) return Optional.empty();
        
        JsonNode node = nodeOpt.get();
        return Optional.of(new ItemMapping(node.get("id").asInt(), node.get("name").asText()));
    }

    /**
     * Fetch the latest price for an item name.
     *
     * @param name item name
     * @return optional item price
     * @throws IOException on API failures
     * @throws InterruptedException on interrupted requests
     */
    public Optional<ItemPrice> fetchPriceByName(String name) throws IOException, InterruptedException {
        Optional<JsonNode> nodeOpt = apiClient.fetchItemPrice(name);
        if (nodeOpt.isEmpty()) return Optional.empty();

        JsonNode prices = nodeOpt.get().path("prices");
        if (prices.isMissingNode()) return Optional.empty();

        Long high = prices.has("high") && !prices.get("high").isNull() ? prices.get("high").asLong() : null;
        Long low = prices.has("low") && !prices.get("low").isNull() ? prices.get("low").asLong() : null;
        return Optional.of(new ItemPrice(high, low));
    }

    /**
     * Search for items matching a query.
     *
     * @param query search query
     * @param limit maximum number of results
     * @return list of matching item mappings
     */
    public List<ItemMapping> searchItems(String query, int limit) {
        return apiClient.searchItems(query, limit).stream()
                .map(node -> new ItemMapping(node.get("id").asInt(), node.get("name").asText()))
                .toList();
    }

    // Keep the id-based one for compatibility if needed, though name-based is easier with the new API
    public Optional<ItemPrice> fetchPrice(int id) throws IOException, InterruptedException {
        // Since the new API is name-based for lookup, this might be less efficient if we only have ID
        // but for now let's just use a placeholder or implement it if really needed.
        // Actually, let's just make it throw as we should prefer names now.
        throw new UnsupportedOperationException("Use fetchPriceByName with the new API");
    }
}
