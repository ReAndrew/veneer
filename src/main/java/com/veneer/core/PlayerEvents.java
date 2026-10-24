package com.veneer.core;

import com.veneer.VeneerPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The only join/quit listener in Veneer. It hands each event to every running module in order
 * ({@link VeneerModule#playerJoin}, {@link VeneerModule#playerQuit}), then to the update notice and the
 * profile cleanup, so modules never register their own join/quit handlers.
 */
public final class PlayerEvents implements Listener {

    private final VeneerPlugin plugin;
    private volatile long lastPurge;

    public PlayerEvents(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.profile(event.getPlayer());
        var address = event.getPlayer().getAddress();
        if (address != null && address.getAddress() != null) {
            var uuid = event.getPlayer().getUniqueId().toString();
            var name = event.getPlayer().getName();
            var ip = address.getAddress().getHostAddress();
            var skin = event.getPlayer().getPlayerProfile().getTextures().getSkin();
            var skinUrl = skin != null ? skin.toString() : null;
            FoliaScheduler.runAsync(plugin, () -> {
                plugin.database().recordJoin(uuid, name, ip);
                if (skinUrl != null && !plugin.database().hasFreshSkin(uuid, 7 * 86_400_000L)) {
                    String skinPng = Heads.fetch(skinUrl);
                    if (skinPng != null) plugin.database().saveSkin(uuid, skinPng);
                }
                long now = System.currentTimeMillis();
                if (now - lastPurge > 3_600_000L) {
                    lastPurge = now;
                    var hacks = plugin.config().section("hacks");
                    plugin.database().purge(hacks.getInt("web-editor.links.retention-days", 90),
                            hacks.getInt("web-editor.history.retention-days", 180));
                }
            });
        }
        for (VeneerModule module : plugin.modules()) {
            if (!module.running()) continue;
            try {
                module.playerJoin(event.getPlayer());
            } catch (Throwable t) {
                plugin.getLogger().warning("Module '" + module.id() + "' failed on join of " + event.getPlayer().getName() + ": " + t);
            }
        }
        plugin.updates().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        for (VeneerModule module : plugin.modules()) {
            if (!module.running()) continue;
            try {
                module.playerQuit(uuid);
            } catch (Throwable t) {
                plugin.getLogger().warning("Module '" + module.id() + "' failed on quit: " + t);
            }
        }
        plugin.dropProfile(uuid);
    }
}
