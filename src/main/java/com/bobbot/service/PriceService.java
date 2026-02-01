package com.bobbot.service;

import com.bobbot.osrs.OsrsItemClient;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Service for OSRS price lookups.
 */
public class PriceService {
    private final OsrsItemClient itemClient;

    public PriceService() {
        this.itemClient = new OsrsItemClient();
    }

    /**
     * Look up an item by name and fetch its price.
     *
     * @param itemName name of the item
     * @return price info or empty if not found
     * @throws IOException on API failures
     * @throws InterruptedException on interrupted requests
     */
    public Optional<PriceInfo> lookupPrice(String itemName) throws IOException, InterruptedException {
        Optional<OsrsItemClient.ItemMapping> mapping = itemClient.findItem(itemName);
        if (mapping.isEmpty()) {
            // Try partial search if exact match fails
            List<OsrsItemClient.ItemMapping> searchResults = itemClient.searchItems(itemName, 1);
            if (searchResults.isEmpty()) {
                return Optional.empty();
            }
            mapping = Optional.of(searchResults.get(0));
        }

        OsrsItemClient.ItemMapping item = mapping.get();
        Optional<OsrsItemClient.ItemPrice> price = itemClient.fetchPrice(item.id());

        return Optional.of(new PriceInfo(item.name(), price.orElse(null)));
    }

    public record PriceInfo(String itemName, OsrsItemClient.ItemPrice price) {}
}
