package com.veneer.hacks.commands;

import com.veneer.VeneerPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Map;

public class HackReloadCommand implements CommandExecutor {

    private final VeneerPlugin plugin;

    public HackReloadCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("veneer.hacks.reload")) {
            sender.sendMessage(plugin.getMessageManager().get("no-permission"));
            return true;
        }
        plugin.getHackConfigManager().reload();
        plugin.getMessageManager().load();
        sender.sendMessage(plugin.getMessageManager().get("reload-done"));
        return true;
    }
}