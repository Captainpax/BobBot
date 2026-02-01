package com.bobbot.service;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Service to manage the "bob master" role in Discord guilds.
 */
public class RoleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoleService.class);
    public static final String ADMIN_ROLE_NAME = "bob master";

    /**
     * Ensure the admin role exists in the given guild.
     *
     * @param guild Discord guild
     * @return future that completes with the role
     */
    public CompletableFuture<Role> ensureRoleExists(Guild guild) {
        List<Role> roles = guild.getRolesByName(ADMIN_ROLE_NAME, true);
        if (!roles.isEmpty()) {
            return CompletableFuture.completedFuture(roles.get(0));
        }

        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            LOGGER.warn("Missing MANAGE_ROLES permission in guild {}", guild.getName());
            return CompletableFuture.failedFuture(new IllegalStateException("Missing permission"));
        }

        LOGGER.info("Creating '{}' role in guild {}", ADMIN_ROLE_NAME, guild.getName());
        return guild.createRole()
                .setName(ADMIN_ROLE_NAME)
                .setHoisted(true)
                .setMentionable(true)
                .submit();
    }

    /**
     * Assign the admin role to a user in all guilds shared with the bot.
     *
     * @param jda active JDA client
     * @param userId Discord user ID
     */
    public void assignRoleToAdmin(JDA jda, String userId) {
        for (Guild guild : jda.getGuilds()) {
            ensureRoleExists(guild).thenAccept(role -> {
                guild.retrieveMemberById(userId).queue(member -> {
                    if (!member.getRoles().contains(role)) {
                        guild.addRoleToMember(member, role).queue(
                                success -> LOGGER.info("Assigned '{}' role to {} in guild {}", ADMIN_ROLE_NAME, userId, guild.getName()),
                                error -> LOGGER.warn("Failed to assign role to {} in guild {}", userId, guild.getName(), error)
                        );
                    }
                }, error -> LOGGER.debug("User {} not found in guild {}", userId, guild.getName()));
            }).exceptionally(ex -> {
                LOGGER.warn("Could not ensure role in guild {}: {}", guild.getName(), ex.getMessage());
                return null;
            });
        }
    }

    /**
     * Remove the admin role from a user in all guilds shared with the bot.
     *
     * @param jda active JDA client
     * @param userId Discord user ID
     */
    public void removeRoleFromAdmin(JDA jda, String userId) {
        for (Guild guild : jda.getGuilds()) {
            List<Role> roles = guild.getRolesByName(ADMIN_ROLE_NAME, true);
            if (roles.isEmpty()) continue;
            Role role = roles.get(0);

            guild.retrieveMemberById(userId).queue(member -> {
                if (member.getRoles().contains(role)) {
                    guild.removeRoleFromMember(member, role).queue(
                            success -> LOGGER.info("Removed '{}' role from {} in guild {}", ADMIN_ROLE_NAME, userId, guild.getName()),
                            error -> LOGGER.warn("Failed to remove role from {} in guild {}", userId, guild.getName(), error)
                    );
                }
            }, error -> LOGGER.debug("User {} not found in guild {}", userId, guild.getName()));
        }
    }

    /**
     * Sync roles for all admins across all guilds.
     *
     * @param jda active JDA client
     * @param adminIds set of admin user IDs
     * @param superuserId superuser ID
     */
    public void syncAllAdmins(JDA jda, Set<String> adminIds, String superuserId) {
        LOGGER.info("Syncing '{}' roles for all admins", ADMIN_ROLE_NAME);
        for (Guild guild : jda.getGuilds()) {
            ensureRoleExists(guild).thenAccept(role -> {
                // Add role to superuser
                if (superuserId != null && !superuserId.isBlank()) {
                    syncMemberRole(guild, superuserId, role, true);
                }

                // Add role to admins
                for (String adminId : adminIds) {
                    syncMemberRole(guild, adminId, role, true);
                }

                // Optional: Remove role from people who are not admins anymore?
                // For now, let's just stick to adding. 
                // If we wanted to be strict, we'd list members with the role and remove if not in adminIds.
                // But retrieveMembersByRole is a bit heavy.
            });
        }
    }

    private void syncMemberRole(Guild guild, String userId, Role role, boolean shouldHave) {
        guild.retrieveMemberById(userId).queue(member -> {
            boolean hasRole = member.getRoles().contains(role);
            if (shouldHave && !hasRole) {
                guild.addRoleToMember(member, role).queue();
            } else if (!shouldHave && hasRole) {
                guild.removeRoleFromMember(member, role).queue();
            }
        }, error -> LOGGER.debug("Member {} not found in guild {} for sync", userId, guild.getName()));
    }
}
