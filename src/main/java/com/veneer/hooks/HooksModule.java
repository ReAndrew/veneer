package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.core.VeneerModule;
import org.bukkit.entity.Player;

/** Optional integrations: PlaceholderAPI ({@code %veneer_*%}) and LuckPerms (primary group). */
public class HooksModule extends VeneerModule {

    private VeneerPlaceholders placeholders;
    private LuckPermsHook luckPerms;

    public HooksModule(VeneerPlugin plugin) {
        super(plugin);
    }

    @Override public String id() { return "hooks"; }

    /** Hooks have no switch of their own: they run when the plugin they bridge to is installed. */
    @Override public boolean wanted() { return true; }

    @Override
    protected void enable() {
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new VeneerPlaceholders(plugin);
            placeholders.register();
            plugin.getLogger().info("Registered the PlaceholderAPI expansion.");
        }
        if (plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPerms = new LuckPermsHook();
                plugin.getLogger().info("Hooked into LuckPerms.");
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to hook into LuckPerms: " + t.getMessage());
            }
        }
    }

    @Override
    protected void disable() {
        if (placeholders != null) placeholders.unregister();
        placeholders = null;
        luckPerms = null;
    }

    /**
     * The value set for {@code node} on the player, or null when it is not set: from LuckPerms (own and group
     * nodes, without wildcards above it) when it is installed, else what Bukkit reports as set.
     */
    public Boolean permissionSet(Player player, String node) {
        if (luckPerms != null) return luckPerms.explicit(player, node);
        return player.isPermissionSet(node) ? player.hasPermission(node) : null;
    }

    /** Primary LuckPerms group of the player, or "" when LuckPerms is absent. */
    public String primaryGroup(Player player) {
        return luckPerms == null ? "" : luckPerms.getPrimaryGroup(player);
    }
}
