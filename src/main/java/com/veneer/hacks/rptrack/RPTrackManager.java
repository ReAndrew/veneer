package com.veneer.hacks.rptrack;

import com.veneer.VeneerConfig;
import com.veneer.VeneerPlugin;
import com.veneer.hacks.utils.FoliaScheduler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Resource-pack cache fingerprinting (technique described at
 * https://alaggydev.github.io/posts/cytooxien/).
 *
 * <p>The client keeps downloaded packs in {@code .minecraft/downloads}, keyed by SHA-1, across accounts.
 * On join we offer N probe packs with a known hash but an unreachable URL: a pack answers
 * SUCCESSFULLY_LOADED only when it is already cached. A fresh client gets a random subset of packs
 * downloaded for real; later logins on any account/IP reproduce that subset.
 */
public class RPTrackManager implements Listener {

    private static final String DEAD_URL = "http://127.0.0.1:0";
    private static final String LB_URL = "http://127.0.0.1:15000/default/img/steve.png";
    private static final UUID HIDE_ID = UUID.nameUUIDFromBytes("veneer-hide".getBytes(StandardCharsets.UTF_8));
    private static final String[] LANGS = ("af_za ar_sa ast_es az_az bar ba_ru be_by be_latn bg_bg brb br_fr bs_ba ca_es cs_cz cv_cu "
            + "cy_gb da_dk de_at de_ch de_de el_gr en_au en_ca en_gb en_nz enp en_pt en_ud en_us enws eo_uy esan es_ar es_cl es_ec "
            + "es_es es_mx es_uy es_ve et_ee eu_es fa_ir fi_fi fil_ph fo_fo fra_de fr_ca fr_ch fr_fr fur_it fy_nl ga_ie gd_gb gl_es "
            + "go_fr got_de hal_ua haw_us he_il hi_in hn_no hr_hr hu_hu hy_am id_id ig_ng io_en is_is isv it_it ja_jp jbo_en ka_ge "
            + "kk_kz kn_in ko_kr ksh kw_gb ky_kg la_la lb_lu li_li lmo lo_la lol_us lt_lt lv_lv lzh mk_mk mn_mn ms_my mt_mt nah "
            + "nds_de nl_be nl_nl nn_no no_no oc_fr ovd pl_pl pls pt_br pt_pt qcb_es qid qya_aa ro_ro rpr ru_ru ry_ua sah_sah "
            + "se_no sk_sk sl_si so_so sq_al sr_cs sr_sp sv_se sxu szl ta_in th_th tlh_aa tl_ph tok tr_tr tt_ru tzo_mx uk_ua "
            + "uz_uz val_es vec_it vi_vn vp_vl vro yi_de yo_ng zh_cn zh_hk zh_tw zlm_arab").split(" ");
    private static final UUID LB_ID = UUID.nameUUIDFromBytes("veneer-lb".getBytes(StandardCharsets.UTF_8));

    private enum Phase { HIDE, PROBE, ASSIGN }

    private static final class Session {
        Phase phase = Phase.HIDE;
        final Map<UUID, Integer> idToProbe = new HashMap<>();
        final Set<Integer> pending = ConcurrentHashMap.newKeySet();
        final Set<Integer> ok = ConcurrentHashMap.newKeySet();
        volatile boolean lbPending, lbHit, declined, done;
    }

    private final VeneerPlugin plugin;
    private final ConfigurationSection cfg;
    private final boolean enabled;
    private final int probes, assign, minMatch, delay, timeout;
    private final boolean lbProbe, alert;
    private final String publicUrl;
    private final byte[][] packs;
    private final byte[][] hashes;
    private final UUID[] ids;
    private byte[] hidePack, hideHash;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private HttpServer http;
    private final Map<UUID, Result> results = new ConcurrentHashMap<>();

    /** Last RPTrack outcome for an online player; used by placeholders. */
    public record Result(int packs, int linked, boolean liquidBounce) { }

    public Result getResult(UUID uuid) { return results.get(uuid); }

    public RPTrackManager(VeneerPlugin plugin) {
        this.plugin = plugin;
        this.cfg = VeneerConfig.section(plugin, "checkhacks.rptrack");
        this.enabled = cfg.getBoolean("enabled", false);
        this.probes = Math.max(1, Math.min(62, cfg.getInt("probes", 28)));
        this.assign = Math.max(1, Math.min(probes, cfg.getInt("assign", 6)));
        this.minMatch = Math.max(1, cfg.getInt("min-match", 5));
        this.delay = cfg.getInt("delay-ticks", 40);
        this.timeout = cfg.getInt("timeout-ticks", 100);
        this.lbProbe = cfg.getBoolean("liquidbounce-probe", true);
        this.alert = cfg.getBoolean("alert", true);
        String url = cfg.getString("public-url", "");
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;

        packs = new byte[probes][];
        hashes = new byte[probes][];
        ids = new UUID[probes];
        if (!enabled) return;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            hidePack = buildHidePack();
            hideHash = sha1.digest(hidePack);
            for (int i = 0; i < probes; i++) {
                packs[i] = buildPack(i + 1);
                hashes[i] = sha1.digest(packs[i]);
                ids[i] = UUID.nameUUIDFromBytes(("veneer-probe-" + (i + 1)).getBytes(StandardCharsets.UTF_8));
            }
            startHttp();
        } catch (Exception e) {
            plugin.getLogger().severe("[RPTrack] init failed: " + e.getMessage());
            return;
        }
        if (publicUrl.isEmpty())
            plugin.getLogger().warning("[RPTrack] public-url is empty: packs cannot be assigned to new clients.");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[RPTrack] enabled (" + probes + " probes, port " + cfg.getInt("port", 8123) + ")");
    }

    public boolean isEnabled() { return enabled && http != null; }

    public void stop() {
        if (http != null) http.stop(0);
    }

    /** Stored-in-zip, fixed timestamps: bytes (and so the SHA-1) never change between runs. */
    private static byte[] buildPack(int n) throws Exception {
        byte[] meta = ("{\"pack\":{\"pack_format\":46,\"description\":\"" + n + "\"}}").getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(meta);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            ZipEntry e = new ZipEntry("pack.mcmeta");
            e.setMethod(ZipEntry.STORED);
            e.setSize(meta.length);
            e.setCompressedSize(meta.length);
            e.setCrc(crc.getValue());
            e.setTime(946684800000L);
            zos.putNextEntry(e);
            zos.write(meta);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /**
     * Blanks the "failed to download" toast: empty text for every language plus a transparent toast
     * background, so failed probes are invisible to the player.
     */
    private static byte[] buildHidePack() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"supported_formats\":[22,1000],\"description\":\"\"}}"
                .getBytes(StandardCharsets.UTF_8));
        byte[] lang = "{\"download.pack.failed\":\"\",\"download.pack.title\":\"\"}".getBytes(StandardCharsets.UTF_8);
        for (String l : LANGS) files.put("assets/minecraft/lang/" + l + ".json", lang);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(160, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        files.put("assets/minecraft/textures/gui/sprites/toast/system.png", png.toByteArray());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                CRC32 crc = new CRC32();
                crc.update(f.getValue());
                ZipEntry e = new ZipEntry(f.getKey());
                e.setMethod(ZipEntry.STORED);
                e.setSize(f.getValue().length);
                e.setCompressedSize(f.getValue().length);
                e.setCrc(crc.getValue());
                e.setTime(946684800000L);
                zos.putNextEntry(e);
                zos.write(f.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private void startHttp() throws Exception {
        http = HttpServer.create(new InetSocketAddress(cfg.getString("bind", "0.0.0.0"), cfg.getInt("port", 8123)), 0);
        http.createContext("/fp/", ex -> {
            try (ex) {
                String path = ex.getRequestURI().getPath().substring("/fp/".length());
                if (path.equals("hide.zip")) {
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, hidePack.length);
                    ex.getResponseBody().write(hidePack);
                    return;
                }
                int n = -1;
                try { n = Integer.parseInt(path.replace(".zip", "")); } catch (NumberFormatException ignored) { }
                if (n < 1 || n > probes) { ex.sendResponseHeaders(404, -1); return; }
                byte[] body = packs[n - 1];
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
        });
        http.start();
    }

    // ---------------------------------------------------------------- flow

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.isBedrockPlayer(player)) return;
        FoliaScheduler.runAtEntityLater(plugin, player, () -> startHide(player), delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        results.remove(event.getPlayer().getUniqueId());
    }

    private void startHide(Player player) {
        if (!player.isOnline()) return;
        Session s = new Session();
        sessions.put(player.getUniqueId(), s);
        if (publicUrl.isEmpty()) {
            startProbe(player, s);
            return;
        }
        player.setResourcePack(HIDE_ID, publicUrl + "/fp/hide.zip", hideHash, (net.kyori.adventure.text.Component) null, false);
        FoliaScheduler.runAtEntityLater(plugin, player, () -> startProbe(player, s), timeout);
    }

    private synchronized void startProbe(Player player, Session s) {
        if (!player.isOnline() || s.done || s.phase != Phase.HIDE) return;
        s.phase = Phase.PROBE;
        for (int i = 0; i < probes; i++) {
            s.idToProbe.put(ids[i], i);
            s.pending.add(i);
            player.setResourcePack(ids[i], DEAD_URL, hashes[i], (net.kyori.adventure.text.Component) null, false);
        }
        if (lbProbe) {
            s.lbPending = true;
            player.setResourcePack(LB_ID, LB_URL, (byte[]) null, (net.kyori.adventure.text.Component) null, false);
        }
        FoliaScheduler.runAtEntityLater(plugin, player, () -> finishProbe(player, s), timeout);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.done) return;
        UUID id = event.getID();
        if (id.equals(HIDE_ID)) {
            switch (event.getStatus()) {
                case ACCEPTED, DOWNLOADED -> { }
                default -> startProbe(player, s);
            }
            return;
        }
        if (s.phase == Phase.HIDE) return;
        boolean isLb = id.equals(LB_ID);
        Integer probe = s.idToProbe.get(id);
        if (!isLb && probe == null) return;

        boolean success;
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> success = true;
            case DOWNLOADED -> { if (!isLb) return; success = true; }
            case ACCEPTED -> { return; }
            case DECLINED -> { s.declined = true; success = false; }
            default -> success = false;
        }

        if (isLb) {
            s.lbPending = false;
            s.lbHit = success;
        } else {
            s.pending.remove(probe);
            if (success) s.ok.add(probe);
        }
        if (s.pending.isEmpty() && !s.lbPending) {
            if (s.phase == Phase.PROBE) finishProbe(player, s);
            else finishAssign(player, s);
        }
    }

    private synchronized void finishProbe(Player player, Session s) {
        if (s.done || s.phase != Phase.PROBE || !player.isOnline()) return;
        removeAll(player, s);
        if (s.declined) { s.done = true; sessions.remove(player.getUniqueId()); return; }

        Set<Integer> cached = new TreeSet<>(s.ok);
        boolean lb = s.lbHit;
        if (lb) report(player, "LiquidBounce local HTTP server (port 15000) reachable");

        if (cached.isEmpty()) {
            if (publicUrl.isEmpty()) { s.done = true; return; }
            // fresh client: hand out a random subset for real
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < probes; i++) all.add(i);
            Collections.shuffle(all);
            s.phase = Phase.ASSIGN;
            s.ok.clear();
            s.pending.clear();
            s.lbPending = false;
            s.lbHit = lb;
            for (int k = 0; k < assign; k++) s.pending.add(all.get(k));
            for (int i : new ArrayList<>(s.pending))
                player.setResourcePack(ids[i], publicUrl + "/fp/" + (i + 1) + ".zip", hashes[i], (net.kyori.adventure.text.Component) null, false);
            FoliaScheduler.runAtEntityLater(plugin, player, () -> finishAssign(player, s), timeout * 3L);
        } else {
            s.done = true;
            sessions.remove(player.getUniqueId());
            analyse(player, maskOf(cached), lb);
        }
    }

    private synchronized void finishAssign(Player player, Session s) {
        if (s.done || s.phase != Phase.ASSIGN) return;
        s.done = true;
        sessions.remove(player.getUniqueId());
        if (player.isOnline()) removeAll(player, s);
        if (s.ok.isEmpty()) return;
        long mask = maskOf(s.ok);
        results.put(player.getUniqueId(), new Result(s.ok.size(), 0, s.lbHit));
        FoliaScheduler.runAsync(plugin, () -> plugin.getDatabaseManager()
                .saveRPTrack(player.getUniqueId().toString(), player.getName(), mask, s.lbHit));
    }

    private void removeAll(Player player, Session s) {
        for (UUID id : s.idToProbe.keySet()) player.removeResourcePack(id);
        player.removeResourcePack(LB_ID);
    }

    private static long maskOf(Collection<Integer> set) {
        long m = 0;
        for (int i : set) m |= 1L << i;
        return m;
    }

    /** Cached subset seen on a returning client: compare against known accounts. */
    private void analyse(Player player, long mask, boolean lb) {
        String uuid = player.getUniqueId().toString();
        FoliaScheduler.runAsync(plugin, () -> {
            List<String> linked = findLinked(uuid, mask);
            results.put(player.getUniqueId(), new Result(Long.bitCount(mask), linked.size(), lb));
            plugin.getDatabaseManager().saveRPTrack(uuid, player.getName(), mask, lb);
            if (!linked.isEmpty() && alert)
                report(player, "same client cache as: " + String.join(", ", linked));
        });
    }

    /** Names of other accounts whose stored fingerprint overlaps this one by at least min-match packs. */
    public List<String> findLinked(String uuid, long mask) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : plugin.getDatabaseManager().getRPTracks()) {
            if (uuid.equals(row.get("uuid"))) continue;
            long other = ((Number) row.get("mask")).longValue();
            if (Long.bitCount(mask & other) >= Math.min(minMatch, Long.bitCount(other)))
                out.add(String.valueOf(row.get("name")));
        }
        return out;
    }

    public long getMask(String uuid) {
        for (Map<String, Object> row : plugin.getDatabaseManager().getRPTracks())
            if (uuid.equals(row.get("uuid"))) return ((Number) row.get("mask")).longValue();
        return 0;
    }

    private void report(Player player, String text) {
        String msg = "[RPTrack] " + player.getName() + ": " + text;
        plugin.getLogger().info(msg);
        if (!alert) return;
        FoliaScheduler.runGlobal(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.hasPermission("veneer.hacks.alerts") && plugin.hasAlertsEnabled(p.getUniqueId()))
                    p.sendMessage(msg);
        });
    }
}
