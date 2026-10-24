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

    public PlayerEvents(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.profile(event.getPlayer());
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
