package com.veneer.core;

import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackResult;
import com.veneer.rptrack.RPTrackManager;
import net.kyori.adventure.text.Component;
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
        send(sender, "<gold>Veneer " + plugin.version() + "</gold>");
        send(sender, "<gray>/" + label + " info <player></gray> <white>- client, mod scan and RPTrack data of a player</white>");
        send(sender, "<gray>/" + label + " alerts</gray> <white>- toggle your staff alerts</white>");
        send(sender, "<gray>/" + label + " reload</gray> <white>- reload config.yml and the messages</white>");
        send(sender, "<gray>/" + label + " version</gray> <white>- running and latest release</white>");
    }

    private void reload(CommandSender sender) {
        if (denied(sender, "veneer.admin")) return;
        plugin.reloadAll();
        sender.sendMessage(plugin.messages().get("reload-done"));
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>Only players can toggle alerts.</red>");
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
        send(sender, "<gray>Running Veneer</gray> <white>" + current + "</white>");
        plugin.updates().lookup(false);
        UpdateChecker.Status s = plugin.updates().status();
        if (s == null) send(sender, "<gray>Latest release: checking, run this again in a moment.</gray>");
        else if (s.latest() == null) send(sender, "<gray>No release is published on GitHub yet.</gray>");
        else if (s.updateAvailable()) send(sender, "<green>Version " + s.latest() + " is available:</green> <aqua>" + s.url() + "</aqua>");
        else send(sender, "<green>You are on the latest release (" + s.latest() + ").</green>");
    }

    private void info(CommandSender sender, String label, String[] args) {
        if (denied(sender, "veneer.admin")) return;
        if (args.length < 2) {
            send(sender, "<red>Usage: /" + label + " info <player></red>");
            return;
        }
        String name = args[1];
        Player online = Bukkit.getPlayerExact(name);
        var offline = Bukkit.getOfflinePlayer(name);
        String uuid = (online != null ? online : offline).getUniqueId().toString();

        send(sender, "<gold>Veneer</gold> <gray>report for</gray> <white>" + name + "</white>"
                + (online == null ? " <gray>(offline)</gray>" : ""));
        if (online != null) clientSection(sender, online);

        FoliaScheduler.runAsync(plugin, () -> {
            scanSection(sender, name);
            rptrackSection(sender, online, uuid);
        });
    }

    private void clientSection(CommandSender sender, Player player) {
        PlayerProfile profile = plugin.profileOf(player.getUniqueId());
        String brand = plugin.brandOf(player) == null ? "unknown" : plugin.brandOf(player);
        int channels = profile == null ? 0 : profile.getChannels().size();
        String type = profile == null ? "unknown" : profile.clientType().name().toLowerCase(Locale.ROOT);
        send(sender, "<gray>Client:</gray> <white>" + escape(brand) + "</white> <gray>(" + type + ", " + channels + " channels)</gray>");

        List<String> flags = new ArrayList<>();
        if (plugin.bedrock().isBedrock(player)) flags.add("bedrock");
        if (plugin.spoof().running() && plugin.spoof().isPlayerSpoofing(player)) flags.add("<red>flagged by spoof detection</red>");
        if (!flags.isEmpty()) send(sender, "<gray>Status:</gray> <white>" + String.join(", ", flags) + "</white>");

        String group = plugin.hooks().primaryGroup(player);
        if (!group.isEmpty()) send(sender, "<gray>Group:</gray> <white>" + group + "</white>");
    }

    @SuppressWarnings("unchecked")
    private void scanSection(CommandSender sender, String name) {
        if (!plugin.hacks().running()) return;
        List<Map<String, Object>> scans = plugin.database().getPlayerScans(name).stream()
                .filter(row -> "hack".equals(row.get("type"))).toList();
        if (scans.isEmpty()) {
            send(sender, "<gray>Mod scan:</gray> <white>never scanned</white>");
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
        send(sender, "<gray>Mod scan:</gray> <white>" + when + "</white> <gray>-</gray> "
                + (found.isEmpty() ? "<green>clean</green>" : "<red>" + escape(found) + "</red>")
                + " <gray>(" + scans.size() + " scans on record)</gray>");
    }

    private void rptrackSection(CommandSender sender, Player online, String uuid) {
        RPTrackManager rp = plugin.rptrack();
        if (!rp.isEnabled()) return;
        long mask = rp.getMask(uuid);
        if (mask == 0) {
            send(sender, "<gray>RPTrack:</gray> <white>no fingerprint stored</white>");
            return;
        }
        List<String> linked = rp.findLinked(uuid, mask);
        send(sender, "<gray>RPTrack:</gray> <white>" + Long.bitCount(mask) + " cached packs</white> <gray>- linked accounts:</gray> <white>"
                + (linked.isEmpty() ? "none" : escape(String.join(", ", linked))) + "</white>");
    }

    private void send(CommandSender sender, String miniMessage) {
        Component c = plugin.messages().prefixed(miniMessage);
        sender.sendMessage(c);
    }

    /** Player-controlled text (client brand, mod names) must not be parsed as MiniMessage. */
    private static String escape(String text) {
        return text.replace("<", "\\<");
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
