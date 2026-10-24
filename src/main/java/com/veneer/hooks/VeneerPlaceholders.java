package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.rptrack.RPTrackManager;
import com.veneer.core.PlayerProfile;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * PlaceholderAPI expansion {@code %veneer_<key>%}.
 * brand, channels, channels_count, is_spoofing, is_bedrock, client_type, alerts, group,
 * rptrack_packs, rptrack_linked, rptrack_liquidbounce.
 */
public class VeneerPlaceholders extends PlaceholderExpansion {

    private final VeneerPlugin plugin;

    public VeneerPlaceholders(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "veneer"; }
    @SuppressWarnings("deprecation")
    @Override public @NotNull String getAuthor() { return String.join(", ", plugin.getDescription().getAuthors()); }
    @Override public @NotNull String getVersion() { return plugin.version(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String id) {
        if (player == null) return "";
        switch (id) {
            case "brand": {
                String brand = plugin.brandOf(player);
                return brand != null ? brand : "unknown";
            }
            case "channels": {
                PlayerProfile data = plugin.profileOf(player.getUniqueId());
                Set<String> ch = data == null ? Set.of() : data.getChannels();
                return ch.isEmpty() ? "none" : String.join(", ", ch);
            }
            case "channels_count": {
                PlayerProfile data = plugin.profileOf(player.getUniqueId());
                return String.valueOf(data == null ? 0 : data.getChannels().size());
            }
            case "is_spoofing": return String.valueOf(plugin.spoof().running() && plugin.spoof().isPlayerSpoofing(player));
            case "is_bedrock": return String.valueOf(plugin.bedrock().isBedrock(player));
            case "client_type": {
                PlayerProfile data = plugin.profileOf(player.getUniqueId());
                return data == null ? "unknown" : data.clientType().name().toLowerCase();
            }
            case "alerts": return plugin.alerts().isEnabled(player.getUniqueId()) ? "on" : "off";
            case "group": return plugin.hooks().primaryGroup(player);
        }
        if (id.startsWith("rptrack_")) {
            RPTrackManager.Result r = plugin.rptrack().getResult(player.getUniqueId());
            switch (id) {
                case "rptrack_packs": return String.valueOf(r == null ? 0 : r.packs());
                case "rptrack_linked": return String.valueOf(r == null ? 0 : r.linked());
                case "rptrack_liquidbounce": return String.valueOf(r != null && r.liquidBounce());
            }
        }
        return null;
    }
}
