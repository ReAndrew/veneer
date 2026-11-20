package com.veneer.hacks.managers;

import com.veneer.hacks.HacksModule;
import com.veneer.core.Messages;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.LangCheckData;
import com.veneer.hacks.VersionProbe;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.ClientVersions;
import com.veneer.hacks.utils.PacketSignProbe;
import com.veneer.hacks.utils.SignUtil;
import com.veneer.core.WrappedTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

import java.util.function.Function;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LangCheckManager {

    private static final String LANG_KEY      = "key.forward";
    private static final String LANG_FALLBACK = "\u27e6LANG_UNKNOWN\u27e7";

    private final VeneerPlugin plugin;
    private final Map<UUID, LangCheckData> activeChecks     = new ConcurrentHashMap<>();
    private final Set<UUID>                firstJoinChecked = ConcurrentHashMap.newKeySet();

    public LangCheckManager(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isChecking(UUID uuid)   { return activeChecks.containsKey(uuid); }
    public Set<UUID> getFirstJoinChecked() { return firstJoinChecked; }

    /** True when the edited sign at {@code loc} is this player's language probe sign. */
    public boolean ownsSign(UUID uuid, Location loc) {
        LangCheckData data = activeChecks.get(uuid);
        return data != null && SignUtil.sameBlock(data.getSignLocation(), loc);
    }

    /** Accepts a sign update packet from PacketEvents if it matches the current probe sign. */
    public boolean acceptSignUpdate(Player target, int x, int y, int z, String[] lines) {
        UUID uuid = target.getUniqueId();
        LangCheckData data = activeChecks.get(uuid);
        if (data == null) return false;
        Location loc = data.getSignLocation();
        if (loc == null) return false;
        if (loc.getBlockX() != x || loc.getBlockY() != y || loc.getBlockZ() != z) return false;
        if (!data.beginResponse()) return false;
        data.setRawLines(lines.clone());
        FoliaScheduler.runAtEntity(plugin, target, () -> handleResponse(target, lines));
        return true;
    }

    /** The raw lines of a sign update packet (network thread): kept when they answer this player's probe sign. */
    public void rawAnswer(UUID uuid, int x, int y, int z, String[] lines) {
        LangCheckData data = activeChecks.get(uuid);
        Location at = data == null ? null : data.getSignLocation();
        if (at != null && at.getBlockX() == x && at.getBlockY() == y && at.getBlockZ() == z) data.setRawLines(lines);
    }

    /** True when {@code loc} is any running check's probe sign. */
    public boolean isProbeSign(Location loc) {
        for (LangCheckData d : activeChecks.values())
            if (SignUtil.sameBlock(d.getSignLocation(), loc)) return true;
        return false;
    }

    /** The player left: drop the check and put the blocks back. */
    public void abort(UUID uuid) {
        LangCheckData data = activeChecks.remove(uuid);
        if (data == null) return;
        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        restoreSign(data, false);
        Player gone = Bukkit.getPlayer(uuid);
        plugin.module(HacksModule.class).scanEvent(gone != null ? gone.getName() : uuid.toString(), "lang", "aborted", 0);
    }

    public void startCheck(Player target, Player initiator, Map<String, String> languages) {
        startCheck(target, initiator, languages, 0, true, true);
    }

    /** The one sign answers both questions; {@code language} and {@code version} choose what is reported. */
    public void startCheck(Player target, Player initiator, Map<String, String> languages, boolean language, boolean version) {
        startCheck(target, initiator, languages, 0, language, version);
    }

    private void startCheck(Player target, Player initiator, Map<String, String> languages, int waits,
                            boolean language, boolean version) {
        UUID uuid = target.getUniqueId();

        if (plugin.bedrock().isBedrock(target)) {
            Function<Messages, Component> msg = m -> m.get("bedrock-skip", Map.of("player", target.getName()));
            if (initiator != null) initiator.sendMessage(msg.apply(plugin.messages().in(initiator)));
            return;
        }

        String oldClient = ClientVersions.tooOld(target, plugin.module(HacksModule.class).config().getMinClientVersion());
        if (oldClient != null) {
            Function<Messages, Component> msg = m -> m.get("client-too-old", Map.of("player", target.getName(),
                    "version", oldClient, "min", plugin.module(HacksModule.class).config().getMinClientVersion()));
            if (initiator != null) initiator.sendMessage(msg.apply(plugin.messages().in(initiator)));
            else plugin.alerts().send(msg);
            return;
        }


        // One sign spot and one sign editor per player: wait for a running mod scan (manual checks are refused)
        if (plugin.module(HacksModule.class).checks().isChecking(uuid)) {
            if (initiator != null) {
                initiator.sendMessage(plugin.messages().in(initiator).get("already-checking", Map.of("player", target.getName())));
            } else if (waits < 30) {
                FoliaScheduler.runAtEntityLater(plugin, target,
                        () -> { if (target.isOnline()) startCheck(target, null, languages, waits + 1, language, version); }, 40L);
            }
            return;
        }

        if (activeChecks.containsKey(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.messages().in(initiator).get("already-checking",
                        Map.of("player", target.getName())));
            return;
        }

        LangCheckData data = new LangCheckData(uuid,
                initiator != null ? initiator.getUniqueId() : null, languages);
        data.setReports(language, version);
        activeChecks.put(uuid, data);
        plugin.module(HacksModule.class).scanEvent(target.getName(), "lang", "started", 1);

        if (initiator != null)
            initiator.sendMessage(plugin.messages().in(initiator).get("lang-check-started",
                    Map.of("player", target.getName())));

        FoliaScheduler.runAtEntity(plugin, target, () -> setupLangSign(target, data));
    }

    private void setupLangSign(Player target, LangCheckData data) {
        UUID uuid = target.getUniqueId();
        if (activeChecks.get(uuid) != data) return;
        // Dead or in bed the sign cannot open: wait (up to a minute) instead of timing out
        if (SignUtil.cannotOpen(target)) {
            if (data.waitWhileBusy() > 60) {
                abort(uuid);
                return;
            }
            FoliaScheduler.runAtEntityLater(plugin, target, () -> setupLangSign(target, data), 20L);
            return;
        }

        Location signLoc = PacketSignProbe.findProbeLocation(target);
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.translatable(LANG_KEY, LANG_FALLBACK));

        // The probe answers outgrow what Paper keeps of a sign line: only asked when the raw packet can be read
        var protocol = ClientVersions.of(target);
        if (data.reportsVersion() && plugin.module(HacksModule.class).config().isVersionCheckEnabled() && plugin.module(HacksModule.class).readsRawSigns() && protocol != null) {
            Component[] probe = VersionProbe.lines(protocol);
            for (int i = 0; i < 3; i++) {
                lines.add(i < probe.length ? probe[i] : Component.empty());
            }
            data.setProbedFor(protocol);
        } else {
            lines.add(Component.empty());
            lines.add(Component.empty());
            lines.add(Component.empty());
        }

        data.setSignLocation(signLoc);
        data.setAwaitingResponse(true);

        if (!PacketSignProbe.open(target, signLoc, lines)) {
            activeChecks.remove(uuid);
            return;
        }
        PacketSignProbe.close(target);

        WrappedTask timeout = FoliaScheduler.runAtLocationLater(plugin, signLoc, () -> {
            LangCheckData current = activeChecks.get(uuid);
            if (current != data || !current.beginResponse()) return;
            Location asked = data.getSignLocation();
            restoreSign(data, false);
            if (target.isOnline() && SignUtil.lostSign(target, asked)) {
                if (data.waitWhileBusy() > 60) { abort(uuid); return; }
                FoliaScheduler.runAtEntity(plugin, target, () -> setupLangSign(target, data));
                return;
            }
            activeChecks.remove(uuid);
            plugin.module(HacksModule.class).scanEvent(target.getName(), "lang", "aborted", 0);
            Function<Messages, Component> msg = m -> m.get("lang-check-timeout",
                    Map.of("player", target.getName()));
            plugin.alerts().send(msg);
            plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);
        }, plugin.module(HacksModule.class).config().getLangTimeoutTicks());

        data.setTimeoutTask(timeout);
    }

    public void handleResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        LangCheckData data = activeChecks.remove(uuid);
        if (data == null) return;

        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        restoreSign(data, false);

        String response = lines.length > 0 ? lines[0].strip() : "";
        plugin.getLogger().info("[Hacks] LangCheck response from " + target.getName() + ": '" + response + "'");

        var langProfile = plugin.profileOf(uuid);
        boolean blocked = response.isEmpty() || response.equals(LANG_FALLBACK);
        if (!data.reportsLanguage()) {
            String note = blocked ? null : checkVersion(target, data);
            String shown = note != null ? note : "unknown";
            Function<Messages, Component> msg = m -> m.get("version-check-result",
                    Map.of("player", target.getName(), "result", shown));
            plugin.alerts().send(msg);
            plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);
            String versionName = target.getName(), versionId = uuid.toString();
            String versionChecker = data.getInitiatorUUID() != null
                    ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID())).map(Player::getName).orElse("Console")
                    : "AutoCheck";
            FoliaScheduler.runAsync(plugin, () -> {
                plugin.database().scans().saveLangScan(versionName, versionId, versionChecker, "Version check", "Version", shown);
                plugin.module(HacksModule.class).scanEvent(versionName, "lang", "done", 0);
            });
            return;
        }

        if (blocked) {
            if (langProfile != null) langProfile.setLanguageProbe(null);
            Function<Messages, Component> msg = m -> m.get("lang-check-protected",
                    Map.of("player", target.getName()));
            plugin.alerts().send(msg);
            plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);
            String name = target.getName(), id = target.getUniqueId().toString();
            String checker = data.getInitiatorUUID() != null
                    ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                    .map(Player::getName).orElse("AutoCheck")
                    : "AutoCheck";
            FoliaScheduler.runAsync(plugin, () -> {
                plugin.database().scans().saveScan("lang", name, id, checker, "Lang check", false);
                plugin.module(HacksModule.class).scanEvent(name, "lang", "done", 0);
            });
            return;
        }

        // A language may list several texts ("a|b", game versions differ), and several languages can share a
        // text (pt_br and pt_pt, the English variants): every matching language is reported
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, String> entry : data.getLanguages().entrySet()) {
            for (String text : entry.getValue().split("\\|")) {
                if (text.strip().equalsIgnoreCase(response)) { matches.add(entry.getKey()); break; }
            }
        }
        String detected = matches.isEmpty() ? null : String.join(", ", matches);

        String display = detected != null ? detected : "Unknown";
        if (langProfile != null) langProfile.setLanguageProbe(detected);
        String versionNote = data.reportsVersion() ? checkVersion(target, data) : null;
        String msgKey  = detected != null ? "lang-check-complete" : "lang-check-unknown";
        Function<Messages, Component> msg = m -> m.get(msgKey,
                Map.of("player", target.getName(), "lang", display, "response", response));
        plugin.alerts().send(msg);
        plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);

        String checkerName = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : "AutoCheck";

        // Off the main thread (database I/O); scan and result in one transaction, so no scan is left without its result
        String name = target.getName(), id = target.getUniqueId().toString();
        String stored = versionNote == null ? response : response + " | " + versionNote;
        FoliaScheduler.runAsync(plugin, () -> {
            plugin.database().scans().saveLangScan(name, id, checkerName, "Lang check", display, stored);
            plugin.module(HacksModule.class).scanEvent(name, "lang", "done", 0);
        });

        ConfigManager cfg = plugin.module(HacksModule.class).config();
        if (cfg.isLangDiscordEnabled()) {
            var profile = plugin.profileOf(target.getUniqueId());
            Map<String, String> values = new java.util.HashMap<>();
            values.put("player", target.getName());
            values.put("name", target.getName()); // &name& of older configs
            values.put("uuid", target.getUniqueId().toString());
            values.put("checker", checkerName);
            values.put("lang", detected != null ? detected : "unknown (" + response + ")");
            values.put("response", response);
            values.put("brand", profile != null && profile.getBrand() != null ? profile.getBrand() : "unknown");
            values.put("version", com.veneer.core.ClientVersions.name(target));
            plugin.webhook().report(cfg.getLangWebhookUrl(), cfg.getLangEmbedColor(), cfg.getLangDiscordTitle(),
                    cfg.getLangDiscordMessage(), cfg.isLangDiscordUseComponentsV2(), target, values);
        }
    }


    /**
     * Compares the real client version read from the sign with the protocol version the client connects with.
     * Called only when line 0 came back translated, so a client that blocks translations is not misread as new.
     * Returns a note for the scan history, or null when the probe is off or told nothing.
     */
    private String checkVersion(Player target, LangCheckData data) {
        ConfigManager cfg = plugin.module(HacksModule.class).config();
        var protocol = data.getProbedFor();
        String[] raw = data.getRawLines();
        if (!cfg.isVersionCheckEnabled() || protocol == null || raw == null) return null;
        VersionProbe.Range real = VersionProbe.evaluate(raw, protocol);
        if (plugin.config().debug())
            plugin.getLogger().info("[Hacks] Version probe answers from " + target.getName() + ": " + String.join(" / ", raw));
        if (!real.known()) return null;
        String claimed = protocol == null ? "?" : VersionProbe.protocolName(protocol);
        boolean mismatch = !real.fits(protocol);

        var profile = plugin.profile(target);
        profile.setVersionProbe(real.text(), claimed, mismatch);
        plugin.getLogger().info("[Hacks] Version probe for " + target.getName() + ": client " + real.text()
                + ", connects as " + claimed + (mismatch ? " - MISMATCH" : ""));
        if (mismatch) {
            plugin.alerts().send(m -> m.get("version-mismatch",
                    Map.of("player", target.getName(), "real", real.text(), "claimed", claimed)));
            String cmd = cfg.getVersionMismatchCommand();
            if (!cmd.isBlank()) {
                String run = cmd.replace("%player%", target.getName()).replace("%real%", real.text()).replace("%claimed%", claimed);
                FoliaScheduler.runGlobal(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run));
            }
        }
        return "client " + real.text() + ", connects as " + claimed + (mismatch ? " (mismatch)" : "");
    }

    private void restoreSign(LangCheckData data, boolean now) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        Player player = Bukkit.getPlayer(data.getTargetUUID());
        if (player != null && player.isOnline()) {
            PacketSignProbe.restore(player, loc);
        }
        data.setSignLocation(null);
        data.setAwaitingResponse(false);
    }

    public void cleanup() {
        for (LangCheckData d : activeChecks.values()) {
            if (d.getTimeoutTask() != null) d.getTimeoutTask().cancel();
            restoreSign(d, true);
        }
        activeChecks.clear();
    }
}
