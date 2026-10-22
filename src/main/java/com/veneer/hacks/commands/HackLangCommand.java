package com.veneer.hacks.commands;

import com.veneer.VeneerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class HackLangCommand implements CommandExecutor, TabCompleter {

    private final VeneerPlugin plugin;

    public HackLangCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("veneer.hacks.lang")) {
            sender.sendMessage(plugin.messages().get("no-permission", Map.of()));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(plugin.messages().prefixed("<red>Usage: /hacklang <player> [lang1,lang2,...]"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(plugin.messages().get("player-not-found",
                    Map.of("player", args[0])));
            return true;
        }
        if (plugin.hacks().langChecks().isChecking(target.getUniqueId())) {
            sender.sendMessage(plugin.messages().get("already-checking",
                    Map.of("player", target.getName())));
            return true;
        }

        Map<String, String> allLangs = plugin.hacks().config().getLanguages();
        Map<String, String> langs;

        if (args.length >= 2) {
            langs = new LinkedHashMap<>();
            for (String code : args[1].split(",")) {
                String trimmed = code.trim().toLowerCase();
                if (!allLangs.containsKey(trimmed)) {
                    sender.sendMessage(plugin.messages().prefixed("<red>Unknown language: <white>" + trimmed));
                    return true;
                }
                langs.put(trimmed, allLangs.get(trimmed));
            }
        } else {
            langs = allLangs;
        }

        Player initiator = sender instanceof Player p ? p : null;
        plugin.hacks().langChecks().startCheck(target, initiator, langs);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("veneer.hacks.lang")) return List.of();
        if (args.length == 1)
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        if (args.length == 2) {
            String typed   = args[1];
            String prefix  = typed.contains(",") ? typed.substring(0, typed.lastIndexOf(',') + 1) : "";
            String current = typed.contains(",") ? typed.substring(typed.lastIndexOf(',') + 1) : typed;
            return plugin.hacks().config().getLanguages().keySet().stream()
                    .filter(id -> id.startsWith(current.toLowerCase()))
                    .map(id -> prefix + id)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}