# Invalider

A single Paper/Spigot plugin merging two previously-independent plugins into one jar:

* **AntiSpoof** (`com.gigazelensky.antispoof.*`, by GigaZelensky) - client brand/channel
  spoof detection.
* **CheckHacks** (`me.branduzzo.checkHacks.*`, by Branduzzo) - sign-translation-vulnerability
  hack detection.

Both modules' original package trees, classes, and behavior were kept as close to
unchanged as possible. The only required change was replacing their two separate
`JavaPlugin` main classes with a single one (`com.invalider.InvaliderPlugin`), since
Bukkit only loads one main class per `plugin.yml`.

## Build

```
mvn package
```

Produces `target/invalider-<version>.jar`.

## What changed during the merge

- **One main class.** `AntiSpoofPlugin` and `CheckHacksPlugin` were removed; their
  `onEnable`/`onDisable` logic now lives in `InvaliderPlugin`, which both modules'
  managers/listeners/commands reference directly (constructor param types were
  updated from the old plugin classes to `InvaliderPlugin`).
- **One config file.** Everything lives in `plugins/Invalider/config.yml`: `antispoof:`
  (was AntiSpoof's config.yml) and `checkhacks:` with `settings` (was config.yml),
  `detection` (was checkhacks.yml) and `language-check` (was checklang.yml). Each
  module's `ConfigManager` reads its own section via `InvaliderConfig.section(...)`,
  not Bukkit's built-in `getConfig()`. Chat messages stay in `messages/<lang>.yml`.
  Old standalone config files are not migrated.
- **AntiSpoof -> CheckHacks.** A new AntiSpoof flag queues a CheckHacks scan
  (`antispoof.checkhacks-on-flag.enabled`). Anticheat hooks (Grim via its event bus,
  Vulcan, Spartan, Matrix, Intave, AAC) are in `checkhacks.detection.detect-flag`.
- **One accessor renamed.** Both modules had a `getConfigManager()` on their plugin
  class, returning incompatible types. AntiSpoof kept the name; CheckHacks's is now
  `getHackConfigManager()`. This only matters if you're writing code against the
  plugin instance directly (not via commands/config).
- **AntiSpoof's Lite build variant was dropped.** The original project shipped a
  "Full" (PacketEvents shaded in) and "Lite" (PacketEvents provided externally) jar.
  The merged build only produces the Full equivalent, since CheckHacks has no
  Lite-style profile and a single combined jar is simpler to install.
- **CheckHacks's self-updater is disabled.** `/checkhacksupdate` and the
  startup GitHub check are wired up but inert
  (`InvaliderPlugin.onEnable` never calls `updateManager.checkForUpdates()`).
  The original updater downloads a standalone `CheckHacks-X.Y.Z.jar` release and
  deletes the currently-running jar to replace it. In this merged build the running
  jar is `invalider-*.jar` (AntiSpoof + CheckHacks); letting that logic run would
  delete the merged plugin and replace it with a CheckHacks-only jar missing all of
  AntiSpoof. Update by building/downloading a new Invalider release manually.
- **Version reset to `1.0.0`**, independent of the original AntiSpoof (1.2.7) and
  CheckHacks (1.3.1) version numbers, since they no longer version independently.
- **`api-version` set to `1.21`, Paper API 1.21.4 used for compilation**, matching
  CheckHacks's requirement (Folia support, modern sign APIs). AntiSpoof's original
  claimed range was 1.8.9-1.21.5 against the old Spigot API; its code compiles fine
  against the newer Paper API, so no functional loss is expected, but it hasn't been
  tested against legacy 1.8.x servers as part of this merge.

## License

GPLv3 for the combined work (required by AntiSpoof's original GPLv3 license when
combined with other code) - see `LICENSE`. CheckHacks's original MIT text is kept
in `LICENSE-MIT` for attribution.

## Original projects

The original standalone projects, with their own git history, are kept untouched
in `AntiSpoof/` and `CheckHacks/` for reference. They are not part of the build.
