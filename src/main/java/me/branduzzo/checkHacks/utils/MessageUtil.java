package me.branduzzo.checkHacks.utils;

import com.invalider.InvaliderPlugin;
import net.kyori.adventure.text.Component;

import java.util.Map;

public class MessageUtil {

    public static Component parse(InvaliderPlugin plugin, String key, Map<String, String> placeholders) {
        return plugin.getMessageManager().get(key, placeholders);
    }

    public static Component parseRaw(InvaliderPlugin plugin, String raw, Map<String, String> placeholders) {
        String prefix = plugin.getHackConfigManager().getPrefix();
        raw = raw.replace("{prefix}", prefix);
        for (Map.Entry<String, String> e : placeholders.entrySet())
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(raw);
    }

    public static void broadcastAlerts(InvaliderPlugin plugin, Component msg) {
        plugin.getMessageManager().broadcastAlerts(msg);
    }

    public static void broadcast(InvaliderPlugin plugin, String key,
                                 String ignored, Map<String, String> placeholders) {
        plugin.getMessageManager().broadcastAlerts(plugin.getMessageManager().get(key, placeholders));
    }
}