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
}
