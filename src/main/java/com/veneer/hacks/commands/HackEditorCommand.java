package com.veneer.hacks.commands;

import com.veneer.VeneerPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class HackEditorCommand implements CommandExecutor {

    private final VeneerPlugin plugin;

    public HackEditorCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open the editor.");
            return true;
        }
        if (!player.hasPermission("veneer.hacks.editor")) {
            player.sendMessage(plugin.getMessageManager().get("no-permission"));
            return true;
        }
        if (!plugin.getHackConfigManager().isWebEditorEnabled()) {
            player.sendMessage(plugin.getMessageManager().get("editor-disabled"));
            return true;
        }

        String token = plugin.getDatabaseManager().saveToken(
                player.getUniqueId().toString(),
                player.getName(),
                plugin.getHackConfigManager().getTokenExpireMinutes());

        String host = plugin.getHackConfigManager().getWebHost();
        int    port = plugin.getHackConfigManager().getWebPort();
        String url  = "http://" + host + ":" + port + "/editor?token=" + token;

        player.sendMessage(plugin.getMessageManager().get("editor-url", Map.of("url", url)));
        return true;
    }
}