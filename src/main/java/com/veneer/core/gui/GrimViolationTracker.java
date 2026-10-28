package com.veneer.core.gui;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.event.events.FlagEvent;
import com.veneer.VeneerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Isolated so GrimAPI classes are only loaded when Grim is installed. */
final class GrimViolationTracker {

    record Violation(UUID playerUuid, String playerName, String check, String verbose, long time) {}

    private static final int MAX = 500;
    private final ConcurrentLinkedDeque<Violation> recent = new ConcurrentLinkedDeque<>();

    GrimViolationTracker(VeneerPlugin plugin) {
        GrimAbstractAPI api = GrimAPIProvider.get();
        api.getEventBus().get(FlagEvent.class).onFlag(api.getGrimPlugin(plugin), (user, check, verbose, cancelled) -> {
            Player p = Bukkit.getPlayer(user.getUniqueId());
            String name = p != null ? p.getName() : user.getUniqueId().toString().substring(0, 8);
            add(new Violation(user.getUniqueId(), name, String.valueOf(check), verbose != null ? verbose : "", System.currentTimeMillis()));
            return cancelled;
        });
    }

    private void add(Violation v) {
        recent.addFirst(v);
        while (recent.size() > MAX) recent.removeLast();
    }

    List<Violation> getRecent(int limit) {
        List<Violation> out = new ArrayList<>(Math.min(limit, recent.size()));
        for (Violation v : recent) {
            out.add(v);
            if (out.size() >= limit) break;
        }
        return out;
    }

    List<Violation> getByPlayer(UUID uuid, int limit) {
        List<Violation> out = new ArrayList<>();
        for (Violation v : recent) {
            if (uuid.equals(v.playerUuid())) {
                out.add(v);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    boolean hasViolations(UUID uuid) {
        for (Violation v : recent) if (uuid.equals(v.playerUuid())) return true;
        return false;
    }
}
