package com.bobbot.osrs;

/**
 * Utility for OSRS experience thresholds.
 */
public final class OsrsXpTable {
    private static final int MAX_LEVEL = 120;
    private static final long[] XP_TABLE = buildXpTable();

    private OsrsXpTable() {
    }

    /**
     * Calculate the remaining XP to the next level.
     *
     * @param level current level
     * @param currentXp current experience
     * @return remaining XP to next level, or 0 if already at cap
     */
    public static long xpToNextLevel(int level, long currentXp) {
        if (level < 1) {
            return 0;
        }
        int maxLevel = level >= 99 ? 120 : 99;
        if (level >= maxLevel) {
            return 0;
        }
        long nextLevelXp = XP_TABLE[level + 1];
        long safeXp = Math.max(0L, currentXp);
        return Math.max(0L, nextLevelXp - safeXp);
    }

    /**
     * Return the XP needed to reach a specific level.
     *
     * @param level target level
     * @return total XP required
     */
    public static long xpForLevel(int level) {
        if (level < 1) {
            return 0;
        }
        if (level > MAX_LEVEL) {
            return XP_TABLE[MAX_LEVEL];
        }
        return XP_TABLE[level];
    }

    private static long[] buildXpTable() {
        long[] table = new long[MAX_LEVEL + 1];
        table[1] = 0L;
        long points = 0L;
        for (int level = 1; level < MAX_LEVEL; level++) {
            points += Math.floor(level + 300.0 * Math.pow(2.0, (double) level / 7.0));
            table[level + 1] = (long) Math.floor(points / 4.0);
        }
        return table;
    }
}
