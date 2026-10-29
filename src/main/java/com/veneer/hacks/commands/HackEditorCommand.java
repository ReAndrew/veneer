package com.veneer.hacks.commands;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class HackEditorCommand implements CommandExecutor {

    /** Panel actions beyond viewing the scan history, each {@code veneer.web.<name>}. */
    private static final List<String> WEB_PERMS = List.of("scan", "delete", "links", "ips");

    private final VeneerPlugin plugin;

    public HackEditorCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "editor-players-only");
            return true;
        }
        if (!player.hasPermission("veneer.hacks.editor")) {
            player.sendMessage(plugin.messages().in(player).get("no-permission"));
            return true;
        }
        if (!plugin.hacks().config().isWebEditorEnabled()) {
            player.sendMessage(plugin.messages().in(player).get("editor-disabled"));
            return true;
        }

        String uuid = player.getUniqueId().toString(), name = player.getName();
        // /hackeditor revoke: every link issued to this player stops working (a link that leaked, rights taken away)
        if (args.length > 0 && args[0].equalsIgnoreCase("revoke")) {
            FoliaScheduler.runAsync(plugin, () -> {
                plugin.database().revokeTokens(uuid);
                FoliaScheduler.runAtEntity(plugin, player, () -> player.sendMessage(plugin.messages().in(player).get("editor-revoked")));
            });
            return true;
        }
        // The token carries the rights the player has now; changing them later needs a new /hackeditor
        String perms = String.join(",", WEB_PERMS.stream().filter(p -> player.hasPermission("veneer.web." + p)).toList());
        int expire = plugin.hacks().config().getTokenExpireMinutes();
        String host = plugin.hacks().config().getWebHost();
        int    port = plugin.hacks().config().getWebPort();
        // The insert waits for the database connection, which the web panel may be holding: not on the main thread
        FoliaScheduler.runAsync(plugin, () -> {
            String token = plugin.database().saveToken(uuid, name, expire, perms);
            if (token == null) {
                FoliaScheduler.runAtEntity(plugin, player, () -> player.sendMessage(plugin.messages().in(player).get("editor-failed")));
                return;
            }
            String base = plugin.hacks().config().getPublicUrl();
            String url  = (base.isEmpty() ? "http://" + host + ":" + port : base) + "/editor?token=" + token;
            FoliaScheduler.runAtEntity(plugin, player,
                    () -> player.sendMessage(plugin.messages().in(player).get("editor-url", Map.of("url", url))));
        });
        return true;
    }
}
