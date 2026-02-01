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

    public PriceService(OsrsItemClient itemClient) {
        this.itemClient = itemClient;
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
        Optional<OsrsItemClient.ItemPrice> price = itemClient.fetchPriceByName(itemName);
        if (price.isEmpty()) {
            // Try searching
            List<OsrsItemClient.ItemMapping> searchResults = itemClient.searchItems(itemName, 1);
            if (searchResults.isEmpty()) {
                return Optional.empty();
            }
            OsrsItemClient.ItemMapping item = searchResults.get(0);
            price = itemClient.fetchPriceByName(item.name());
            return Optional.of(new PriceInfo(item.name(), price.orElse(null)));
        }

        // To get the "canonical" name if we found it directly
        Optional<OsrsItemClient.ItemMapping> itemOpt = itemClient.findItem(itemName);
        String finalName = itemOpt.map(OsrsItemClient.ItemMapping::name).orElse(itemName);

        return Optional.of(new PriceInfo(finalName, price.get()));
    }

    public record PriceInfo(String itemName, OsrsItemClient.ItemPrice price) {}
}
