<div align="center">

# Veneer

**Catch clients that aren't what they claim to be.**

Client spoof detection, mod detection and cache-based account linking for Paper, Spigot and Folia, in a single jar.

![Java 21](https://img.shields.io/badge/Java-21-orange)
![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-blue)
![Folia](https://img.shields.io/badge/Folia-1.21.11_tested-green)
![License](https://img.shields.io/badge/license-GPLv3-lightgrey)

</div>

> **Status:** beta. Tested on Paper/Purpur and Folia 1.21.11 with test clients and real clients (including ViaFabricPlus and ViaProxy), not yet on a busy public server.

---

## Features

| Module | What it does |
|---|---|
| **Spoof detection** (from AntiSpoof) | Compares the client brand and registered plugin channels against what the claimed client can actually send. Catches fake `vanilla`, fake Fabric/Forge/Lunar, blocked or required channels, Geyser impersonation. Uses PacketEvents and the `minecraft:brand` channel. |
| **Mod detection** (from CheckHacks) | Finds installed mods through the sign translation vulnerability (MC-265322). Hacks are defined in config, results are stored in SQLite. Clients older than `hacks.detection.min-client-version` (default 1.17) are not scanned: they do not answer the probe sign. |
| **Language and version check** | `/hacklang` reads the client language, and from the same sign the client's real game version (keys Mojang removed or added in 1.20.5 and 1.21.2). A version that does not match the protocol the player connects with points to ViaFabricPlus, ViaProxy and the like; an alert and an optional command follow. |
| **Web panel** | Scan history and manual scans, a list of every mod detected, and a 3D graph of linked accounts (shared addresses, RPTrack fingerprints and the same client brand and channels) with player heads, filters by link type, strength and last seen, and search by name or IP. Each address shows its provider, AS number and country; a shared address of a VPN, Cloudflare WARP or hosting provider counts as a weaker link. The panel follows `general.language` (English and Russian). |
| **Anticheat bridge** | Flags from Grim (event bus), Vulcan, Spartan, Matrix, Intave and AAC can trigger a mod scan. A spoof flag can also queue one. |
| **RPTrack** | Links accounts and IPs of the same Minecraft installation through its resource-pack download cache, and detects LiquidBounce's local server. **Off by default.** See [RPTrack](#rptrack). |
| **Integrations** | LuckPerms (primary group), PlaceholderAPI (`%veneer_*%`), Floodgate/Geyser, Discord webhooks, Folia-aware scheduling. |

## Install

1. Drop `veneer-<version>.jar` into `plugins/` and start the server.
2. Edit `plugins/Veneer/config.yml`, then apply it with `/veneer reload`. Requires Paper, Spigot or Folia 1.21+ on Java 21.
3. Optional: install LuckPerms, PlaceholderAPI, Floodgate/Geyser, ViaVersion or an anticheat. Veneer picks them up automatically.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/veneer info <player>` | `veneer.admin` | One report per player: client brand and channels, spoof status, latest mod scan, RPTrack link |
| `/veneer alerts` | `veneer.alerts` | Toggle your own staff alerts (spoof, mod scan and RPTrack share one switch) |
| `/veneer reload` | `veneer.admin` | Reload `config.yml` and the language files for every module |
| `/veneer version` | `veneer.admin` | Running and latest release |
| `/spoof` | `veneer.spoof.use` | Inspect client brand and channels, re-run spoof checks (`check`, `runcheck`, `channels`, `brand`) |
| `/hackcheck <player> [hacks]` | `veneer.hacks.check` | Scan a player for mods |
| `/hacklang <player>` | `veneer.hacks.lang` | Check a player's client language |
| `/hackeditor` | `veneer.hacks.editor` | Link to the web panel: scan history and manual scans |
| `/rptrack <player>` | `veneer.rptrack` | Show a player's cache fingerprint and linked accounts |

Other permissions: `veneer.spoof.admin`. Bypass (all default `false`, not in `veneer.*`): `veneer.bypass` (everything), `veneer.bypass.<spoof|hacks|rptrack>` (that module, join and later), `veneer.bypass.<type>.join` (join-time check only). Wildcards: `veneer.spoof.*`, `veneer.hacks.*`, `veneer.web.*`, `veneer.*`.

Web panel rights (default op) are taken from the player when `/hackeditor` issues the token: `veneer.web.scan` (run scans), `veneer.web.delete` (delete scans), `veneer.web.links` (account graph), `veneer.web.ips` (see IP addresses; without it the graph hides them). Without any of them the token still shows the scan history.

## Configuration

One file, `plugins/Veneer/config.yml`, one section per part:

```
general:    # language, prefix, debug, alerts, Bedrock detection, shared Discord webhook, update checker
spoof:      # brand/channel rules, punishments, Discord alerts            (enabled: true)
hacks:      # hack definitions, auto-checks, anticheat hooks, web panel   (enabled: true)
rptrack:    # resource-pack cache tracking                                (enabled: false)
```

- Settings that more than one module needs live in `general` once: the Bedrock rules, the staff alert switch, the debug flag and a default Discord webhook (a module's own URL wins when set).
- Settings missing from your file are added from the defaults on start and on reload, comments included. Unknown settings are reported in the console.
- Turning a module on or off needs a restart; everything else applies with `/veneer reload`.
- The mod scan messages live in `plugins/Veneer/messages/<lang>.yml` (en, ru, de, es, fr, it, br, lolcat, uwu).

## PlaceholderAPI

| Placeholder | Value |
|---|---|
| `%veneer_brand%` | Client brand |
| `%veneer_channels%`, `%veneer_channels_count%` | Registered plugin channels |
| `%veneer_is_spoofing%`, `%veneer_is_bedrock%` | `true` / `false` |
| `%veneer_client_type%` | Client type seen by the mod scan |
| `%veneer_alerts%` | `on` / `off` for the viewer |
| `%veneer_group%` | LuckPerms primary group |
| `%veneer_rptrack_packs%`, `%veneer_rptrack_linked%`, `%veneer_rptrack_liquidbounce%` | RPTrack result for the player |

## RPTrack

The Minecraft client caches downloaded resource packs by SHA-1 in `.minecraft/downloads`, shared across accounts. On join, Veneer offers 28 probe packs with a known hash and an unreachable URL; a pack answers *loaded* only if it is already cached. A fresh client is assigned a random subset of 6 packs that it really downloads, and later logins on any account or IP reproduce that subset. The technique is described in [this write-up](https://alaggydev.github.io/posts/cytooxien/).

Probe packs and a small pack that hides the "failed to download" toast must be downloadable by clients. Point `public-url` at `localhost` (e.g. `http://localhost:8123`) and Veneer serves them itself on that port, sending clients to the host they joined with. With any other URL no server is started: host `plugins/Veneer/rptrack/fp/` (exported on start) at `<public-url>/fp/`. Set it up in `rptrack`:

```yaml
enabled: true
public-url: "http://localhost:8123"   # localhost = built-in server on this port
track: 28                             # probe packs checked on every join
send: 6                               # packs a fresh client is sent (<= track)
match: 5                              # cached packs that must overlap to link accounts (<= send)
debug: false                          # log every step to check how stable the signal is
```

> **Privacy:** this is covert tracking of players. Disclose it in your server rules or privacy policy. Some clients (Meteor, Wurst, LiquidBounce, ExploitPreventer) already block it, so absence of a match proves nothing.

## Build

```
mvn -q -DskipTests package
```

Produces `target/veneer-<version>.jar` (about 24 MB, PacketEvents shaded and relocated). Needs Java 21 or newer.

## Notes

- The web panel (`/hackeditor`) listens on `127.0.0.1` by default; set `hacks.web-editor.bind` to `0.0.0.0` (and put it behind HTTPS) to reach it from elsewhere. Access needs the one-time token from the command. Join addresses and skins are kept for `hacks.web-editor.links.retention-days` (90) after the player was last seen, scans for `hacks.web-editor.history.retention-days` (180).
- The provider and country data (about 50 MB) is downloaded to `plugins/Veneer/geo/` through jsDelivr and refreshed weekly (`hacks.web-editor.links.ip-database`, on by default; the plugin works without it). The country is where the network is registered, not a precise location. Which providers count as weak is set by `weak-provider-asns` and `weak-provider-keywords`, the weight factor by `weak-provider-factor` (0.3). Client links need the spoof module and are recorded when a player leaves.
- To translate the panel, add `src/main/resources/web/lang/<language>.json` (English text as keys, see `ru.json`).
- Version `0.9.3` is independent of the originals (AntiSpoof 1.2.7, CheckHacks 1.3.1).
- **Folia:** every task goes through `FoliaScheduler` (global, per-entity and async schedulers; Bukkit's scheduler is no longer used anywhere), and punishments run as console commands on the global region. Tested on Folia 1.21.11 (mod scan, language check, web panel scans, death, world change and quitting mid-scan). A probe sign Folia does not let the plugin remove on shutdown is removed on the next start.
- Compiled against Paper API 1.21.4, `api-version: 1.21`. Legacy 1.8.x servers are untested.

## Credits and license

Veneer merges two open-source plugins and adds new modules:

- **AntiSpoof** by GigaZelensky (GPLv3)
- **CheckHacks** by Branduzzo (MIT)

The web panel bundles [3d-force-graph](https://github.com/vasturiano/3d-force-graph) and [three.js](https://github.com/mrdoob/three.js) (both MIT, texts in `src/main/resources/web/THIRD-PARTY-LICENSES.txt`). IP provider data: [IPtoASN](https://iptoasn.com) (PDDL); country data: [geo-whois-asn-country](https://github.com/sapics/ip-location-db) (CC BY 4.0, from NRO), both via ip-location-db.

The combined work is released under **GPLv3**, see `LICENSE`; the CheckHacks MIT text is appended there for attribution.
