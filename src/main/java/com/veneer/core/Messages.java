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

    /** The jar's copy of a language file laid over the jar's en.yml, so a key an edited file lacks still resolves. */
    private YamlConfiguration bundled(String fileName) {
        YamlConfiguration merged = new YamlConfiguration();
        for (String name : new String[]{"en.yml", fileName}) {
            try (java.io.InputStream in = plugin.getResource("messages/" + name)) {
                if (in == null) continue;
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                for (String key : yaml.getKeys(true))
                    if (!yaml.isConfigurationSection(key)) merged.set(key, yaml.get(key));
            } catch (java.io.IOException ignored) {
                // keep what was read so far
            }
        }
        return merged;
    }

    private void ensureFile(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            plugin.saveResource(name, false);
        }
    }

    public String getRaw(String key) {
        String text = messages.getString(key);   // without a fallback argument, so the bundled defaults apply
        return text != null ? text : "<red>Missing message: " + key;
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

    /** A placeholder value that is already MiniMessage; any other value is escaped so players cannot inject tags. */
    public record Raw(String text) {}

    /** The text of {@code key} as a placeholder value that is not escaped. */
    public Raw raw(String key) {
        return new Raw(getRaw(key));
    }

    /** Escapes text from players or the network (brands, channels, names) for use inside MiniMessage. */
    public static String escape(String text) {
        return text.replace("<", "\\<");
    }

    private Map<String, String> values(Object... pairs) {
        Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            map.put(String.valueOf(pairs[i]), v instanceof Raw r ? r.text() : escape(String.valueOf(v)));
        }
        return map;
    }

    /** The message {@code key} with placeholders given as name, value, name, value... */
    public Component with(String key, Object... pairs) {
        return get(key, values(pairs));
    }

    /** The message {@code key} as plain text with {@code {name}} placeholders filled in (no prefix, no formatting pass). */
    public String plain(String key, Object... pairs) {
        String text = getRaw(key);
        for (Map.Entry<String, String> e : values(pairs).entrySet()) text = text.replace("{" + e.getKey() + "}", e.getValue());
        return text;
    }

    public void send(org.bukkit.command.CommandSender to, String key, Object... pairs) {
        to.sendMessage(with(key, pairs));
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
