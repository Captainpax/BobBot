package com.bobbot.osrs;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * OSRS skills in hiscore lite order.
 */
public enum Skill {
    TOTAL(0, "Overall", true),
    ATTACK(1, "Attack"),
    DEFENCE(2, "Defence"),
    STRENGTH(3, "Strength"),
    HITPOINTS(4, "Hitpoints"),
    RANGED(5, "Ranged"),
    PRAYER(6, "Prayer"),
    MAGIC(7, "Magic"),
    COOKING(8, "Cooking"),
    WOODCUTTING(9, "Woodcutting"),
    FLETCHING(10, "Fletching"),
    FISHING(11, "Fishing"),
    FIREMAKING(12, "Firemaking"),
    CRAFTING(13, "Crafting"),
    SMITHING(14, "Smithing"),
    MINING(15, "Mining"),
    HERBLORE(16, "Herblore"),
    AGILITY(17, "Agility"),
    THIEVING(18, "Thieving"),
    SLAYER(19, "Slayer"),
    FARMING(20, "Farming"),
    RUNECRAFT(21, "Runecraft"),
    HUNTER(22, "Hunter"),
    CONSTRUCTION(23, "Construction"),
    SAILING(24, "Sailing");

    private static final List<Skill> ORDERED = Arrays.stream(values())
            .sorted(Comparator.comparingInt(Skill::lineIndex))
            .toList();

    private final int lineIndex;
    private final String displayName;
    private final boolean overall;

    Skill(int lineIndex, String displayName) {
        this(lineIndex, displayName, false);
    }

    Skill(int lineIndex, String displayName, boolean overall) {
        this.lineIndex = lineIndex;
        this.displayName = displayName;
        this.overall = overall;
    }

    public int lineIndex() {
        return lineIndex;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isOverall() {
        return overall;
    }

    public static List<Skill> ordered() {
        return ORDERED;
    }
}
