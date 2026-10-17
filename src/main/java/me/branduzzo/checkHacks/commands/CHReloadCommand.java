package me.branduzzo.checkHacks.commands;

import com.invalider.InvaliderPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Map;

public class CHReloadCommand implements CommandExecutor {

    private final InvaliderPlugin plugin;

    public CHReloadCommand(InvaliderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("checkhacks.reload")) {
            sender.sendMessage(plugin.getMessageManager().get("no-permission"));
            return true;
        }
        plugin.getHackConfigManager().reload();
        plugin.getMessageManager().load();
        sender.sendMessage(plugin.getMessageManager().get("reload-done"));
        return true;
    }
}