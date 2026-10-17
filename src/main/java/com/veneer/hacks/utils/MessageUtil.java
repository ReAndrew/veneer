package com.veneer.hacks.utils;

import com.veneer.VeneerPlugin;
import net.kyori.adventure.text.Component;

import java.util.Map;

public class MessageUtil {

    public static Component parse(VeneerPlugin plugin, String key, Map<String, String> placeholders) {
        return plugin.getMessageManager().get(key, placeholders);
    }

    public static Component parseRaw(VeneerPlugin plugin, String raw, Map<String, String> placeholders) {
        String prefix = plugin.getHackConfigManager().getPrefix();
        raw = raw.replace("{prefix}", prefix);
        for (Map.Entry<String, String> e : placeholders.entrySet())
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(raw);
    }

    public static void broadcastAlerts(VeneerPlugin plugin, Component msg) {
        plugin.getMessageManager().broadcastAlerts(msg);
    }

    public static void broadcast(VeneerPlugin plugin, String key,
                                 String ignored, Map<String, String> placeholders) {
        plugin.getMessageManager().broadcastAlerts(plugin.getMessageManager().get(key, placeholders));
    }
}