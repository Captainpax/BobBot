package com.bobbot.discord;

import com.bobbot.config.EnvConfig;
import com.bobbot.service.HealthService;
import com.bobbot.service.RoleService;
import com.bobbot.storage.JsonStorage;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener that handles role synchronization on startup and when joining a guild.
 */
public class RoleListener extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoleListener.class);
    private final RoleService roleService;
    private final HealthService healthService;
    private final JsonStorage storage;
    private final EnvConfig envConfig;

    public RoleListener(RoleService roleService, HealthService healthService, JsonStorage storage, EnvConfig envConfig) {
        this.roleService = roleService;
        this.healthService = healthService;
        this.storage = storage;
        this.envConfig = envConfig;
    }

    @Override
    public void onReady(ReadyEvent event) {
        roleService.syncAllAdmins(event.getJDA(), storage.loadSettings().getAdminUserIds(), envConfig.superuserId());
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        LOGGER.info("Joined new guild: {}. Syncing admin roles.", event.getGuild().getName());
        roleService.ensureRoleExists(event.getGuild()).thenAccept(role -> {
            roleService.syncAllAdmins(event.getJDA(), storage.loadSettings().getAdminUserIds(), envConfig.superuserId());
        });
    }
    
    @Override
    public void onRoleDelete(RoleDeleteEvent event) {
        if (event.getRole().getName().equalsIgnoreCase(RoleService.ADMIN_ROLE_NAME)) {
            LOGGER.info("Admin role '{}' deleted in guild {}. Recreating.", RoleService.ADMIN_ROLE_NAME, event.getGuild().getName());
            roleService.ensureRoleExists(event.getGuild()).thenAccept(role -> {
                roleService.syncAllAdmins(event.getJDA(), storage.loadSettings().getAdminUserIds(), envConfig.superuserId());
            });
        }
    }
}
