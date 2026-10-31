package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.core.VeneerModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import java.util.ArrayList;
import java.util.List;

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

    /** A permission node set on a player: its value and the group it comes from (null when set on the player). */
    public record SetNode(String node, boolean value, String group) {}

    /**
     * Nodes under {@code prefix} set on the player: from LuckPerms (own nodes first, then groups; no wildcards above
     * the prefix) when it is installed, else the ones Bukkit reports as explicitly set on the player. Without
     * LuckPerms a group is not known there, and an operator's {@code *} is not an explicit setting: it must not
     * show up as a granted node, or operators would bypass every check by being operators.
     */
    public List<SetNode> setNodes(Player player, String prefix) {
        if (luckPerms != null) return luckPerms.nodes(player, prefix);
        List<SetNode> nodes = new ArrayList<>();
        for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
            String name = permission.getName();
            if (name.startsWith(prefix) && player.isPermissionSet(name))
                nodes.add(new SetNode(name, player.hasPermission(name), null));
        }
        return nodes;
    }

    /** Primary LuckPerms group of the player, or "" when LuckPerms is absent. */
    public String primaryGroup(Player player) {
        return luckPerms == null ? "" : luckPerms.getPrimaryGroup(player);
    }
}
