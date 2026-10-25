package com.veneer.hooks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

/** Optional LuckPerms integration: a player's primary group and own permission nodes. Only loaded when LuckPerms is present. */
final class LuckPermsHook {

    private final LuckPerms lp = LuckPermsProvider.get();

    /** Primary group of a player, or "" when LuckPerms has no loaded user for them. */
    String getPrimaryGroup(Player player) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        return user == null ? "" : user.getPrimaryGroup();
    }

    /**
     * The value the player was given for {@code node} or {@code node.*} (own nodes and inherited groups, in the
     * player's current contexts), or null when neither is set. Wildcards above the node ({@code veneer.*}, {@code *})
     * and Bukkit child permissions do not count: LuckPerms applies those when a permission is checked, not here.
     */
    Boolean explicit(Player player, String node) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        if (user == null) return null;
        QueryOptions options = lp.getContextManager().getQueryOptions(player);
        var map = user.getCachedData().getPermissionData(options).getPermissionMap();
        Boolean value = map.get(node);
        return value != null ? value : map.get(node + ".*");
    }
}
