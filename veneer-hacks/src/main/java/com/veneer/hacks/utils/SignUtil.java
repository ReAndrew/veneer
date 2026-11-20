package com.veneer.hacks.utils;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Utility checks for sign probes: player availability and reachability.
 */
public final class SignUtil {

    private SignUtil() {}

    /** The player cannot open a sign right now: on the death screen or in bed. */
    public static boolean cannotOpen(Player player) {
        return player.isDead() || player.isSleeping();
    }

    /**
     * Whether a sign that got no answer should be asked again rather than counted as TIMEOUT: the player died,
     * went to bed, changed world (the client dropped the sign with the old world) or moved out of reach.
     */
    public static boolean lostSign(Player player, Location sign) {
        if (cannotOpen(player)) return true;
        if (sign == null) return false;
        Location at = player.getLocation();
        return at.getWorld() != sign.getWorld() || at.distanceSquared(sign) > 64;
    }

    /** Checks whether two locations point to the exact same block coordinates. */
    public static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }
}
