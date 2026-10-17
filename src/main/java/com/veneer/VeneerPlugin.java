package com.veneer;

import com.veneer.spoof.commands.SpoofCommand;
import com.veneer.spoof.data.PlayerData;
import com.veneer.spoof.listeners.PermissionChangeListener;
import com.veneer.spoof.listeners.PlayerEventListener;
import com.veneer.spoof.managers.AlertManager;
import com.veneer.spoof.managers.DetectionManager;
import com.veneer.spoof.utils.DiscordWebhookHandler;
import com.veneer.spoof.utils.VersionChecker;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import com.veneer.hacks.commands.*;
import com.veneer.hacks.listeners.*;
import com.veneer.hacks.managers.*;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Combined plugin merging AntiSpoof (client spoof detection, by GigaZelensky)
 * and CheckHacks (sign-translation hack detection, by Branduzzo) into a
 * single JavaPlugin, since Bukkit only loads one main class per plugin.yml.
 * The original package trees (com.veneer.spoof.*, com.veneer.hacks.*)
 * are kept as-is; this class replaces their old standalone main classes and
 * wires both modules' managers/listeners/commands against itself.
 */
public class VeneerPlugin extends JavaPlugin {

    // ===================== AntiSpoof state =====================
    private com.veneer.spoof.managers.ConfigManager configManager;
    private DiscordWebhookHandler discordWebhookHandler;
    private AlertManager alertManager;
    private DetectionManager detectionManager;
    private PlayerEventListener playerEventListener;

    private final ConcurrentHashMap<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();
    private final Set<UUID> brandAlertedPlayers = ConcurrentHashMap.newKeySet();
    private FloodgateApi floodgateApi = null;

    // ===================== CheckHacks state =====================
    private com.veneer.hacks.managers.ConfigManager hackConfigManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private com.veneer.hacks.rptrack.RPTrackManager rpTrackManager;
    private WebServerManager webServerManager;
    private CheckManager checkManager;
    private LangCheckManager langCheckManager;
    private ClientDataManager clientDataManager;
    private final Set<UUID> alertsDisabled = new HashSet<>();
    private com.veneer.hooks.LuckPermsHook luckPermsHook;

    @Override
    public void onEnable() {
        // ---------- AntiSpoof ----------
        this.configManager = new com.veneer.spoof.managers.ConfigManager(this);
        this.alertManager = new AlertManager(this);
        this.detectionManager = new DetectionManager(this);
        this.discordWebhookHandler = new DiscordWebhookHandler(this);

        new VersionChecker(this);

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
            .reEncodeByDefault(false)
            .checkForUpdates(false);
        PacketEvents.getAPI().load();

        if (getServer().getPluginManager().isPluginEnabled("floodgate")) {
            try {
                floodgateApi = FloodgateApi.getInstance();
                getLogger().info("Successfully hooked into Floodgate API!");
            } catch (Exception e) {
                getLogger().warning("Failed to hook into Floodgate API: " + e.getMessage());
            }
        }

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new com.veneer.hooks.VeneerPlaceholders(this).register();
            getLogger().info("Successfully registered PlaceholderAPI expansion!");
        }

        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                luckPermsHook = new com.veneer.hooks.LuckPermsHook(this);
                getLogger().info("Hooked into LuckPerms.");
            } catch (Throwable t) {
                getLogger().warning("Failed to hook into LuckPerms: " + t.getMessage());
            }
        }

        registerClientBrandChannel();

        this.playerEventListener = new PlayerEventListener(this);
        this.playerEventListener.register();

        PermissionChangeListener permissionListener = new PermissionChangeListener(this);
        getServer().getPluginManager().registerEvents(permissionListener, this);

        SpoofCommand antiSpoofCommand = new SpoofCommand(this);
        getCommand("spoof").setExecutor(antiSpoofCommand);
        getCommand("spoof").setTabCompleter(antiSpoofCommand);

        PacketEvents.getAPI().init();

        if (configManager.isDiscordWebhookEnabled()) {
            String webhookUrl = configManager.getDiscordWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isEmpty()) {
                getLogger().info("Discord webhook integration is enabled.");
            } else {
                getLogger().warning("Discord webhook is enabled but no URL is configured!");
            }
        } else if (configManager.isDebugMode()) {
            getLogger().info("Discord webhook integration is disabled.");
        }

        com.veneer.hacks.utils.FoliaScheduler.runGlobalLater(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                alertManager.registerPlayer(player);
            }
            if (configManager.isDebugMode()) {
                getLogger().info("[Debug] Initialized alert recipients list");
            }
        }, 40L);

        // ---------- CheckHacks ----------
        try {
            new Metrics(this, 31804);
        } catch (Throwable ignored) {
        }

        hackConfigManager = new com.veneer.hacks.managers.ConfigManager(this);
        messageManager    = new MessageManager(this);
        databaseManager   = new DatabaseManager(this);
        clientDataManager = new ClientDataManager();
        checkManager      = new CheckManager(this);
        langCheckManager  = new LangCheckManager(this);

        if (hackConfigManager.isWebEditorEnabled()) {
            webServerManager = new WebServerManager(this);
        }

        HackCheckCommand checkCmd = new HackCheckCommand(this);
        getCommand("hackcheck").setExecutor(checkCmd);
        getCommand("hackcheck").setTabCompleter(checkCmd);
        getCommand("hackreload").setExecutor(new HackReloadCommand(this));
        getCommand("hackalerts").setExecutor(new HackAlertsCommand(this));
        HackLangCommand langCmd = new HackLangCommand(this);
        getCommand("hacklang").setExecutor(langCmd);
        getCommand("hacklang").setTabCompleter(langCmd);
        getCommand("hackeditor").setExecutor(new HackEditorCommand(this));

        rpTrackManager = new com.veneer.hacks.rptrack.RPTrackManager(this);
        getCommand("rptrack").setExecutor(new com.veneer.hacks.rptrack.RPTrackCommand(this));

        getServer().getPluginManager().registerEvents(new SignListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        AntiCheatListener acListener = new AntiCheatListener(this);
        getServer().getPluginManager().registerEvents(acListener, this);
        acListener.registerHooks();


        getLogger().info("Veneer enabled: AntiSpoof + CheckHacks v" + getDescription().getVersion());
    }

    private void registerClientBrandChannel() {
        String channelName = getServer().getBukkitVersion().contains("1.13") ||
                            Integer.parseInt(getServer().getBukkitVersion().split("-")[0].split("\\.")[1]) >= 13 ?
                            "minecraft:brand" : "MC|Brand";

        getServer().getMessenger().registerIncomingPluginChannel(this, channelName,
            (channel, player, message) -> {
                String brand = new String(message).substring(1);
                UUID playerUuid = player.getUniqueId();

                String previousBrand = playerBrands.get(playerUuid);
                boolean isNewBrand = previousBrand == null || !previousBrand.equals(brand);

                if (isNewBrand) {
                    playerBrands.put(playerUuid, brand);

                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Received brand for " + player.getName() + ": " + brand);
                    }

                    detectionManager.checkPlayerAsync(player, false);
                }
            });
    }

    // ===================== AntiSpoof accessors =====================

    public com.veneer.spoof.managers.ConfigManager getConfigManager() {
        return configManager;
    }

    public DiscordWebhookHandler getDiscordWebhookHandler() {
        return discordWebhookHandler;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public DetectionManager getDetectionManager() {
        return detectionManager;
    }

    public ConcurrentHashMap<UUID, PlayerData> getPlayerDataMap() {
        return playerDataMap;
    }

    public String getClientBrand(Player player) {
        if (player != null) {
            return playerBrands.get(player.getUniqueId());
        }
        return null;
    }

    public Map<UUID, String> getPlayerBrands() {
        return playerBrands;
    }

    public boolean hasPlayerBeenBrandAlerted(Player player) {
        return brandAlertedPlayers.contains(player.getUniqueId());
    }

    public void markPlayerBrandAlerted(Player player) {
        brandAlertedPlayers.add(player.getUniqueId());
    }

    public boolean sendBrandAlert(Player player, String brand, String brandKey) {
        if (hasPlayerBeenBrandAlerted(player)) {
            if (configManager.isDebugMode()) {
                getLogger().info("[Debug] Suppressing duplicate brand alert for " + player.getName());
            }
            return false;
        }

        markPlayerBrandAlerted(player);

        if (brandKey == null) {
            alertManager.sendSimpleBrandAlert(player, brand);
            return true;
        }

        com.veneer.spoof.managers.ConfigManager.ClientBrandConfig brandConfig =
            configManager.getClientBrandConfig(brandKey);

        if (!brandConfig.shouldAlert()) {
            return false;
        }

        String alertMessage = brandConfig.getAlertMessage()
            .replace("%player%", player.getName())
            .replace("%brand%", brand);

        String consoleMessage = brandConfig.getConsoleAlertMessage()
            .replace("%player%", player.getName())
            .replace("%brand%", brand);

        getLogger().info(consoleMessage);
        alertManager.sendAlertToRecipients(alertMessage);

        if (configManager.isDiscordWebhookEnabled() && brandConfig.shouldDiscordAlert()) {
            List<String> brandInfo = new ArrayList<>();
            brandInfo.add("Client brand: " + brand);

            discordWebhookHandler.sendAlert(
                player,
                "Using client: " + brandKey,
                brand,
                null,
                brandInfo
            );
        }

        return true;
    }

    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;

        if (floodgateApi != null) {
            try {
                if (floodgateApi.isFloodgatePlayer(player.getUniqueId())) {
                    if (configManager.isDebugMode()) {
                        getLogger().info("[Debug] Player " + player.getName() +
                                       " identified as Bedrock player via Floodgate API");
                    }
                    return true;
                }
            } catch (Exception e) {
                if (configManager.isDebugMode()) {
                    getLogger().warning("[Debug] Error checking Floodgate API for " +
                                       player.getName() + ": " + e.getMessage());
                }
            }
        }

        if (configManager.isBedrockPrefixCheckEnabled()) {
            String prefix = configManager.getBedrockPrefix();
            if (player.getName().startsWith(prefix)) {
                if (configManager.isDebugMode()) {
                    getLogger().info("[Debug] Player " + player.getName() +
                                   " identified as Bedrock player via prefix check");
                }
                return true;
            }
        }

        return false;
    }

    public boolean isPlayerSpoofing(Player player) {
        if (player == null) return false;

        String brand = getClientBrand(player);

        if (brand == null) {
            if (configManager.isNoBrandCheckEnabled()) {
                return true;
            }
            if (configManager.shouldBlockNonVanillaWithChannels()) {
                return true;
            }
            return false;
        }

        boolean isBedrockPlayer = isBedrockPlayer(player);

        if (isBedrockPlayer && configManager.getBedrockHandlingMode().equals("IGNORE")) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataMap.get(uuid);
        if (data == null) return false;

        Set<String> filteredChannels = detectionManager.getFilteredChannels(data.getChannels());
        boolean hasChannels = !filteredChannels.isEmpty();
        boolean claimsVanilla = brand.equalsIgnoreCase("vanilla");

        if (configManager.isPunishSpoofingGeyser() && isSpoofingGeyser(player)) {
            return true;
        }

        if (configManager.isVanillaCheckEnabled() && claimsVanilla && hasChannels) {
            return true;
        }

        if (configManager.isBlockedBrandsEnabled()) {
            boolean brandBlocked = configManager.isBrandBlocked(brand);

            if (brandBlocked && configManager.shouldCountNonWhitelistedBrandsAsFlag()) {
                return true;
            }
        }

        if (configManager.shouldBlockNonVanillaWithChannels() && (!claimsVanilla || hasChannels)) {
            return true;
        }

        if (configManager.isBlockedChannelsEnabled() && hasChannels) {
            if (configManager.isChannelWhitelistEnabled()) {
                if (!detectionManager.checkChannelWhitelist(filteredChannels)) {
                    return true;
                }
            } else {
                String blockedChannel = detectionManager.findBlockedChannel(filteredChannels);
                if (blockedChannel != null) {
                    return true;
                }
            }
        }

        String matchedBrand = configManager.getMatchingClientBrand(brand);
        if (matchedBrand != null && hasChannels) {
            com.veneer.spoof.managers.ConfigManager.ClientBrandConfig brandConfig =
                configManager.getClientBrandConfig(matchedBrand);

            if (!brandConfig.getRequiredChannels().isEmpty()) {
                boolean missingRequiredChannels = false;

                for (int i = 0; i < brandConfig.getRequiredChannels().size(); i++) {
                    Pattern pattern = brandConfig.getRequiredChannels().get(i);
                    boolean patternMatched = false;

                    for (String channel : filteredChannels) {
                        try {
                            if (pattern.matcher(channel).matches()) {
                                patternMatched = true;
                                break;
                            }
                        } catch (Exception e) {
                            String simplePatternStr = pattern.toString()
                                .replace("(?i)", "")
                                .replace(".*", "")
                                .replace("^", "")
                                .replace("$", "");

                            if (channel.toLowerCase().contains(simplePatternStr.toLowerCase())) {
                                patternMatched = true;
                                break;
                            }
                        }
                    }

                    if (!patternMatched) {
                        missingRequiredChannels = true;
                        break;
                    }
                }

                if (missingRequiredChannels) {
                    return true;
                }
            }
        }

        if (isBedrockPlayer && configManager.isBedrockExemptMode()) {
            return false;
        }

        return false;
    }

    public boolean isSpoofingGeyser(Player player) {
        if (player == null) return false;

        if (!configManager.isPunishSpoofingGeyser()) {
            return false;
        }

        String brand = getClientBrand(player);
        if (brand == null) return false;

        boolean claimsGeyser = brand.toLowerCase().contains("geyser");

        return claimsGeyser && !isBedrockPlayer(player);
    }

    public void handlePlayerQuit(UUID uuid) {
        getDetectionManager().handlePlayerQuit(uuid);
        getAlertManager().handlePlayerQuit(uuid);
        getDiscordWebhookHandler().handlePlayerQuit(uuid);
        playerBrands.remove(uuid);
        playerDataMap.remove(uuid);
        brandAlertedPlayers.remove(uuid);

        if (configManager.isDebugMode()) {
            getLogger().info("Cleaned up all data for player with UUID: " + uuid);
        }
    }

    // ===================== CheckHacks accessors =====================

    /** Primary LuckPerms group of the player, or "" when LuckPerms is absent. */
    public String getPrimaryGroup(Player player) {
        return luckPermsHook == null ? "" : luckPermsHook.getPrimaryGroup(player);
    }

    public com.veneer.hacks.managers.ConfigManager getHackConfigManager() { return hackConfigManager; }
    public MessageManager    getMessageManager()    { return messageManager; }
    public com.veneer.hacks.rptrack.RPTrackManager getRPTrackManager() { return rpTrackManager; }
    public DatabaseManager   getDatabaseManager()   { return databaseManager; }
    public CheckManager      getCheckManager()      { return checkManager; }
    public LangCheckManager  getLangCheckManager()  { return langCheckManager; }
    public ClientDataManager getClientDataManager() { return clientDataManager; }

    /** Queues a CheckHacks scan when AntiSpoof flags a player. Safe to call from any thread. */
    public void triggerCheckHacksFromSpoof(Player player, String reason) {
        if (configManager == null || !configManager.isCheckHacksOnFlagEnabled()) return;
        if (checkManager == null || hackConfigManager == null || player == null) return;
        UUID uuid = player.getUniqueId();
        if (checkManager.isChecking(uuid) || !checkManager.canAutoCheck(uuid)) return;

        var hacks = hackConfigManager.getFlagCheckHacks();
        if (hacks.isEmpty()) hacks = hackConfigManager.getDefaultCheckHacks();
        if (hacks.isEmpty()) return;

        final var toCheck = hacks;
        getLogger().info("[Veneer] AntiSpoof flagged " + player.getName() + " - queuing CheckHacks scan.");
        com.veneer.hacks.utils.FoliaScheduler.runAtEntity(this, player, () ->
                checkManager.startCheck(player, null, toCheck, true, "AntiSpoof flag: " + reason));
    }

    public boolean hasAlertsEnabled(UUID uuid) {
        boolean def = hackConfigManager.isAlertsDefaultEnabled();
        return def ? !alertsDisabled.contains(uuid) : alertsDisabled.contains(uuid);
    }

    public void toggleAlerts(UUID uuid) {
        if (!alertsDisabled.remove(uuid)) alertsDisabled.add(uuid);
    }

    @Override
    public void onDisable() {
        // ---------- AntiSpoof ----------
        if (PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().terminate();
        }
        playerBrands.clear();
        playerDataMap.clear();
        brandAlertedPlayers.clear();

        // ---------- CheckHacks ----------
        if (luckPermsHook    != null) luckPermsHook.close();
        if (checkManager     != null) checkManager.cleanup();
        if (langCheckManager != null) langCheckManager.cleanup();
        if (webServerManager != null) webServerManager.stop();
        if (rpTrackManager != null) rpTrackManager.stop();
        if (databaseManager  != null) databaseManager.close();

        getLogger().info("Veneer disabled.");
    }
}
