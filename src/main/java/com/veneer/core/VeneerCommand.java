package com.veneer.core;

import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackResult;
import com.veneer.rptrack.RPTrackManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code /veneer}: the one command that spans modules. {@code info} puts the client data, the latest
 * mod scan and the RPTrack link of a player on one screen; {@code reload} reloads everything.
 */
public final class VeneerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("info", "alerts", "reload", "version", "help");

    private final VeneerPlugin plugin;

    public VeneerCommand(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "alerts" -> alerts(sender);
            case "info" -> info(sender, label, args);
            case "version" -> version(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private boolean denied(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return false;
        sender.sendMessage(plugin.messages().get("no-permission"));
        return true;
    }

    private void help(CommandSender sender, String label) {
        for (String key : List.of("title", "info", "alerts", "reload", "version"))
            plugin.messages().send(sender, "veneer-help-" + key, "label", label, "version", plugin.version());
    }

    private void reload(CommandSender sender) {
        if (denied(sender, "veneer.admin")) return;
        plugin.reloadAll();
        sender.sendMessage(plugin.messages().get("reload-done"));
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "veneer-alerts-players-only");
            return;
        }
        if (!player.hasPermission(Alerts.PERMISSION)) {
            sender.sendMessage(plugin.messages().get("no-permission"));
            return;
        }
        boolean on = plugin.alerts().toggle(player.getUniqueId());
        sender.sendMessage(plugin.messages().get(on ? "alerts-enabled" : "alerts-disabled"));
    }

    private void version(CommandSender sender) {
        if (denied(sender, "veneer.admin")) return;
        String current = plugin.version();
        plugin.messages().send(sender, "veneer-running", "version", current);
        plugin.updates().lookup(false);
        UpdateChecker.Status s = plugin.updates().status();
        if (s == null) plugin.messages().send(sender, "veneer-latest-checking");
        else if (s.latest() == null) plugin.messages().send(sender, "veneer-latest-none");
        else if (s.updateAvailable()) plugin.messages().send(sender, "veneer-update-available", "latest", s.latest(), "url", s.url());
        else plugin.messages().send(sender, "veneer-latest-ok", "latest", s.latest());
    }

    private void info(CommandSender sender, String label, String[] args) {
        if (denied(sender, "veneer.admin")) return;
        if (args.length < 2) {
            plugin.messages().send(sender, "veneer-info-usage", "label", label);
            return;
        }
        String name = args[1];
        Player online = Bukkit.getPlayerExact(name);
        var offline = Bukkit.getOfflinePlayer(name);
        String uuid = (online != null ? online : offline).getUniqueId().toString();

        plugin.messages().send(sender, "veneer-info-title", "player", name,
                "offline", online == null ? plugin.messages().raw("veneer-info-offline") : new Messages.Raw(""));
        if (online != null) clientSection(sender, online);

        FoliaScheduler.runAsync(plugin, () -> {
            scanSection(sender, name);
            rptrackSection(sender, online, uuid);
        });
    }

    private void clientSection(CommandSender sender, Player player) {
        PlayerProfile profile = plugin.profileOf(player.getUniqueId());
        String brand = plugin.brandOf(player) == null ? plugin.messages().plain("veneer-unknown") : plugin.brandOf(player);
        int channels = profile == null ? 0 : profile.getChannels().size();
        String type = profile == null ? plugin.messages().plain("veneer-unknown") : profile.clientType().name().toLowerCase(Locale.ROOT);
        plugin.messages().send(sender, "veneer-info-client", "brand", brand, "type", type, "channels", channels);
        if (profile != null && profile.getVersionReal() != null)
            plugin.messages().send(sender, profile.isVersionMismatch() ? "veneer-info-version-mismatch" : "veneer-info-version",
                    "real", profile.getVersionReal(), "claimed", profile.getVersionClaimed());

        List<String> flags = new ArrayList<>();
        if (plugin.bedrock().isBedrock(player)) flags.add(plugin.messages().getRaw("veneer-flag-bedrock"));
        if (plugin.spoof().running() && plugin.spoof().isPlayerSpoofing(player)) flags.add(plugin.messages().getRaw("veneer-flag-spoof"));
        if (!flags.isEmpty()) plugin.messages().send(sender, "veneer-info-status", "flags", new Messages.Raw(String.join(", ", flags)));

        String group = plugin.hooks().primaryGroup(player);
        if (!group.isEmpty()) plugin.messages().send(sender, "veneer-info-group", "group", group);
    }

    @SuppressWarnings("unchecked")
    private void scanSection(CommandSender sender, String name) {
        if (!plugin.hacks().running()) return;
        List<Map<String, Object>> scans = plugin.database().getPlayerScans(name).stream()
                .filter(row -> "hack".equals(row.get("type"))).toList();
        if (scans.isEmpty()) {
            plugin.messages().send(sender, "veneer-scan-never");
            return;
        }
        Map<String, Object> latest = scans.get(0);
        List<Map<String, Object>> results = (List<Map<String, Object>>) latest.get("results");
        String found = results.stream()
                .filter(r -> HackResult.DETECTED.name().equals(r.get("result")) || HackResult.PROTECTED.name().equals(r.get("result"))
                        || HackResult.TIMEOUT.name().equals(r.get("result")))
                .map(r -> r.get("hack_name") + " (" + String.valueOf(r.get("result")).toLowerCase(Locale.ROOT) + ")")
                .collect(Collectors.joining(", "));
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(((Number) latest.get("timestamp")).longValue()));
        plugin.messages().send(sender, "veneer-scan-line", "when", when, "count", scans.size(),
                "result", found.isEmpty() ? plugin.messages().raw("veneer-scan-clean")
                        : new Messages.Raw(plugin.messages().plain("veneer-scan-found", "found", found)));
    }

    private void rptrackSection(CommandSender sender, Player online, String uuid) {
        RPTrackManager rp = plugin.rptrack();
        if (!rp.isEnabled()) return;
        long mask = rp.getMask(uuid);
        if (mask == 0) {
            plugin.messages().send(sender, "veneer-rp-none");
            return;
        }
        List<String> linked = rp.findLinked(uuid, mask);
        plugin.messages().send(sender, "veneer-rp-line", "packs", Long.bitCount(mask),
                "linked", linked.isEmpty() ? plugin.messages().plain("veneer-rp-linked-none") : String.join(", ", linked));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("info"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
