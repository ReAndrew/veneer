package com.veneer.hacks;

import com.veneer.VeneerPlugin;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.VeneerModule;
import com.veneer.hacks.commands.HackCheckCommand;
import com.veneer.hacks.commands.HackEditorCommand;
import com.veneer.hacks.commands.HackLangCommand;
import com.veneer.hacks.listeners.AntiCheatListener;
import com.veneer.hacks.listeners.JoinListener;
import com.veneer.hacks.listeners.SignListener;
import com.veneer.hacks.managers.CheckManager;
import com.veneer.hacks.managers.ConfigManager;
import com.veneer.hacks.managers.LangCheckManager;
import com.veneer.hacks.managers.WebServerManager;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Mod detection (from CheckHacks): finds installed client mods through the sign translation vulnerability
 * (MC-265322), keeps the scan history and serves the web panel.
 */
public class HacksModule extends VeneerModule {

    private ConfigManager config;
    private CheckManager checks;
    private LangCheckManager langChecks;
    private WebServerManager web;

    public HacksModule(VeneerPlugin plugin) {
        super(plugin);
    }

    @Override public String id() { return "hacks"; }

    @Override public List<String> commandNames() { return List.of("hackcheck", "hacklang", "hackeditor"); }

    @Override
    protected void enable() {
        config = new ConfigManager(plugin);
        checks = new CheckManager(plugin);
        langChecks = new LangCheckManager(plugin);
        if (config.isWebEditorEnabled()) web = new WebServerManager(plugin);

        command("hackcheck", new HackCheckCommand(plugin));
        command("hacklang", new HackLangCommand(plugin));
        command("hackeditor", new HackEditorCommand(plugin));

        listen(new SignListener(plugin));
        listen(new JoinListener(plugin));
        AntiCheatListener anticheat = listen(new AntiCheatListener(plugin));
        anticheat.registerHooks();
    }

    @Override
    protected void disable() {
        checks.cleanup();
        langChecks.cleanup();
        if (web != null) web.stop();
        web = null;
    }

    @Override
    protected void reload() {
        config.reload();
        if (config.isWebEditorEnabled() != (web != null))
            plugin.getLogger().info("hacks.web-editor.enabled changed: restart the server to apply it.");
    }

    public ConfigManager config() { return config; }
    public CheckManager checks() { return checks; }
    public LangCheckManager langChecks() { return langChecks; }

    /**
     * Queues a mod scan for a player the spoof module just flagged. Safe to call from any thread.
     * Uses the {@code detect-flag.hacks} list (or the default list) and respects the flag cooldown.
     */
    public void onSpoofFlag(Player player, String reason) {
        if (!running() || player == null || !config.isSpoofFlagScanEnabled()) return;
        UUID uuid = player.getUniqueId();
        if (checks.isChecking(uuid) || !checks.canAutoCheck(uuid)) return;

        List<HackDefinition> hacks = config.getFlagCheckHacks();
        if (hacks.isEmpty()) hacks = config.getDefaultCheckHacks();
        if (hacks.isEmpty()) return;

        List<HackDefinition> toCheck = hacks;
        plugin.getLogger().info("Spoof detection flagged " + player.getName() + ", queuing a mod scan.");
        FoliaScheduler.runAtEntity(plugin, player, () ->
                checks.startCheck(player, null, toCheck, true, "Spoof flag: " + reason));
    }
}
