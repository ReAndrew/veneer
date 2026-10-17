package me.branduzzo.checkHacks.commands;

import com.invalider.InvaliderPlugin;
import me.branduzzo.checkHacks.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public class AlertsCommand implements CommandExecutor {

    private final InvaliderPlugin plugin;

    public AlertsCommand(InvaliderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.parseRaw(plugin,
                    plugin.getHackConfigManager().getPrefix() + "<red>Only players can use this command.", Map.of()));
            return true;
        }
        if (!player.hasPermission("checkhacks.alerts")) {
            player.sendMessage(MessageUtil.parse(plugin, "no-permission", Map.of()));
            return true;
        }
        plugin.toggleAlerts(player.getUniqueId());
        String key = plugin.hasAlertsEnabled(player.getUniqueId()) ? "alerts-enabled" : "alerts-disabled";
        player.sendMessage(MessageUtil.parse(plugin, key, Map.of()));
        return true;
    }
}