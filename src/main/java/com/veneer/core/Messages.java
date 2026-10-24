package com.veneer.core;

import com.veneer.VeneerPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Translatable chat messages from {@code messages/<language>.yml}, formatted with MiniMessage. */
public class Messages {

    private static final List<String> BUNDLED = List.of(
            "en", "it", "br", "de", "es", "fr", "lolcat", "ru", "uwu");

    private final VeneerPlugin plugin;
    private FileConfiguration messages;

    public Messages(VeneerPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        for (String lang : BUNDLED) ensureFile("messages/" + lang + ".yml");
        String lang = plugin.config().language();
        File file = new File(plugin.getDataFolder(), "messages/" + lang + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("messages/" + lang + ".yml not found, falling back to en.yml");
            file = new File(plugin.getDataFolder(), "messages/en.yml");
        }
        messages = YamlConfiguration.loadConfiguration(file);
        messages.setDefaults(bundled(file.getName()));
    }

    /** The copy of a language file shipped in the jar (en when there is none), so keys added later still resolve. */
    private YamlConfiguration bundled(String fileName) {
        for (String name : new String[]{fileName, "en.yml"}) {
            try (java.io.InputStream in = plugin.getResource("messages/" + name)) {
                if (in != null)
                    return YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException ignored) {
                // fall through to the next candidate
            }
        }
        return new YamlConfiguration();
    }

    private void ensureFile(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            plugin.saveResource(name, false);
        }
    }

    public String getRaw(String key) {
        return messages.getString(key, "<red>Missing message: " + key);
    }

    /** The message for {@code key} with {@code {prefix}} and {@code {name}} placeholders filled in. */
    public Component get(String key, Map<String, String> placeholders) {
        return format(getRaw(key), placeholders);
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    /** Formats an arbitrary MiniMessage string the same way as a message from the file. */
    public Component format(String raw, Map<String, String> placeholders) {
        raw = raw.replace("{prefix}", plugin.config().prefix());
        for (Map.Entry<String, String> e : placeholders.entrySet())
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) raw = applyPapi(raw);
        return Text.mini(raw);
    }

    /** Prefixed one-liner for commands that have no entry in the language files. */
    public Component prefixed(String miniMessage) {
        return format("{prefix}" + miniMessage, Map.of());
    }

    private static String applyPapi(String text) {
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return (String) papi.getMethod("setPlaceholders", org.bukkit.entity.Player.class, String.class)
                    .invoke(null, null, text);
        } catch (Exception e) {
            return text;
        }
    }
}
