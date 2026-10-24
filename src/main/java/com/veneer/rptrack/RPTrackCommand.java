package com.veneer.rptrack;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

/** /rptrack <player> - shows the stored cache fingerprint and accounts sharing it. */
public class RPTrackCommand implements CommandExecutor {

    private final VeneerPlugin plugin;

    public RPTrackCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        RPTrackManager fp = plugin.rptrack();
        if (fp == null || !fp.isEnabled()) {
            plugin.messages().send(sender, "rptrack-disabled");
            return true;
        }
        if (args.length < 1) {
            plugin.messages().send(sender, "rptrack-usage", "label", label);
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        String uuid = target.getUniqueId().toString();
        FoliaScheduler.runAsync(plugin, () -> {
            long mask = fp.getMask(uuid);
            if (mask == 0) {
                plugin.messages().send(sender, "rptrack-none", "player", args[0]);
                return;
            }
            List<String> linked = fp.findLinked(uuid, mask);
            plugin.messages().send(sender, "rptrack-cache", "player", args[0],
                    "mask", Long.toBinaryString(mask), "packs", Long.bitCount(mask));
            plugin.messages().send(sender, "rptrack-linked",
                    "linked", linked.isEmpty() ? plugin.messages().plain("veneer-rp-linked-none") : String.join(", ", linked));
        });
        return true;
    }
}
