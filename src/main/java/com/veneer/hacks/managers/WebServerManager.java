package com.veneer.hacks.managers;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.HackDefinition;
import com.veneer.core.FoliaScheduler;
import com.veneer.core.IpDatabase;
import com.veneer.core.PlayerProfile;
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
    private IpDatabase ipdb;
    private com.veneer.core.WrappedTask cacheTimer;
    /** Ticks of the online-player cache timer (every 5 s); the ban list is read every 12th run (once a minute). */
    private static final long CACHE_TICKS = 100L;
    private int cacheRuns;

    public WebServerManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        // Nothing else runs when the port cannot be bound: no timer, no 50 MB IP database
        if (!start()) return;
        var links = links();
        if (links.getBoolean("ip-database", true)) {
            ipdb = new IpDatabase(plugin, links.getInt("ip-database-refresh-days", 7),
                    links.getIntegerList("weak-provider-asns"), links.getStringList("weak-provider-keywords"));
            ipdb.start();
        }
        cacheTimer = FoliaScheduler.runGlobalTimer(plugin, this::updatePlayerCache, 1L, CACHE_TICKS);
    }

    private boolean start() {
        int port = plugin.hacks().config().getWebPort();
        try {
            String bind = plugin.hacks().config().getWebBind();
            // The JDK server reads the request on a worker thread with no time limit by default: a few connections
            // that send their headers byte by byte would hold every worker. Read once per JVM, so set it only
            // when nobody (another plugin, the start script) has.
            if (System.getProperty("sun.net.httpserver.maxReqTime") == null)
                System.setProperty("sun.net.httpserver.maxReqTime", "20");
            if (System.getProperty("sun.net.httpserver.maxRspTime") == null)
                System.setProperty("sun.net.httpserver.maxRspTime", "60");
            server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getByName(bind), port), 0);
            server.createContext("/", this::handle);
            executor = Executors.newFixedThreadPool(8);
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Web panel listening on " + bind + ":" + port);
            if (!java.net.InetAddress.getByName(bind).isLoopbackAddress())
                plugin.getLogger().warning("The web panel listens on " + bind + " over plain HTTP: tokens and IP addresses "
                        + "cross the network unencrypted. Put it behind an HTTPS reverse proxy (see hacks.web-editor.public-url).");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to start the web panel: " + e.getMessage());
            if (executor != null) executor.shutdownNow();
            return false;
        }
    }

    /** Drops the cached account graph: levels or link settings changed (/veneer reload). */
    public void clearCache() {
        graphCache.clear();
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
        Set<String> flagged = new HashSet<>();
        boolean spoof = plugin.spoof() != null && plugin.spoof().running() && plugin.spoof().detection() != null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("uuid", p.getUniqueId().toString());
            list.add(m);
            PlayerProfile profile = plugin.profileOf(p.getUniqueId());
            if ((spoof && plugin.spoof().detection().isFlagged(p.getUniqueId()))
                    || (profile != null && (profile.isAlreadyPunished() || profile.isVersionMismatch())))
                flagged.add(p.getUniqueId().toString());
        }
        cachedPlayers = list;
        cachedFlagged = flagged;
        // The server's own ban list, once a minute: it can hold tens of thousands of entries. Bans kept by a ban
        // plugin (LiteBans, AdvancedBan) are not in it.
        if (cacheRuns++ % 12 == 0) cachedBanned = bannedIds();
    }

    @SuppressWarnings({"deprecation", "rawtypes"})
    private static Set<String> bannedIds() {
        Set<String> banned = new HashSet<>();
        try {
            for (Object e : Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE).getEntries()) {
                Object target = ((org.bukkit.BanEntry) e).getBanTarget();
                if (target instanceof org.bukkit.profile.PlayerProfile p && p.getUniqueId() != null)
                    banned.add(p.getUniqueId().toString());
            }
        } catch (RuntimeException ignored) {
            // A server without profile bans: no banned accounts shown
        }
        return banned;
    }

    private volatile Set<String> cachedFlagged = Set.of(), cachedBanned = Set.of();

    /**
     * The graph with the live state of each account: online, flagged by the spoof module this session, banned.
     * New node maps, the cached graph is shared and outlives the state.
     */
    private Map<String, Object> withStatus(Map<String, Object> graph) {
        Set<String> online = new HashSet<>();
        for (Map<String, Object> p : cachedPlayers) online.add((String) p.get("uuid"));
        Set<String> flagged = cachedFlagged, banned = cachedBanned;
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object n : (List<?>) graph.get("nodes")) {
            @SuppressWarnings("unchecked") Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) n);
            String id = (String) node.get("id");
            node.put("online", online.contains(id));
            node.put("spoof", flagged.contains(id));
            node.put("banned", banned.contains(id));
            nodes.add(node);
        }
        Map<String, Object> out = new LinkedHashMap<>(graph);
        out.put("nodes", nodes);
        return out;
    }

    private void handle(HttpExchange ex) {
        try {
            String path   = ex.getRequestURI().getPath();
            String query  = ex.getRequestURI().getRawQuery(); // raw: parseQuery decodes each value once
            Map<String, String> params = parseQuery(query);

            addSecurityHeaders(ex);

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
        } catch (com.google.gson.JsonParseException | ClassCastException | IllegalArgumentException e) {
            // Malformed JSON, a field of the wrong type or a bad %-escape: the request is at fault
            try { sendJson(ex, 400, Map.of("error", "bad request")); } catch (Exception ignored) {}
        } catch (Exception e) {
            // Logged with the details; the client gets no exception text
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            plugin.getLogger().warning("[Hacks] Web panel request " + ex.getRequestURI().getPath() + " failed: " + error);
            try { sendJson(ex, 500, Map.of("error", "internal error")); } catch (Exception ignored) {}
        } finally {
            ex.close();
        }
    }

    private void serveEditor(HttpExchange ex, Map<String, String> params) throws IOException {
        String token = params.get("token");
        if (!isTokenShaped(token) || plugin.database().validateToken(token) == null) {
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
        // Anything that is not a token /hackeditor could have issued is refused without taking the database lock
        Map<String, String> playerInfo = isTokenShaped(token) ? plugin.database().validateToken(token) : null;

        if (playerInfo == null) {
            sendJson(ex, 401, Map.of("error", "unauthorized"));
            return;
        }

        String method = ex.getRequestMethod();

        if (path.equals("/api/validate")) {
            Map<String, Object> info = new LinkedHashMap<>(playerInfo);
            // Level of every configured mod, for colouring scan results that carry only the mod id
            Map<String, Integer> levels = new LinkedHashMap<>();
            for (HackDefinition h : plugin.hacks().config().getHacks().values()) levels.put(h.getId(), h.getLevel());
            info.put("levels", levels);
            sendJson(ex, 200, info);
            return;
        }

        if (path.equals("/api/players/online")) {
            sendJson(ex, 200, cachedPlayers);
            return;
        }

        if (path.equals("/api/stats")) {
            Map<String, Object> stats = plugin.database().getStats(plugin.hacks().config().toleratedIds());
            stats.put("online", cachedPlayers.size());
            sendJson(ex, 200, stats);
            return;
        }

        if (path.equals("/api/players/search")) {
            String q = params.getOrDefault("q", "").trim();
            sendJson(ex, 200, q.isEmpty() || q.length() > 16 ? List.of() : plugin.database().searchPlayers(q, 12));
            return;
        }

        if (path.equals("/api/mods")) {
            List<Map<String, Object>> mods = plugin.database().getDetectedMods();
            withLevels(mods);
            // Cheats first, then forbidden, then tolerated mods; most players first within a level (stable sort)
            mods.sort(Comparator.comparingInt(m -> (Integer) m.get("level")));
            sendJson(ex, 200, mods);
            return;
        }

        if (path.startsWith("/api/links") && !can(playerInfo, "links")) {
            sendJson(ex, 403, Map.of("error", "no permission: veneer.web.links"));
            return;
        }

        if (path.startsWith("/api/links/")) {
            // getPath() is already decoded
            Map<String, Object> details = plugin.database().getLinkDetails(path.substring("/api/links/".length()));
            withLevels((List<?>) details.get("mods"));
            if (!can(playerInfo, "ips")) details.put("ips", List.of());
            for (Object r : (List<?>) details.get("ips")) {
                @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) r;
                describeIp((String) row.get("ip"), row);
            }
            sendJson(ex, 200, details);
            return;
        }

        if (path.equals("/api/links")) {
            sendJson(ex, 200, withStatus(linkGraph(playerInfo)));
            return;
        }

        if (path.equals("/api/scans")) {
            String type  = params.get("type");
            // Clamped: limit=-1 means "no limit" to SQLite and would read the whole history under the DB lock
            int    limit = Math.max(1, Math.min(100, parseInt(params.get("limit"), 50)));
            String validType = ("hack".equals(type) || "lang".equals(type)) ? type : null;
            sendJson(ex, 200, plugin.database().getRecentScans(validType, limit,
                    Math.max(0, parseInt(params.get("offset"), 0)), "1".equals(params.get("detected")) ? true : null, null));
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
            String type       = req != null && req.get("type") != null ? (String) req.get("type") : "hack";
            // Only the two kinds of scan; anything else would also end up in the console log as written
            if (!"hack".equals(type) && !"lang".equals(type)) {
                sendJson(ex, 400, Map.of("error", "type must be hack or lang"));
                return;
            }

            if (targetName == null || targetName.isBlank()) {
                sendJson(ex, 400, Map.of("error", "player is required"));
                return;
            }

            final String checkerName = playerInfo.get("player_name");
            final String finalType   = type;
            final String finalTarget = targetName;
            // From the cache, not Bukkit: this runs on an HTTP thread
            if (cachedPlayers.stream().noneMatch(p -> finalTarget.equalsIgnoreCase((String) p.get("name")))) {
                sendJson(ex, 404, Map.of("error", "player is not online"));
                return;
            }
            boolean nothing = "lang".equals(type) ? plugin.hacks().config().getLanguages().isEmpty()
                    : plugin.hacks().config().getDefaultCheckHacks().isEmpty();
            if (nothing) {
                sendJson(ex, 409, Map.of("error", "lang".equals(type) ? "no languages configured" : "no hacks configured"));
                return;
            }
            plugin.getLogger().info("[Hacks] Web panel: " + checkerName + " started a " + type + " scan of " + targetName);

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
                    if (ok) plugin.getLogger().info("[Hacks] Web panel: " + playerInfo.get("player_name") + " deleted scan #" + id);
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
            String name = path.substring("/api/player/".length()); // getPath() is already decoded
            // From the cache, not Bukkit: this runs on an HTTP thread
            boolean online = false;
            String uuid = "";
            for (Map<String, Object> p : cachedPlayers)
                if (name.equalsIgnoreCase((String) p.get("name"))) { uuid = (String) p.get("uuid"); online = true; break; }
            Map<String, Object> record = plugin.database().getPlayerRecord(name);
            if (uuid.isEmpty()) uuid = (String) record.getOrDefault("uuid", "");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name",   name);
            result.put("uuid",   uuid);
            result.put("online", online);
            result.put("known",  !uuid.isEmpty());
            result.put("first_seen", record.get("first_seen"));
            result.put("last_seen",  record.get("last_seen"));
            result.put("joins",      record.get("joins"));
            result.put("client",     online ? liveClient(uuid) : record.get("client"));
            if (!uuid.isEmpty()) {
                Map<String, Object> details = plugin.database().getLinkDetails(uuid);
                withLevels((List<?>) details.get("mods"));
                result.put("mods", details.get("mods"));
                result.put("fingerprint", details.get("fingerprint"));
                if (can(playerInfo, "ips")) {
                    for (Object r : (List<?>) details.get("ips")) {
                        @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) r;
                        describeIp((String) row.get("ip"), row);
                    }
                    result.put("ips", details.get("ips"));
                }
                if (can(playerInfo, "links")) result.put("alts", alts(linkGraph(playerInfo), uuid));
            }
            result.put("scans",  plugin.database().getPlayerScans(uuid, name));
            sendJson(ex, 200, result);
            return;
        }

        sendJson(ex, 404, Map.of("error", "endpoint not found"));
    }

    /**
     * The account graph as the token may see it: IP links weakened for VPN/hosting providers, and addresses
     * hidden without {@code veneer.web.ips}.
     */
    private Map<String, Object> linkGraph(Map<String, String> playerInfo) {
        boolean ips = can(playerInfo, "ips");
        // The player page asks for it on every refresh (every 2.5 s while a scan runs): build it at most every 15 s
        CachedGraph cached = graphCache.get(ips);
        if (cached != null && System.currentTimeMillis() - cached.built() < GRAPH_TTL_MS) return cached.graph();
        // One build at a time: requests that miss together wait for it instead of each comparing every fingerprint
        synchronized (graphLock) {
            cached = graphCache.get(ips);
            if (cached != null && System.currentTimeMillis() - cached.built() < GRAPH_TTL_MS) return cached.graph();
            Map<String, Object> graph = buildLinkGraph(ips);
            graphCache.put(ips, new CachedGraph(graph, System.currentTimeMillis()));
            return graph;
        }
    }

    private record CachedGraph(Map<String, Object> graph, long built) {}
    private final Object graphLock = new Object();
    private static final long GRAPH_TTL_MS = 15_000;
    private final Map<Boolean, CachedGraph> graphCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Map<String, Object> buildLinkGraph(boolean ips) {
        var links = links();
        // The same rule as RPTrack's own join check (match clamped to send, unique mode), so the graph shows
        // exactly the links RPTrack reports
        var rp = plugin.rptrack();
        Map<String, Object> graph = plugin.database().getLinkGraph(links.getInt("max-accounts-per-ip", 8), rp::linked, rp.minMatch(),
                links.getInt("max-accounts-per-client", 5), links.getInt("client-min-channels", 3));
        double weakFactor = Math.max(0, Math.min(1, links.getDouble("weak-provider-factor", 0.3)));
        for (Object e : (List<?>) graph.get("edges")) {
            @SuppressWarnings("unchecked") Map<String, Object> edge = (Map<String, Object>) e;
            if (!"ip".equals(edge.get("type"))) continue;
            // Strangers share the exit addresses of VPNs, WARP and hosting: such a shared address proves little.
            // Without the ips right the provider, ASN and country stay out too: they narrow the address down.
            if (describeIp((String) edge.get("label"), ips ? edge : new HashMap<>()))
                edge.put("p", ((Number) edge.get("p")).doubleValue() * weakFactor);
            if (!ips) edge.put("label", "(hidden)");
        }
        var hacks = plugin.hacks().config();
        for (Object n : (List<?>) graph.get("nodes")) {
            @SuppressWarnings("unchecked") Map<String, Object> node = (Map<String, Object>) n;
            if (!ips) node.put("ips", List.of());
            // The most serious mod found decides the colour: 1 = cheat, 2 = forbidden, 3 = tolerated, 0 = none
            int level = Boolean.TRUE.equals(node.get("liquidbounce")) ? 1 : 0;
            for (Object id : (List<?>) node.get("mod_ids")) {
                int l = hacks.levelOf((String) id);
                if (level == 0 || l < level) level = l;
            }
            node.put("level", level);
            // Tolerated mods (level 3) do not make an account one with mods
            node.put("detected", level == 1 || level == 2);
        }
        return graph;
    }

    /**
     * Accounts linked to {@code uuid} in the graph, strongest first: name, uuid, pct (reasons merged the way the
     * graph merges them, 1 - product of (1 - p)) and the link types.
     */
    private static List<Map<String, Object>> alts(Map<String, Object> graph, String uuid) {
        Map<String, String> names = new HashMap<>();
        for (Object n : (List<?>) graph.get("nodes")) {
            Map<?, ?> node = (Map<?, ?>) n;
            names.put((String) node.get("id"), (String) node.get("name"));
        }
        // Per other account: [miss of non-address reasons, best address p, addresses shared]
        Map<String, double[]> acc = new LinkedHashMap<>();
        Map<String, Set<String>> types = new HashMap<>();
        for (Object e : (List<?>) graph.get("edges")) {
            Map<?, ?> edge = (Map<?, ?>) e;
            String other = uuid.equals(edge.get("source")) ? (String) edge.get("target")
                    : uuid.equals(edge.get("target")) ? (String) edge.get("source") : null;
            if (other == null) continue;
            double p = ((Number) edge.get("p")).doubleValue();
            double[] a = acc.computeIfAbsent(other, k -> new double[]{1, 0, 0});
            if ("ip".equals(edge.get("type"))) { a[1] = Math.max(a[1], p); a[2]++; }
            else a[0] *= 1 - p;
            types.computeIfAbsent(other, k -> new TreeSet<>()).add((String) edge.get("type"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, double[]> m : acc.entrySet()) {
            double[] a = m.getValue();
            Map<String, Object> alt = new LinkedHashMap<>();
            alt.put("uuid", m.getKey());
            alt.put("name", names.getOrDefault(m.getKey(), m.getKey()));
            alt.put("pct", 1 - a[0] * (1 - addressP(a[1], (int) a[2])));
            alt.put("types", types.get(m.getKey()));
            out.add(alt);
        }
        out.sort((a, b) -> Double.compare((Double) b.get("pct"), (Double) a.get("pct")));
        return out;
    }

    /**
     * The address part of a pair's match: the best shared address plus 5 % per extra one, at most 70 %. Addresses
     * of one household or one provider's pool are not independent signs, so they are not multiplied like the other
     * reasons (four shared addresses of a dynamic line would otherwise read as 96 %). editor.html does the same.
     */
    static double addressP(double best, int count) {
        return count == 0 ? 0 : Math.min(0.7, best + 0.05 * (count - 1));
    }

    /** Adds {@code level} (1 = cheat, 2 = forbidden, 3 = tolerated, from config.yml) to each mod row by its id. */
    private void withLevels(List<?> mods) {
        for (Object o : mods) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            m.put("level", plugin.hacks().config().levelOf((String) m.get("id")));
        }
    }

    /** Brand, channels, loader and version probe of an online player, from the live profile. */
    private Map<String, Object> liveClient(String uuid) {
        Map<String, Object> client = new LinkedHashMap<>();
        PlayerProfile profile = plugin.profileOf(UUID.fromString(uuid));
        if (profile == null) return client;
        client.put("brand", profile.getBrand());
        client.put("channels", new TreeSet<>(profile.getChannels()));
        client.put("type", profile.clientType().name());
        client.put("version_real", profile.getVersionReal());
        client.put("version_claimed", profile.getVersionClaimed());
        client.put("version_mismatch", profile.isVersionMismatch());
        client.put("live", true);
        return client;
    }

    /** Whether the token was issued to a player with {@code veneer.web.<perm>} (rights are fixed at issue time). */
    private static boolean can(Map<String, String> playerInfo, String perm) {
        return Arrays.asList(playerInfo.getOrDefault("perms", "").split(",")).contains(perm);
    }

    /** The panel is same-origin, so no CORS; the token is in the URL, so no referrer to the head images either. */
    private void addSecurityHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
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
        if (cacheTimer != null) cacheTimer.cancel();
        if (ipdb != null) ipdb.stop();
        if (server != null) server.stop(0);
        if (executor != null) {
            // Requests still running get a moment to finish before the database closes under them
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 32 hex digits, the form {@link com.veneer.core.DatabaseManager#saveToken} issues. */
    private static boolean isTokenShaped(String token) {
        if (token == null || token.length() != 32) return false;
        for (int i = 0; i < 32; i++) if (Character.digit(token.charAt(i), 16) < 0) return false;
        return true;
    }
}
