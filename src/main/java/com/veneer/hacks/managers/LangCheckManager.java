package com.veneer.hacks.managers;

import com.veneer.VeneerPlugin;
import com.veneer.hacks.LangCheckData;
import com.veneer.core.FoliaScheduler;
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

    public void startCheck(Player target, Player initiator, Map<String, String> languages) {
        UUID uuid = target.getUniqueId();

        if (activeChecks.containsKey(uuid)) {
            if (initiator != null)
                initiator.sendMessage(plugin.messages().get("already-checking",
                        Map.of("player", target.getName())));
            return;
        }

        LangCheckData data = new LangCheckData(uuid,
                initiator != null ? initiator.getUniqueId() : null, languages);
        activeChecks.put(uuid, data);

        if (initiator != null)
            initiator.sendMessage(plugin.messages().get("lang-check-started",
                    Map.of("player", target.getName())));

        FoliaScheduler.runAtEntity(plugin, target, () -> setupLangSign(target, data));
    }

    private void setupLangSign(Player target, LangCheckData data) {
        UUID uuid = target.getUniqueId();

        Location signLoc = SignUtil.findAirBlock(target);
        if (signLoc == null) { activeChecks.remove(uuid); return; }

        Block block = signLoc.getBlock();
        BlockState originalState = block.getState();

        Location belowLoc   = signLoc.clone().subtract(0, 1, 0);
        Block    belowBlock = belowLoc.getBlock();
        boolean  placedBarrier = belowBlock.getType().isAir();
        if (placedBarrier) belowBlock.setType(Material.BARRIER, false);

        block.setType(Material.OAK_SIGN, false);
        BlockState freshState = block.getState();
        if (!(freshState instanceof Sign sign)) {
            originalState.update(true, false);
            if (placedBarrier) belowBlock.setType(Material.AIR, false);
            activeChecks.remove(uuid);
            return;
        }

        sign.getSide(Side.FRONT).line(0, Component.translatable(LANG_KEY, LANG_FALLBACK));
        sign.update(true, false);

        data.setSignLocation(signLoc);
        data.setOriginalState(originalState);
        data.setBarrierPlaced(placedBarrier);
        data.setBarrierLocation(belowLoc);

        SignUtil.setAllowedEditor(signLoc, uuid, plugin);

        FoliaScheduler.runAtEntity(plugin, target, () -> {
            if (!activeChecks.containsKey(uuid)) return;
            SignUtil.sendBlockEntityPacket(target, signLoc, plugin);
            FoliaScheduler.runAtEntityLater(plugin, target, () -> {
                if (!activeChecks.containsKey(uuid)) return;
                SignUtil.sendOpenSignPacket(target, signLoc, plugin);
                target.sendBlockChange(signLoc, Material.AIR.createBlockData());
            }, 1L);
        });

        WrappedTask timeout = FoliaScheduler.runAtLocationLater(plugin, signLoc, () -> {
            if (!activeChecks.containsKey(uuid)) return;
            restoreSign(data);
            activeChecks.remove(uuid);
            Component msg = plugin.messages().get("lang-check-timeout",
                    Map.of("player", target.getName()));
            plugin.alerts().send(msg);
            plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);
        }, plugin.hacks().config().getLangTimeoutTicks());

        data.setTimeoutTask(timeout);
    }

    public void handleResponse(Player target, String[] lines) {
        UUID uuid = target.getUniqueId();
        LangCheckData data = activeChecks.remove(uuid);
        if (data == null) return;

        if (data.getTimeoutTask() != null) data.getTimeoutTask().cancel();
        restoreSign(data);

        String response = lines.length > 0 ? lines[0].strip() : "";
        plugin.getLogger().info("[Hacks] LangCheck response from " + target.getName() + ": '" + response + "'");

        if (response.isEmpty() || response.equals(LANG_FALLBACK)) {
            Component msg = plugin.messages().get("lang-check-protected",
                    Map.of("player", target.getName()));
            plugin.alerts().send(msg);
            plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);
            plugin.database().saveScan("lang", target.getName(),
                    target.getUniqueId().toString(),
                    data.getInitiatorUUID() != null
                            ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                            .map(Player::getName).orElse("AutoCheck")
                            : "AutoCheck",
                    "Lang check", false);
            return;
        }

        String detected = null;
        for (Map.Entry<String, String> entry : data.getLanguages().entrySet()) {
            if (entry.getValue().equalsIgnoreCase(response)) { detected = entry.getKey(); break; }
        }

        String display = detected != null ? detected : "Unknown";
        String msgKey  = detected != null ? "lang-check-complete" : "lang-check-unknown";
        Component msg  = plugin.messages().get(msgKey,
                Map.of("player", target.getName(), "lang", display, "response", response));
        plugin.alerts().send(msg);
        plugin.alerts().notifyInitiator(data.getInitiatorUUID(), msg);

        String checkerName = data.getInitiatorUUID() != null
                ? Optional.ofNullable(Bukkit.getPlayer(data.getInitiatorUUID()))
                .map(Player::getName).orElse("Console")
                : "AutoCheck";

        long scanId = plugin.database().saveScan("lang", target.getName(),
                target.getUniqueId().toString(), checkerName, "Lang check", false);
        plugin.database().saveLangResult(scanId, display, response);

        ConfigManager cfg = plugin.hacks().config();
        if (cfg.isLangDiscordEnabled()) {
            String description = cfg.getLangDiscordMessage()
                    .replace("&name&",    target.getName())
                    .replace("&checker&", checkerName)
                    .replace("&lang&",    detected != null ? detected : "Unknown (" + response + ")");
            plugin.webhook().report(cfg.getLangWebhookUrl(), cfg.getLangEmbedColor(), "Veneer language check",
                    description, cfg.isLangDiscordUseComponentsV2());
        }
    }


    private void restoreSign(LangCheckData data) {
        Location loc = data.getSignLocation();
        if (loc == null) return;
        FoliaScheduler.runAtLocation(plugin, loc, () -> {
            try { if (data.getOriginalState() != null) data.getOriginalState().update(true, false); }
            catch (Exception e) { plugin.getLogger().warning("[Hacks] LangRestore: " + e.getMessage()); }
            if (data.isBarrierPlaced() && data.getBarrierLocation() != null) {
                try { data.getBarrierLocation().getBlock().setType(Material.AIR, false); }
                catch (Exception e) { plugin.getLogger().warning("[Hacks] LangBarrier: " + e.getMessage()); }
            }
        });
    }

    public void cleanup() {
        for (LangCheckData d : activeChecks.values()) {
            if (d.getTimeoutTask() != null) d.getTimeoutTask().cancel();
            restoreSign(d);
        }
        activeChecks.clear();
    }
}