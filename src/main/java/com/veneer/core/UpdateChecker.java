package com.veneer.core;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.veneer.VeneerPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up the latest GitHub release of Veneer and tells admins when a newer one exists. */
public final class UpdateChecker implements Listener {

    public static final String REPO = "ReAndrew/veneer";
    public static final String PERMISSION = "veneer.admin";

    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern NUMBERS = Pattern.compile("\\d+");

    /** Outcome of a lookup; {@code latest} is null when the release could not be determined. */
    public record Status(String current, String latest, String url) {
        public boolean updateAvailable() {
            return latest != null && isNewer(latest, current);
        }
    }

    private final VeneerPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Status status;

    public UpdateChecker(VeneerPlugin plugin) {
        this.plugin = plugin;
    }

    /** The last lookup, or null before the first one finished. */
    public Status status() {
        return status;
    }

    /** Starts the join notification and the first lookup. */
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        lookup(true);
    }

    /** Queries GitHub off the main thread and, when {@code announce} is set, logs a newer release. */
    public void lookup(boolean announce) {
        FoliaScheduler.runAsync(plugin, () -> {
            String current = plugin.getDescription().getVersion();
            try {
                status = fetch(current);
            } catch (IOException | RuntimeException e) {
                plugin.debug("Update check failed: " + e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (announce && status.updateAvailable()) {
                plugin.getLogger().info("Veneer " + status.latest() + " is available (running " + current + "): " + status.url());
                if (plugin.config().updateNotifyOnJoin())
                    for (Player p : plugin.getServer().getOnlinePlayers())
                        if (p.hasPermission(PERMISSION))
                            FoliaScheduler.runAtEntity(plugin, p, () -> notify(p, status));
            }
        });
    }

    private Status fetch(String current) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Veneer/" + current)
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return new Status(current, null, null);   // no release published yet
        if (response.statusCode() != 200) throw new IOException("GitHub answered HTTP " + response.statusCode());
        JsonObject json = new Gson().fromJson(response.body(), JsonObject.class);
        String tag = json.get("tag_name").getAsString();
        String url = json.has("html_url") ? json.get("html_url").getAsString() : "https://github.com/" + REPO + "/releases";
        return new Status(current, tag.startsWith("v") ? tag.substring(1) : tag, url);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Status s = status;
        if (s == null || !s.updateAvailable() || !plugin.config().updateNotifyOnJoin()) return;
        if (!player.hasPermission(PERMISSION)) return;
        FoliaScheduler.runAtEntityLater(plugin, player, () -> notify(player, s), 40L);
    }

    private void notify(Player player, Status s) {
        if (!player.isOnline()) return;
        player.sendMessage(plugin.messages().prefixed("<green>Veneer " + s.latest() + " is available</green> <gray>(running "
                + s.current() + ")</gray> <aqua><click:open_url:'" + s.url() + "'>" + s.url() + "</click></aqua>"));
    }

    /** True when {@code latest} has a higher numeric version than {@code current} (1.10.0 &gt; 1.9.0). */
    static boolean isNewer(String latest, String current) {
        int[] a = parts(latest), b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        Matcher m = NUMBERS.matcher(version.split("[-+]", 2)[0]);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group()));
            } catch (NumberFormatException e) {
                out.add(Integer.MAX_VALUE);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
