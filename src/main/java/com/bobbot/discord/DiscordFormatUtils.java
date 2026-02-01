package com.bobbot.discord;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;

import java.time.Instant;
import java.util.List;

/**
 * Utility for formatting Discord messages.
 */
public final class DiscordFormatUtils {

    private DiscordFormatUtils() {
    }

    /**
     * Create a standard Bob-styled EmbedBuilder.
     *
     * @param jda active JDA client
     * @return EmbedBuilder
     */
    public static EmbedBuilder createBobEmbed(JDA jda) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(0xffbb00); // OSRS Gold
        eb.setTimestamp(Instant.now());
        if (jda != null) {
            eb.setFooter("BobBot v0.1.0", jda.getSelfUser().getEffectiveAvatarUrl());
        }
        return eb;
    }
}
