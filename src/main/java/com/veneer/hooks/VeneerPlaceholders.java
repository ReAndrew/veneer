package com.veneer.hooks;

import com.veneer.VeneerPlugin;
import com.veneer.hacks.rptrack.RPTrackManager;
import com.veneer.spoof.data.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * PlaceholderAPI expansion {@code %veneer_<key>%}.
 * brand, channels, channels_count, is_spoofing, is_bedrock, client_type, alerts, group,
 * rptrack_packs, rptrack_linked, rptrack_liquidbounce.
 * The legacy {@code %antispoof_*%} expansion stays registered for existing configs.
 */
public class VeneerPlaceholders extends PlaceholderExpansion {

    private final VeneerPlugin plugin;

    public VeneerPlaceholders(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "veneer"; }
    @Override public @NotNull String getAuthor() { return String.join(", ", plugin.getDescription().getAuthors()); }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String id) {
        if (player == null) return "";
        switch (id) {
            case "brand": {
                String brand = plugin.getClientBrand(player);
                return brand != null ? brand : "unknown";
            }
            case "channels": {
                PlayerData data = plugin.getPlayerDataMap().get(player.getUniqueId());
                Set<String> ch = data == null ? Set.of() : data.getChannels();
                return ch.isEmpty() ? "none" : String.join(", ", ch);
            }
            case "channels_count": {
                PlayerData data = plugin.getPlayerDataMap().get(player.getUniqueId());
                return String.valueOf(data == null ? 0 : data.getChannels().size());
            }
            case "is_spoofing": return String.valueOf(plugin.isPlayerSpoofing(player));
            case "is_bedrock": return String.valueOf(plugin.isBedrockPlayer(player));
            case "client_type": {
                var type = plugin.getClientDataManager().getClientType(player.getUniqueId());
                return type == null ? "unknown" : type.name().toLowerCase();
            }
            case "alerts": return plugin.hasAlertsEnabled(player.getUniqueId()) ? "on" : "off";
            case "group": return plugin.getPrimaryGroup(player);
        }
        if (id.startsWith("rptrack_")) {
            RPTrackManager rp = plugin.getRPTrackManager();
            RPTrackManager.Result r = rp == null ? null : rp.getResult(player.getUniqueId());
            switch (id) {
                case "rptrack_packs": return String.valueOf(r == null ? 0 : r.packs());
                case "rptrack_linked": return String.valueOf(r == null ? 0 : r.linked());
                case "rptrack_liquidbounce": return String.valueOf(r != null && r.liquidBounce());
            }
        }
        return null;
    }
}
