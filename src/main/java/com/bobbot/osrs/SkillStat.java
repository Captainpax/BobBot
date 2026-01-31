package com.bobbot.osrs;

/**
 * Parsed hiscore data for a single skill.
 *
 * @param skill skill definition
 * @param level current level
 * @param xp current experience
 */
public record SkillStat(Skill skill, int level, long xp) {
}
