package com.veneer.hacks;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import net.kyori.adventure.text.Component;

/**
 * Tells the client's real game version from how it translates a few keys on the language-check sign, to compare
 * with the protocol version it connects with (ViaFabricPlus, ViaProxy and the like make the two differ).
 * <p>
 * Only English is built into the game; every other language file is downloaded from the same place for every
 * version and Mojang updates old versions too, so a 1.20 client in Russian knows the names of 1.21.9 items.
 * Two kinds of evidence survive that:
 * <ul>
 *   <li>A key Mojang removed in 1.21.2 from the game and from the shared files: clients up to 1.21.1 still
 *       translate it (in English, from the built-in file) in any language, newer ones return the fallback.</li>
 *   <li>For English clients only: "Walk Forwards" became "Walk Forward" in 1.21.2, and the mace exists from
 *       1.20.5.</li>
 * </ul>
 * Checked against the en_us files of 1.20.1 to 1.21.11 and with live clients through ViaFabricPlus and ViaProxy.
 */
public final class VersionProbe {

    private static final String REMOVED_KEY      = "advMode.allEntities";
    private static final String REMOVED_TEXT     = "Use \"@e\" to target all entities";
    private static final String REMOVED_FALLBACK = "⟦VERSION_NEW⟧";
    private static final String MACE_KEY         = "item.minecraft.mace";
    private static final String MACE_TEXT        = "Mace";
    private static final String MACE_FALLBACK    = "⟦VERSION_NO_MACE⟧";

    private VersionProbe() {}

    /** Sign line 1: known to 1.21.1 and older only. */
    public static Component removedLine() { return Component.translatable(REMOVED_KEY, REMOVED_FALLBACK); }

    /** Sign line 2: the mace, 1.20.5 and newer (read for English clients only). */
    public static Component maceLine() { return Component.translatable(MACE_KEY, MACE_FALLBACK); }

    /**
     * The client's real version as a range: {@code min}/{@code max} are null when unbounded, and both null when
     * the answers tell nothing (the probe lines came back as raw keys or empty). {@code conflict} means the
     * answers contradict each other, which no unmodified client produces.
     */
    public record Range(ClientVersion min, ClientVersion max, boolean conflict) {

        public boolean known() { return min != null || max != null || conflict; }

        /** Whether a client connecting with {@code protocol} could really be this version. */
        public boolean fits(ClientVersion protocol) {
            if (conflict) return false;
            if (protocol == null) return true;
            if (min != null && protocol.isOlderThan(min)) return false;
            return max == null || !protocol.isNewerThan(max);
        }

        public String text() {
            if (conflict) return "inconsistent";
            if (min != null && max != null) return name(min) + "-" + name(max, true);
            if (max != null) return "<=" + name(max, true);
            if (min != null) return ">=" + name(min);
            return "?";
        }
    }

    /**
     * @param forward the answer to line 0 (key.forward); only an English answer lets the mace line count
     * @param removed the answer to {@link #removedLine()}
     * @param mace    the answer to {@link #maceLine()}
     */
    public static Range evaluate(String forward, String removed, String mace) {
        ClientVersion min = null, max = null;
        if (removed.equalsIgnoreCase(REMOVED_TEXT))  max = ClientVersion.V_1_21;
        else if (removed.equals(REMOVED_FALLBACK))   min = ClientVersion.V_1_21_2;

        if (forward.equalsIgnoreCase("Walk Forwards"))     max = lower(max, ClientVersion.V_1_21);
        else if (forward.equalsIgnoreCase("Walk Forward")) min = higher(min, ClientVersion.V_1_21_2);
        boolean english = forward.toLowerCase(java.util.Locale.ROOT).startsWith("walk forward");
        if (english) {
            if (mace.equalsIgnoreCase(MACE_TEXT))   min = higher(min, ClientVersion.V_1_20_5);
            else if (mace.equals(MACE_FALLBACK))    max = lower(max, ClientVersion.V_1_20_3);
        }
        boolean conflict = min != null && max != null && min.isNewerThan(max);
        return new Range(conflict ? null : min, conflict ? null : max, conflict);
    }

    private static ClientVersion lower(ClientVersion a, ClientVersion b)  { return a == null || b.isOlderThan(a) ? b : a; }
    private static ClientVersion higher(ClientVersion a, ClientVersion b) { return a == null || b.isNewerThan(a) ? b : a; }

    /** Release name; as an upper bound a protocol shared by two releases names the later one (1.21 -> 1.21.1). */
    public static String name(ClientVersion v) { return name(v, false); }

    /** The protocol version a client connects with, naming both releases where two share it ("1.20/1.20.1"). */
    public static String protocolName(ClientVersion v) {
        if (v == ClientVersion.V_1_20)   return "1.20/1.20.1";
        if (v == ClientVersion.V_1_20_3) return "1.20.3/1.20.4";
        if (v == ClientVersion.V_1_20_5) return "1.20.5/1.20.6";
        if (v == ClientVersion.V_1_21)   return "1.21/1.21.1";
        if (v == ClientVersion.V_1_21_2) return "1.21.2/1.21.3";
        if (v == ClientVersion.V_1_21_7) return "1.21.7/1.21.8";
        return v.getReleaseName();
    }

    private static String name(ClientVersion v, boolean upper) {
        if (upper && v == ClientVersion.V_1_21)   return "1.21.1";
        if (upper && v == ClientVersion.V_1_20_3) return "1.20.4";
        return v.getReleaseName();
    }
}
