package com.bobbot.util;

import java.time.Duration;

/**
 * General purpose formatting utilities.
 */
public final class FormatUtils {

    private FormatUtils() {
    }

    /**
     * Format a duration into a human-readable string (e.g., 1h 2m 3s).
     *
     * @param duration duration to format
     * @return formatted string
     */
    public static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, remainingSeconds);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, remainingSeconds);
        }
        return String.format("%ds", remainingSeconds);
    }

    /**
     * Check if a string could be a valid OSRS username.
     * OSRS usernames are 1-12 characters long and contain only letters, numbers, spaces, underscores, or hyphens.
     *
     * @param username the username to check
     * @return true if it looks valid
     */
    public static boolean isValidOsrsUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        String trimmed = username.trim();
        if (trimmed.length() > 12) {
            return false;
        }
        // OSRS usernames allow letters, numbers, spaces, underscores, and hyphens
        return trimmed.matches("^[a-zA-Z0-9\\s_-]+$");
    }
}
