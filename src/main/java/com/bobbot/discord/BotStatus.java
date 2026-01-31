package com.bobbot.discord;

import net.dv8tion.jda.api.OnlineStatus;

import java.util.Locale;
import java.util.Set;

/**
 * Helper for mapping bot status strings to Discord presence values.
 */
public final class BotStatus {
    public static final String ONLINE = "online";
    public static final String BUSY = "busy";
    public static final String OFFLINE = "offline";
    private static final Set<String> ALLOWED = Set.of(ONLINE, BUSY, OFFLINE);

    private BotStatus() {
    }

    /**
     * Normalize a status string to a supported value.
     *
     * @param status input status
     * @return normalized status
     */
    public static String normalize(String status) {
        if (status == null) {
            return ONLINE;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return ALLOWED.contains(normalized) ? normalized : ONLINE;
    }

    /**
     * Convert a normalized status string to a Discord OnlineStatus.
     *
     * @param status normalized status string
     * @return OnlineStatus mapping
     */
    public static OnlineStatus toOnlineStatus(String status) {
        return switch (normalize(status)) {
            case BUSY -> OnlineStatus.DO_NOT_DISTURB;
            case OFFLINE -> OnlineStatus.INVISIBLE;
            default -> OnlineStatus.ONLINE;
        };
    }
}
