package com.invalider;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Loads the single config.yml and hands each module its own top-level section. */
public final class InvaliderConfig {

    private InvaliderConfig() {}

    public static ConfigurationSection section(JavaPlugin plugin, String path) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) plugin.saveResource("config.yml", false);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = cfg.getConfigurationSection(path);
        if (s == null) {
            plugin.getLogger().warning("config.yml: missing section '" + path + "', using defaults.");
            s = new YamlConfiguration();
        }
        return s;
    }
}
