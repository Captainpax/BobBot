package com.bobbot.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Simple JSON file storage where each file is a collection.
 */
public class JsonStorage {
    private final Path dataDir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Create a new storage instance.
     *
     * @param dataDir base data directory
     */
    public JsonStorage(Path dataDir) {
        this.dataDir = dataDir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Load player records keyed by Discord user ID.
     *
     * @return player map
     */
    public Map<String, PlayerRecord> loadPlayers() {
        lock.readLock().lock();
        try {
            Path file = dataDir.resolve("players.json");
            if (!Files.exists(file)) {
                return new HashMap<>();
            }
            return mapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            return new HashMap<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Persist player records.
     *
     * @param players player map
     */
    public void savePlayers(Map<String, PlayerRecord> players) {
        lock.writeLock().lock();
        try {
            ensureDataDir();
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dataDir.resolve("players.json").toFile(), players);
        } catch (IOException ignored) {
            // best effort
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Load bot settings.
     *
     * @return stored settings
     */
    public BotSettings loadSettings() {
        lock.readLock().lock();
        try {
            Path file = dataDir.resolve("settings.json");
            if (!Files.exists(file)) {
                return new BotSettings(null, null, "online", "production", null, null, null, null, null, null);
            }
            return mapper.readValue(file.toFile(), BotSettings.class);
        } catch (IOException e) {
            return new BotSettings(null, null, "online", "production", null, null, null, null, null, null);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Persist bot settings.
     *
     * @param settings settings to save
     */
    public void saveSettings(BotSettings settings) {
        lock.writeLock().lock();
        try {
            ensureDataDir();
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dataDir.resolve("settings.json").toFile(), settings);
        } catch (IOException ignored) {
            // best effort
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Ensure that the data directory exists.
     *
     * @throws IOException if directories cannot be created
     */
    private void ensureDataDir() throws IOException {
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }
    }
}
