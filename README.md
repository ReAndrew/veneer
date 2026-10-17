<div align="center">

# Veneer

**Catch clients that aren't what they claim to be.**

Client spoof detection, mod detection and cache-based account linking for Paper, Spigot and Folia, in a single jar.

![Java 21](https://img.shields.io/badge/Java-21-orange)
![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-blue)
![Folia](https://img.shields.io/badge/Folia-experimental-yellow)
![License](https://img.shields.io/badge/license-GPLv3-lightgrey)

</div>

> **Status:** the plugin compiles and packages; it has **not yet been run on a live server**. Treat it as a pre-release.

---

## Features

| Module | What it does |
|---|---|
| **Spoof detection** (from AntiSpoof) | Compares the client brand and registered plugin channels against what the claimed client can actually send. Catches fake `vanilla`, fake Fabric/Forge/Lunar, blocked or required channels, Geyser impersonation. Uses PacketEvents and the `minecraft:brand` channel. |
| **Mod detection** (from CheckHacks) | Finds installed mods through the sign translation vulnerability (MC-265322). Hacks are defined in config, results are stored in SQLite, and there is an embedded web editor. |
| **Anticheat bridge** | Flags from Grim (event bus), Vulcan, Spartan, Matrix, Intave and AAC can trigger a mod scan. A spoof flag can also queue one. |
| **RPTrack** | Links accounts and IPs of the same Minecraft installation through its resource-pack download cache, and detects LiquidBounce's local server. **Off by default.** See [RPTrack](#rptrack). |
| **Integrations** | LuckPerms (live alert-recipient refresh, primary group), PlaceholderAPI (`%veneer_*%`), Floodgate/Geyser, Discord webhooks, Folia-aware scheduling. |

## Install

1. Drop `veneer-<version>.jar` into `plugins/` and start the server.
2. Edit `plugins/Veneer/config.yml`, then reload with `/spoof reload` or `/hackreload`.
3. Optional: install LuckPerms, PlaceholderAPI, Floodgate, ViaVersion or an anticheat. Veneer picks them up automatically.

Upgrading from the earlier builds (**Invalider**, **Anomaly**)? On first start the contents of `plugins/Anomaly` or `plugins/Invalider` are copied into `plugins/Veneer` if it has no `config.yml` yet. The old folder is left untouched. Permissions changed prefix, so re-grant them as `veneer.*`.

## Commands

| Command | Aliases | Permission | Description |
|---|---|---|---|
| `/spoof` |  `/antispoof` | `veneer.spoof.use` | Inspect client brand and channels, manage spoof detection |
| `/hackcheck <player> [hacks]` | `/checkhacks` | `veneer.hacks.check` | Scan a player for mods |
| `/hackalerts` | `/chalerts` | `veneer.hacks.alerts` | Toggle your detection alerts |
| `/hacklang <player>` | `/checklang` | `veneer.hacks.lang` | Check a player's client language |
| `/hackeditor` | `/cheditor` | `veneer.hacks.editor` | Open the web editor for hack definitions |
| `/hackreload` | `/chreload` | `veneer.hacks.reload` | Reload hack detection config |
| `/rptrack <player>` | `/hackfp` | `veneer.hacks.rptrack` | Show a player's cache fingerprint and linked accounts |

Other permissions: `veneer.spoof.alerts`, `veneer.spoof.admin`, `veneer.spoof.bypass`. Wildcards: `veneer.spoof.*`, `veneer.hacks.*`, `veneer.*`.

## Configuration

One file, `plugins/Veneer/config.yml`:

```
antispoof:            # spoof detection (brand, channels, punishments, Discord)
checkhacks:
  settings:           # prefix, language, web editor, Discord, Bedrock handling
  detection:          # hack definitions, auto-checks, anticheat hooks, commands
  language-check:     # /hacklang
  rptrack:            # resource-pack cache tracking (disabled by default)
```

Chat messages live in `plugins/Veneer/messages/<lang>.yml` (en, ru, de, es, fr, it, br, lolcat, uwu).

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

The older `%antispoof_*%` placeholders still work.

## RPTrack

The Minecraft client caches downloaded resource packs by SHA-1 in `.minecraft/downloads`, shared across accounts. On join, Veneer offers 28 probe packs with a known hash and an unreachable URL; a pack answers *loaded* only if it is already cached. A fresh client is assigned a random subset of 6 packs that it really downloads, and later logins on any account or IP reproduce that subset. The technique is described in [this write-up](https://alaggydev.github.io/posts/cytooxien/).

A built-in HTTP server serves the probe packs and a small pack that hides the "failed to download" toast. Set it up in `checkhacks.rptrack`:

```yaml
enabled: true
port: 8123
public-url: "http://play.example.com:8123"   # must be reachable by players
min-match: 5                                 # packs that must overlap to link two accounts
```

> **Privacy:** this is covert tracking of players. Disclose it in your server rules or privacy policy. Some clients (Meteor, Wurst, LiquidBounce, ExploitPreventer) already block it, so absence of a match proves nothing.

## Build

```
mvn -q -DskipTests package
```

Produces `target/veneer-<version>.jar` (about 19 MB, PacketEvents and bStats shaded and relocated). Needs Java 21 or newer.

## Notes

- The self-updater inherited from CheckHacks is **disabled**: it would delete the running merged jar. Update manually.
- Version `1.0.0` is independent of the originals (AntiSpoof 1.2.7, CheckHacks 1.3.1).
- **Folia:** every task goes through `FoliaScheduler` (global, per-entity and async schedulers; Bukkit's scheduler is no longer used anywhere), and punishments run as console commands on the global region. This is **experimental**: it compiles but has not been tested on a real Folia server.
- Compiled against Paper API 1.21.4, `api-version: 1.21`. Legacy 1.8.x servers are untested.

## Credits and license

Veneer merges two open-source plugins and adds new modules:

- **AntiSpoof** by GigaZelensky (GPLv3)
- **CheckHacks** by Branduzzo (MIT)

The combined work is released under **GPLv3**, see `LICENSE`; the CheckHacks MIT text is appended there for attribution.
