package com.veneer.hacks.listeners;

import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.core.FoliaScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JoinListener implements Listener {

    private final VeneerPlugin plugin;
    private final Set<UUID> alreadyHackChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyLangChecked = ConcurrentHashMap.newKeySet();

    public JoinListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (plugin.hacks().config().isJoinCheckEnabled()) {
            if (!plugin.hacks().config().isOnlyFirstJoin() || alreadyHackChecked.add(uuid)) {
                FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    java.util.List<HackDefinition> hacks = plugin.hacks().config().getJoinCheckHacks();
                    if (hacks.isEmpty()) return;
                    plugin.alerts().send(
                            plugin.messages().get("join-check", Map.of("player", player.getName())));
                    plugin.hacks().checks().startCheck(player, null, hacks, true, "Auto-join check");
                }, 60L);
            }
        }

        if (plugin.hacks().config().isLangJoinCheckEnabled()) {
            boolean isFirst = alreadyLangChecked.add(uuid);
            if (!plugin.hacks().config().isLangOnlyFirstJoin() || isFirst) {
                FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                    if (!player.isOnline()) return;
                    Map<String, String> langs = plugin.hacks().config().getLanguages();
                    if (langs.isEmpty()) return;
                    plugin.alerts().send(
                            plugin.messages().get("lang-join-check", Map.of("player", player.getName())));
                    plugin.hacks().langChecks().startCheck(player, null, langs);
                }, 80L);
            }
        }
    }
}
