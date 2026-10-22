package com.veneer.hooks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/** Optional LuckPerms integration that exposes a player's primary group. Only loaded when LuckPerms is present. */
final class LuckPermsHook {

    private final LuckPerms lp = LuckPermsProvider.get();

    /** Primary group of a player, or "" when LuckPerms has no loaded user for them. */
    String getPrimaryGroup(Player player) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        return user == null ? "" : user.getPrimaryGroup();
    }
}
