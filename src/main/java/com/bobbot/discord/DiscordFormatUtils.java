package com.bobbot.discord;

import java.util.List;

/**
 * Utility for formatting Discord messages.
 */
public final class DiscordFormatUtils {
    private static final int DISCORD_MESSAGE_LIMIT = 1800;

    private DiscordFormatUtils() {
    }

    /**
     * Append a table section to a list of messages, splitting if it exceeds the Discord limit.
     *
     * @param messages list of accumulated messages
     * @param current current message builder
     * @param title section title
     * @param header table header
     * @param separator table separator
     * @param rows table rows
     * @param codeBlock whether to wrap in a code block
     * @return updated message builder
     */
    public static StringBuilder appendTableSection(List<String> messages,
                                                   StringBuilder current,
                                                   String title,
                                                   String header,
                                                   String separator,
                                                   List<String> rows,
                                                   boolean codeBlock) {
        String blockStart = codeBlock ? "```\n" : "";
        String blockEnd = codeBlock ? "```" : "";
        String tableHeader = title + "\n" + blockStart + header + "\n" + separator + "\n";
        if (current.length() + tableHeader.length() + blockEnd.length() > DISCORD_MESSAGE_LIMIT) {
            if (current.length() > 0) {
                messages.add(current.toString());
            }
            current = new StringBuilder();
        }
        current.append(tableHeader);
        for (String row : rows) {
            String rowLine = row + "\n";
            if (current.length() + rowLine.length() + blockEnd.length() > DISCORD_MESSAGE_LIMIT) {
                if (codeBlock) {
                    current.append(blockEnd);
                }
                messages.add(current.toString());
                current = new StringBuilder();
                current.append(title).append(" (cont.)\n");
                current.append(blockStart).append(header).append("\n").append(separator).append("\n");
            }
            current.append(rowLine);
        }
        if (codeBlock) {
            current.append(blockEnd);
        }
        return current;
    }
}
