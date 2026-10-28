package com.veneer.spoof.utils;

import com.veneer.VeneerPlugin;
import com.veneer.core.Text;
import com.veneer.core.PlayerProfile;
import com.veneer.core.Webhook;
import com.veneer.spoof.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordWebhookHandler {
    private final VeneerPlugin plugin;
    private final ConfigManager config;
    
    // Map to track players who have already been alerted for spoofing in the current session
    private final Map<UUID, Boolean> alertedPlayers = new ConcurrentHashMap<>();
    
    // Map to track all pending violations for a player
    private final Map<UUID, List<String>> pendingViolations = new ConcurrentHashMap<>();
    
    // Map to track player brands for alerts
    private final Map<UUID, String> playerBrands = new ConcurrentHashMap<>();
    
    // Map to track blocked channels for alerts
    private final Map<UUID, String> blockedChannels = new ConcurrentHashMap<>();
    
    public DiscordWebhookHandler(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.spoof().config();
    }
    
    /**
     * Starts collecting a player's violations for the alert
     * @param playerUuid The UUID of the player
     */
    public void registerPlayerJoin(UUID playerUuid) {
        pendingViolations.put(playerUuid, new ArrayList<>());
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Discord] Registered join time for player with UUID: " + playerUuid);
        }
    }
    
    /**
     * Sends an alert to Discord webhook
     * @param player The player who triggered the alert
     * @param reason The reason for the alert
     * @param brand The client brand
     * @param channel The channel that triggered the alert (can be null)
     * @param violations List of all violations (for multiple flags)
     * @param violationType What was detected ({@code BLOCKED_CHANNEL}, {@code NO_BRAND}, ...); picks the console
     *                      message, so the reason text is never parsed
     */
    public void sendAlert(Player player, String violationType, String reason, String brand, String channel,
                          List<String> violations) {
        if (!config.isDiscordWebhookEnabled()) {
            return;
        }
        
        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        PlayerProfile data = plugin.profiles().get(playerUuid);
        
        // No data, no channels to check
        if (data == null) {
            return;
        }
        
        // Store brand for this player
        if (brand != null) {
            playerBrands.put(playerUuid, brand);
        }
        
        // Store any blocked channel for future reference
        if ("BLOCKED_CHANNEL".equals(violationType) && channel != null) {
            blockedChannels.put(playerUuid, channel);
        }
        
        // Add this violation to the pending list
        List<String> pendingList = pendingViolations.computeIfAbsent(playerUuid, k -> new ArrayList<>());
        if (!pendingList.contains(reason)) {
            pendingList.add(reason);
        }
        
        // Skip if player has already been alerted for spoofing in this session
        if (alertedPlayers.getOrDefault(playerUuid, false)) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Player " + player.getName() + " already alerted for spoofing in this session, collecting additional violations");
            }
            
            return;
        }
        
        // Mark player as alerted for this session
        alertedPlayers.put(playerUuid, true);
        
        // Get the configured delay before sending discord alerts (in seconds)
        int delaySeconds = config.getCheckDelay();
        
        // If there's a delay configured, schedule the webhook after the delay
        if (delaySeconds > 0) {
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Scheduling alert for player: " + player.getName() + 
                                       " after " + delaySeconds + " seconds");
            }
            
            // Schedule the webhook after the configured delay
            com.veneer.core.FoliaScheduler.runAsyncLater(plugin, () -> {
                // The player may have left during the delay
                PlayerProfile updatedData = plugin.profiles().get(playerUuid);
                if (updatedData != null && player.isOnline()) {
                    // Get all collected violations
                    List<String> allViolations = pendingViolations.getOrDefault(playerUuid, new ArrayList<>());
                    String playerBrand = playerBrands.getOrDefault(playerUuid, brand);
                    String blockedChannel = blockedChannels.get(playerUuid);
                    
                    if (config.isDebugMode()) {
                        plugin.getLogger().info("[Discord] Sending delayed spoofing alert for player: " + 
                                               player.getName() + " with " + allViolations.size() + " violations");
                    }
                    
                    // Send the full webhook with all violations
                    sendFullWebhook(player, violationType, reason, playerBrand, blockedChannel, allViolations);
                }
            }, delaySeconds * 20L); // Convert seconds to ticks (20 ticks = 1 second)
        } else {
            // No delay, send immediate webhook
            if (config.isDebugMode()) {
                plugin.getLogger().info("[Discord] Sending immediate spoofing alert for player: " + 
                                       player.getName());
            }
            
            // Get all collected violations
            List<String> allViolations = pendingViolations.getOrDefault(playerUuid, new ArrayList<>());
            
            // Send the full webhook with all violations
            sendFullWebhook(player, violationType, reason, brand, channel, allViolations.isEmpty() ? violations : allViolations);
        }
    }
    
    /**
     * Handle player logout - reset their alert status to enable new alerts when they log back in
     * @param uuid The player's UUID
     */
    public void handlePlayerQuit(UUID uuid) {
        // Remove from all trackers to ensure they get alerts on next login
        alertedPlayers.remove(uuid);
        pendingViolations.remove(uuid);
        playerBrands.remove(uuid);
        blockedChannels.remove(uuid);
        
        if (config.isDebugMode()) {
            plugin.getLogger().info("[Discord] Reset alert status for player with UUID: " + uuid);
        }
    }
    
    /**
     * Sends a full webhook with all player information
     */
    private void sendFullWebhook(Player player, String violationType, String reason, String brand, String channel,
                                 List<String> violations) {
        // Get the appropriate console alert message
        String consoleAlert = determineConsoleAlert(player, violationType, reason, brand, channel, violations);
        
        // Send the full webhook
        sendWebhookDirectly(player, reason, brand, channel, violations, consoleAlert);
    }
    
    /**
     * Determines the appropriate console alert message based on the violation type
     */
    private String determineConsoleAlert(Player player, String violationType, String reason, String brand,
                                         String channel, List<String> violations) {
        String shownBrand = Text.inert(brand != null ? brand : "unknown");
        // For multiple violations, use the multiple flags console message
        if (violations != null && violations.size() > 1) {
            return config.getConsoleMultipleFlagsMessage()
                    .replace("%player%", player.getName())
                    .replace("%brand%", shownBrand)
                    .replace("%reasons%", Text.inert(String.join(", ", violations)));
        }

        String template = switch (violationType) {
            case "VANILLA_WITH_CHANNELS" -> config.getVanillaCheckConsoleAlertMessage();
            case "NON_VANILLA_WITH_CHANNELS" -> config.getNonVanillaCheckConsoleAlertMessage();
            case "BLOCKED_CHANNEL", "CHANNEL_WHITELIST" -> config.getBlockedChannelsConsoleAlertMessage();
            case "GEYSER_SPOOF" -> config.getGeyserSpoofConsoleAlertMessage();
            case "NO_BRAND" -> config.getNoBrandConsoleAlertMessage();
            case "CLIENT_BRAND", "JOIN_BRAND", "UNKNOWN_BRAND" -> brandConsoleMessage(brand);
            default -> config.getConsoleAlertMessage();
        };
        String alert = template
                .replace("%player%", player.getName())
                .replace("%brand%", shownBrand)
                .replace("%reason%", Text.inert(reason));
        return channel != null ? alert.replace("%channel%", Text.inert(channel)) : alert;
    }

    /** The console message of the client-brands entry matching {@code brand}, else the blocked-brands one. */
    private String brandConsoleMessage(String brand) {
        String brandKey = config.isClientBrandsEnabled() ? config.getMatchingClientBrand(brand) : null;
        return brandKey != null ? config.getClientBrandConfig(brandKey).getConsoleAlertMessage()
                : config.getBlockedBrandsConsoleAlertMessage();
    }

    /**
     * Directly sends a webhook
     */
    private void sendWebhookDirectly(Player player, String reason, String brand, String channel,
                                    List<String> violations, String consoleAlert) {
        String webhookUrl = config.getDiscordWebhookUrl();
        if (webhookUrl.isEmpty()) {
            plugin.getLogger().warning("[Discord] No valid webhook URL (spoof.discord.webhook or general.discord.webhook).");
            return;
        }

        plugin.debug("[Discord] Sending webhook for player: " + player.getName());
        String json = createFullWebhookJson(player, reason, brand, channel, violations, consoleAlert);
        plugin.webhook().post(webhookUrl, json);
    }

    /**
     * Creates the JSON payload for the full Discord webhook
     */
    private String createFullWebhookJson(Player player, String reason, String brand, String channel, 
                                        List<String> violations, String consoleAlert) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"embeds\":[{");
        
        // Title
        String title = config.getDiscordEmbedTitle()
                .replace("%player%", player.getName())
                .replace("%reason%", Text.inert(reason));
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");
        
        // Color (convert hex to decimal)
        String colorHex = config.getDiscordEmbedColor().replace("#", "");
        try {
            Color color = Color.decode("#" + colorHex);
            int decimal = color.getRGB() & 0xFFFFFF;
            sb.append("\"color\":").append(decimal).append(",");
        } catch (NumberFormatException e) {
            sb.append("\"color\":2831050,"); // Default teal color
        }
        
        // Description - Use violation content from config
        sb.append("\"description\":\"");
        
        // ALWAYS use a consistent format with "Violations" header for all alerts
        // Start with player info
        sb.append("**Player**: ").append(escapeJson(player.getName())).append("\\n");
        
        // Create a clean list of violations
        List<String> cleanViolations = new ArrayList<>();
        
        // If we have explicit violations list, use it
        if (violations != null && !violations.isEmpty()) {
            cleanViolations = new ArrayList<>(violations);
            cleanViolations.removeIf(v -> v.contains("Multiple Violations"));
        } 
        // Otherwise use the reason as a single violation
        else if (reason != null && !reason.isEmpty()) {
            cleanViolations.add(reason);
        }
        
        // Always show "Violations:" header with bullet points
        sb.append("**Violations**:\\n");
        
        // Add each violation as a bullet point
        for (String violation : cleanViolations) {
            // Clean up any "(Client: X)" text
            int clientIndex = violation.indexOf(" (Client: ");
            String cleanViolation = clientIndex > 0 ? violation.substring(0, clientIndex) : violation;
            
            // Reasons are translated, so no wording is matched: what follows the last ": " is the brand, channel or
            // pattern the reason names, and it goes in backticks
            int patternStartIndex = cleanViolation.lastIndexOf(": ");
            if (patternStartIndex > 0 && patternStartIndex + 2 < cleanViolation.length()) {
                sb.append("• ").append(escapeJson(cleanViolation.substring(0, patternStartIndex + 2)))
                        .append("`").append(escapeJson(cleanViolation.substring(patternStartIndex + 2))).append("`\\n");
            } else {
                sb.append("• ").append(escapeJson(cleanViolation)).append("\\n");
            }
        }
        
        // Add other standard information
        sb.append("**Client Version**: ").append(getClientVersionFromPlaceholders(player)).append("\\n");
        
        // Wrap brand in backticks for Discord
        sb.append("**Brand**: `").append(escapeJson(Text.inert(brand != null ? brand : "unknown"))).append("`\\n");
        
        // Add channels
        sb.append("**Channels**:\\n");
        PlayerProfile data = plugin.profiles().get(player.getUniqueId());
        if (data != null && !data.getChannels().isEmpty()) {
            Set<String> channels = data.getChannels();
            for (String ch : channels) {
                // Wrap channel in backticks for Discord
                sb.append("• `").append(escapeJson(ch)).append("`\\n");
            }
        } else {
            sb.append("• None detected\\n");
        }
        
        // Close the description
        sb.append("\",");
        
        // Timestamp
        sb.append("\"timestamp\":\"").append(java.time.OffsetDateTime.now()).append("\"");
        
        sb.append("}]}");
        return sb.toString();
    }
    
    /**
     * Helper method to get client version from PlaceholderAPI
     */
    private String getClientVersionFromPlaceholders(Player player) {
        String version = "Unknown";
        // Check if ViaVersion and PlaceholderAPI are installed
        if (Bukkit.getPluginManager().isPluginEnabled("ViaVersion") && 
            Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                // If so, try to get the version through PlaceholderAPI
                version = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                    player, "%viaversion_player_protocol_version%");
            } catch (Exception e) {
                if (config.isDebugMode()) {
                    plugin.getLogger().warning("[Discord] Error getting ViaVersion: " + e.getMessage());
                }
            }
        }
        return version;
    }
    
    private static String escapeJson(String text) {
        return Webhook.escape(text);
    }
}