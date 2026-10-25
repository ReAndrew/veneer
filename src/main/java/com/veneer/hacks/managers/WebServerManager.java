package com.veneer.hacks.managers;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.IpDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServerManager {

    private final VeneerPlugin plugin;
    private HttpServer server;
    private ExecutorService executor;
    private static final int MAX_BODY = 4096;
    private final Gson gson = new Gson();
    private volatile List<Map<String, Object>> cachedPlayers = new ArrayList<>();
    private final IpDatabase ipdb;

    public WebServerManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        var links = links();
        if (links.getBoolean("ip-database", true)) {
            ipdb = new IpDatabase(plugin, links.getInt("ip-database-refresh-days", 7),
                    links.getIntegerList("weak-provider-asns"), links.getStringList("weak-provider-keywords"));
            ipdb.start();
        } else {
            ipdb = null;
        }
        start();
        FoliaScheduler.runGlobalTimer(plugin, this::updatePlayerCache, 1L, 100L);
    }

    private void start() {
        int port = plugin.hacks().config().getWebPort();
        try {
            String bind = plugin.hacks().config().getWebBind();
            server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getByName(bind), port), 0);
            server.createContext("/", this::handle);
            executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Web panel listening on " + bind + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start the web panel: " + e.getMessage());
        }
    }

    private org.bukkit.configuration.ConfigurationSection links() {
        return plugin.config().section("hacks.web-editor.links");
    }

    /** Adds provider, ASN and country of {@code ip} to {@code row}; true when it is a VPN/WARP/hosting address. */
    private boolean describeIp(String ip, Map<String, Object> row) {
        IpDatabase.Info info = ipdb != null ? ipdb.lookup(ip) : null;
        if (info == null) return false;
        row.put("org", info.org());
        row.put("asn", info.asn());
        row.put("country", info.country());
        row.put("weak", info.weak());
        return info.weak();
    }

    private void updatePlayerCache() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("uuid", p.getUniqueId().toString());
            list.add(m);
        }
        cachedPlayers = list;
    }

    private void handle(HttpExchange ex) {
        try {
            String path   = ex.getRequestURI().getPath();
            String query  = ex.getRequestURI().getQuery();
            Map<String, String> params = parseQuery(query);

            addCorsHeaders(ex);

            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            if (path.equals("/") || path.equals("/editor")) {
                serveEditor(ex, params);
                return;
            }

            if (path.equals("/web/3d-force-graph.min.js") || path.equals("/web/three.min.js")) {
                serveLibrary(ex, path.substring(1));
                return;
            }

            if (path.startsWith("/api/")) {
                handleApi(ex, path, params);
                return;
            }

            sendJson(ex, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            // Map.of rejects a null value, and many exceptions have no message: the client would never get an answer
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            plugin.getLogger().warning("[Hacks] Web panel request " + ex.getRequestURI().getPath() + " failed: " + error);
            try { sendJson(ex, 500, Map.of("error", error)); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    private void serveEditor(HttpExchange ex, Map<String, String> params) throws IOException {
        String token = params.get("token");
        if (token == null || plugin.database().validateToken(token) == null) {
            sendHtml(ex, 403, "<html><body style='font-family:monospace;background:#0d1117;color:#e6edf3;display:flex;align-items:center;justify-content:center;height:100vh;margin:0'><div>Invalid or expired token.<br>Run <b>/hackeditor</b> in Minecraft.</div></body></html>");
            return;
        }
        try (InputStream is = plugin.getResource("web/editor.html")) {
            if (is == null) { sendHtml(ex, 500, "<h1>editor.html missing</h1>"); return; }
            sendHtml(ex, 200, new String(is.readAllBytes(), StandardCharsets.UTF_8).replace("/*I18N*/{}", translations()));
        }
    }

    /** The panel texts for general.language from web/lang/<language>.json, or {} (English, as written in the page). */
    private String translations() {
        try (InputStream is = plugin.getResource("web/lang/" + plugin.config().language() + ".json")) {
            return is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "{}";
        } catch (IOException e) {
            return "{}";
        }
    }

    /** Bundled scripts; they are public library code, so no token is needed. */
    private void serveLibrary(HttpExchange ex, String resource) throws IOException {
        try (InputStream is = plugin.getResource(resource)) {
            if (is == null) { sendJson(ex, 404, Map.of("error", "not found")); return; }
            byte[] bytes = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    private void handleApi(HttpExchange ex, String path, Map<String, String> params) throws IOException {
        String token = params.get("token");
        Map<String, String> playerInfo = token != null ? plugin.database().validateToken(token) : null;

        if (playerInfo == null) {
            sendJson(ex, 401, Map.of("error", "unauthorized"));
            return;
        }

        String method = ex.getRequestMethod();

        if (path.equals("/api/validate")) {
            sendJson(ex, 200, playerInfo);
            return;
        }

        if (path.equals("/api/players/online")) {
            sendJson(ex, 200, cachedPlayers);
            return;
        }

        if (path.equals("/api/mods")) {
            sendJson(ex, 200, plugin.database().getDetectedMods());
            return;
        }

        if (path.startsWith("/api/links") && !can(playerInfo, "links")) {
            sendJson(ex, 403, Map.of("error", "no permission: veneer.web.links"));
            return;
        }

        if (path.startsWith("/api/links/")) {
            Map<String, Object> details = plugin.database().getLinkDetails(
                    URLDecoder.decode(path.substring("/api/links/".length()), StandardCharsets.UTF_8));
            if (!can(playerInfo, "ips")) details.put("ips", List.of());
            for (Object r : (List<?>) details.get("ips")) {
                @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) r;
                describeIp((String) row.get("ip"), row);
            }
            sendJson(ex, 200, details);
            return;
        }

        if (path.equals("/api/links")) {
            var links = links();
            var rp = plugin.config().section("rptrack");
            int match = rp != null ? rp.getInt("match", 5) : 5;
            Map<String, Object> graph = plugin.database().getLinkGraph(links.getInt("max-accounts-per-ip", 8), match,
                    links.getInt("max-accounts-per-client", 5), links.getInt("client-min-channels", 3));
            boolean ips = can(playerInfo, "ips");
            double weakFactor = Math.max(0, Math.min(1, links.getDouble("weak-provider-factor", 0.3)));
            for (Object e : (List<?>) graph.get("edges")) {
                @SuppressWarnings("unchecked") Map<String, Object> edge = (Map<String, Object>) e;
                if (!"ip".equals(edge.get("type"))) continue;
                // Strangers share the exit addresses of VPNs, WARP and hosting: such a shared address proves little
                if (describeIp((String) edge.get("label"), edge))
                    edge.put("p", ((Number) edge.get("p")).doubleValue() * weakFactor);
                if (!ips) edge.put("label", "(hidden)");
            }
            if (!ips)
                for (Object n : (List<?>) graph.get("nodes")) {
                    @SuppressWarnings("unchecked") Map<String, Object> node = (Map<String, Object>) n;
                    node.put("ips", List.of());
                }
            sendJson(ex, 200, graph);
            return;
        }

        if (path.equals("/api/scans")) {
            String type  = params.get("type");
            // Clamped: limit=-1 means "no limit" to SQLite and would read the whole history under the DB lock
            int    limit = Math.max(1, Math.min(500, parseInt(params.get("limit"), 50)));
            String validType = ("hack".equals(type) || "lang".equals(type)) ? type : null;
            sendJson(ex, 200, plugin.database().getRecentScans(validType, limit));
            return;
        }

        if (path.equals("/api/scan/run") && "POST".equals(method)) {
            if (!can(playerInfo, "scan")) {
                sendJson(ex, 403, Map.of("error", "no permission: veneer.web.scan"));
                return;
            }
            // The body is a player name and a type; anything much bigger is not from the panel
            byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                sendJson(ex, 413, Map.of("error", "request too large"));
                return;
            }
            String body = new String(raw, StandardCharsets.UTF_8);
            Map<?, ?> req = gson.fromJson(body, Map.class);
            String targetName = req != null ? (String) req.get("player") : null;
            String type       = req != null ? (String) req.get("type")   : "hack";

            if (targetName == null || targetName.isBlank()) {
                sendJson(ex, 400, Map.of("error", "player is required"));
                return;
            }

            final String checkerName = playerInfo.get("player_name");
            final String finalType   = type;
            final String finalTarget = targetName;

            FoliaScheduler.runGlobal(plugin, () -> {
                Player target = Bukkit.getPlayerExact(finalTarget);
                if (target == null) return;
                FoliaScheduler.runAtEntity(plugin, target, () -> {
                    if ("lang".equals(finalType)) {
                        Map<String, String> langs = plugin.hacks().config().getLanguages();
                        if (!langs.isEmpty())
                            plugin.hacks().langChecks().startCheck(target, null, langs);
                    } else {
                        List<HackDefinition> hacks = plugin.hacks().config().getDefaultCheckHacks();
                        if (!hacks.isEmpty())
                            plugin.hacks().checks().startCheck(target, null, hacks, false,
                                    "Web editor check by " + checkerName);
                    }
                });
            });

            sendJson(ex, 200, Map.of("success", true));
            return;
        }

        if (path.startsWith("/api/scan/")) {
            String idStr = path.substring("/api/scan/".length());
            try {
                long id = Long.parseLong(idStr);
                if ("DELETE".equals(method)) {
                    if (!can(playerInfo, "delete")) {
                        sendJson(ex, 403, Map.of("error", "no permission: veneer.web.delete"));
                        return;
                    }
                    boolean ok = plugin.database().deleteScan(id);
                    sendJson(ex, 200, Map.of("success", ok));
                } else {
                    Map<String, Object> scan = plugin.database().getScan(id);
                    if (scan == null) sendJson(ex, 404, Map.of("error", "scan not found"));
                    else              sendJson(ex, 200, scan);
                }
            } catch (NumberFormatException e) {
                sendJson(ex, 400, Map.of("error", "invalid id: " + idStr));
            }
            return;
        }

        if (path.startsWith("/api/player/")) {
            String name = URLDecoder.decode(
                    path.substring("/api/player/".length()), StandardCharsets.UTF_8);
            // From the cache, not Bukkit: this runs on an HTTP thread
            boolean online = false;
            String uuid = "";
            for (Map<String, Object> p : cachedPlayers)
                if (name.equalsIgnoreCase((String) p.get("name"))) { uuid = (String) p.get("uuid"); online = true; break; }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name",   name);
            result.put("uuid",   uuid);
            result.put("online", online);
            result.put("scans",  plugin.database().getPlayerScans(name));
            sendJson(ex, 200, result);
            return;
        }

        sendJson(ex, 404, Map.of("error", "endpoint not found"));
    }

    /** Whether the token was issued to a player with {@code veneer.web.<perm>} (rights are fixed at issue time). */
    private static boolean can(Map<String, String> playerInfo, String perm) {
        return Arrays.asList(playerInfo.getOrDefault("perms", "").split(",")).contains(perm);
    }

    private void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJson(HttpExchange ex, int code, Object obj) throws IOException {
        byte[] bytes = gson.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendHtml(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2)
                params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }
}
