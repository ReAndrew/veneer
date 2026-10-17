package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.hacks.utils.FoliaScheduler;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

/**
 * Optional LuckPerms integration. Refreshes the alert-recipient list the moment a player's permissions
 * change (instead of sniffing /lp commands) and exposes the primary group for placeholders.
 * Only loaded when LuckPerms is present.
 */
public final class LuckPermsHook {

    private final VeneerPlugin plugin;
    private final LuckPerms lp;
    private final EventSubscription<UserDataRecalculateEvent> subscription;

    public LuckPermsHook(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.lp = LuckPermsProvider.get();
        this.subscription = lp.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, this::onRecalculate);
    }

    private void onRecalculate(UserDataRecalculateEvent event) {
        Player player = plugin.getServer().getPlayer(event.getUser().getUniqueId());
        if (player == null) return;
        FoliaScheduler.runAtEntity(plugin, player, () -> plugin.getAlertManager().updatePlayerAlertStatus(player));
    }

    /** Primary group of a player, or "" when LuckPerms has no loaded user for them. */
    public String getPrimaryGroup(Player player) {
        User user = lp.getUserManager().getUser(player.getUniqueId());
        return user == null ? "" : user.getPrimaryGroup();
    }

    public void close() {
        subscription.close();
    }
}
