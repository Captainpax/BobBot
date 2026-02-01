package com.bobbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service to manage .env file configuration.
 */
public class ConfigService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigService.class);
    private static final String ENV_FILE = ".env";
    
    private static final List<String> SUPPORTED_VARS = List.of(
            "DISCORD_TOKEN",
            "DISCORD_SUPERUSER_ID",
            "LEADERBOARD_INTERVAL",
            "POLL_INTERVAL",
            "DATA_DIR",
            "HEALTH_PORT",
            "PORT",
            "ENVIRONMENT",
            "AI_URL",
            "AI_MODEL"
    );

    /**
     * @return list of supported environment variable names
     */
    public List<String> getSupportedVariables() {
        return SUPPORTED_VARS;
    }

    /**
     * Update or add a variable in the .env file.
     *
     * @param key variable name
     * @param value variable value
     * @throws IOException if file operations fail
     */
    public void updateEnvFile(String key, String value) throws IOException {
        Path path = Path.of(ENV_FILE);
        List<String> lines;
        if (Files.exists(path)) {
            lines = Files.readAllLines(path);
        } else {
            lines = new ArrayList<>();
        }

        boolean found = false;
        String newLine = key + "=" + value;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith(key + "=") || line.startsWith("# " + key + "=") || line.startsWith("#" + key + "=")) {
                lines.set(i, newLine);
                found = true;
                break;
            }
        }

        if (!found) {
            lines.add(newLine);
        }

        Files.write(path, lines);
        LOGGER.info("Updated {} in .env", key);
    }
}
