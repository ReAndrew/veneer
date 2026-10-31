package com.veneer.hacks.listeners;

import net.kyori.adventure.text.Component;
import com.veneer.core.Messages;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.core.FoliaScheduler;
import org.bukkit.entity.Player;

import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JoinListener {

    private final VeneerPlugin plugin;
    private final Set<UUID> alreadyHackChecked = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alreadyLangChecked = ConcurrentHashMap.newKeySet();

    public JoinListener(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (plugin.bypasses(player, "hacks", true)) return;

        if (plugin.hacks().config().isJoinCheckEnabled()) {
            boolean firstOnly = plugin.hacks().config().isOnlyFirstJoin();
            FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                if (!player.isOnline()) return;
                List<HackDefinition> hacks = plugin.hacks().config().getJoinCheckHacks();
                if (hacks.isEmpty()) return;
                // Marked only when the scan really runs: a join skipped by an empty list or a disconnect
                // must not consume the player's one first-join chance
                if (firstOnly && !alreadyHackChecked.add(uuid)) return;
                plugin.alerts().send(m -> m.get("join-check", Map.of("player", player.getName())));
                plugin.hacks().checks().startCheck(player, null, hacks, true, "Auto-join check");
            }, 60L);
        }

        if (plugin.hacks().config().isLangJoinCheckEnabled()) {
            boolean firstOnly = plugin.hacks().config().isLangOnlyFirstJoin();
            FoliaScheduler.runAtEntityLater(plugin, player, () -> {
                if (!player.isOnline()) return;
                Map<String, String> langs = plugin.hacks().config().getLanguages();
                if (langs.isEmpty()) return;
                if (firstOnly && !alreadyLangChecked.add(uuid)) return;
                plugin.alerts().send(m -> m.get("lang-join-check", Map.of("player", player.getName())));
                plugin.hacks().langChecks().startCheck(player, null, langs);
            }, 80L);
        }
    }

    /** Players who left are forgotten, so their UUIDs do not pile up in memory forever. */
    public void onQuit(UUID uuid) {
        alreadyHackChecked.remove(uuid);
        alreadyLangChecked.remove(uuid);
    }
}
