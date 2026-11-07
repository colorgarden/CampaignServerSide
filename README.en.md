# Campaign Server Side

[简体中文](README.md) | **English**

> [!WARNING]
> This mod is at a **very early testing stage** and certainly has bugs nobody has found yet.
> If you hit one, please open an issue or send a PR — vibe-coded PRs are welcome too.
>
> This project **contains AI-generated content**.

A Java plugin for the Mindustry **dedicated server** that makes **campaign mode** playable on a server,
plus a network API for client mods.

- Generates and loads campaign sectors on the server, saving progress the way single-player does
- Admins can switch sectors and unlock research; the "admin only" switches can be turned off so everyone can
- Admin panel `/ca menu`: research, sector switch, planet switch, pause, wave skip, difficulty, campaign rules, abandon
- Eight vote kinds (sector / research / planet / destination / redirect / pause / wave / abandon), all with a confirmation page
- **Launch setup UI**: core blueprint library, cargo, capacity checks, cargo deducted from the origin sector
- Periodic autosave (follows vanilla `saveinterval`), save on shutdown, save-on-loss decided by the campaign rule
- AFK detection: players idle longer than `afkSeconds` are skipped in votes, with chat announcements both ways
- Custom packets + a Java API for client mods (see [`PROTOCOL.md`](PROTOCOL.md))
- Every string lives in the language files and follows the client locale
  (`bundles/bundle.properties` + `bundle_zh_CN.properties`)

Version: **0.0.2-alpha** ([release](https://github.com/colorgarden/CampaignServerSide/releases/tag/v0.0.2-alpha)).
Target: Mindustry **v160.x** dedicated server (`server-release.jar`), JDK 17+.
License: **AGPL-3.0-or-later** (see [`LICENSE`](LICENSE)).
Repository: `https://github.com/colorgarden/CampaignServerSide`

> [!TIP]
> **Test server: `colorgarden.top`** (port `6567`, join straight from a v160 client)
> **QQ group: `1135085472`** (bug reports, finding people to play with, release news)

---

## 1. Installation

1. Build `CampaignServerSide.jar` (see section 10) or use `build/CampaignServerSide.jar`.
2. Drop it into the server's `config/mods/`:
   ```
   <server>/
     server-release.jar
     config/mods/CampaignServerSide.jar
   ```
3. Start the server: `java -jar server-release.jar`
4. In the console run `campaign-help`, or go straight to `campaign-host groundZero`.
5. To resume the last sector on boot, set `"autoStart"` to `true` in the config file.

> The campaign maps (`maps/serpulo/*.msav`, `maps/erekir/*.msav`) are already inside `server-release.jar`;
> no extra `assets.jar` is needed. `campaign-diag` prints something like
> `Sector preset maps available: 78/78` — anything lower means the server jar is incomplete.

## 2. Console commands

| Command | Description |
| --- | --- |
| `campaign-help` | List every command |
| `campaign-host [sector]` | Enter the campaign (last sector or the starting one) |
| `campaign-goto <sector>` | Switch sector (saves the current one first) |
| `campaign-reset <sector>` | Delete a sector save and regenerate it |
| `campaign-save` | Save the current sector now |
| `campaign-stop` | Save and stop hosting |
| `campaign-status` | Current sector, wave, cores, save file, … |
| `campaign-sectors [page]` | List every sector of the planet and its state |
| `campaign-capture` | Force the current sector to count as captured (testing) |
| `campaign-research <name>` | Unlock one item (including its prerequisites) |
| `campaign-research-all` | Unlock everything |
| `campaign-relock <name>` | Lock an item again |
| `campaign-rule [name] [on\|off]` | Show or toggle campaign rules (`invasion` `fog` `hidespawns` `randomwaveai` `rtsai` `pausedisabled` `clearonlose`) |
| `campaign-difficulty [name]` | Show or set the campaign difficulty |
| `campaign-sim [pause\|resume]` | Pause or resume the simulation directly |
| `campaign-skipwave` | Skip the next wave (same as the vanilla button) |
| `campaign-autopause [on\|off]` | Show or set vanilla `autoPause` |
| `campaign-cores` | List every core block, its size and the blueprint footprint it allows |
| `campaign-fix-spawn [x] [y]` | Repair a corrupted spawn position of the current sector |
| `campaign-votelist [kind] [--all]` | List the targets a vote could use right now |
| `campaign-vote [info\|cancel]` | Show or cancel the running vote |
| `campaign-destination [sector\|clear]` | Show or set the launch pad target (same planet only) |
| `campaign-redirect` | Point every exporting sector at the current one (the vanilla ↓ button) |
| `campaign-why <sector>` | Explain whether a sector can be entered and why not |
| `campaign-joininfo` | Preview the message players see on join |
| `campaign-logic [on\|off\|probe]` | World processor state, keep-running toggle, accumulator probe |
| `campaign-pause [on\|off]` | Pause the simulation while nobody is online |
| `campaign-planets` | List participating planets and capture progress |
| `campaign-planet [name]` | Show or switch planet |
| `campaign-uncapture <sector>` | Clear the captured flag of a sector |
| `campaign-config` | Print the current config and its path |
| `campaign-reload` | Re-read `config.json` |
| `campaign-diag` | Diagnostics (save manager, map availability, API state, snapshot round trip) |

Sector syntax: preset name `groundZero`, ID `170`, `id:170`, cross-planet `erekir:onset`.

## 3. In-game commands

Prefix `/`, visible in `/help`.

| Command | Permission | Description |
| --- | --- | --- |
| `/sector <name\|id>` | per config | Switch sector (online players reload the world) |
| `/sectors [page]` | everyone | Sector list and state |
| `/campaign` | everyone | Current sector, wave, research left |
| `/research <name\|all>` | per config | Unlock research, synced to online players |
| `/campaignsave` | admin | Save the current sector |
| `/capture` | admin | Force capture |
| `/ca menu` | admin | Admin panel (research / sector / planet / pause / wave / difficulty / rules / abandon) |
| `/cadmin menu` | admin | Alias of `/ca` |
| `/ca research <name>` | admin | Unlock research directly |
| `/ca sector <name>` | admin | Switch sector directly |
| `/ca planet <name>` | admin | Switch planet directly |
| `/ca rule <name> [on\|off]` | admin | Toggle a campaign rule |
| `/cj` `/cjump` | admin | Skip one wave (limited by `skipWaveCooldown`) |
| `/cv menu` `/cvote menu` | everyone | Vote panel |
| `/cvote sector <name>` | everyone | Start a sector-switch vote |
| `/cvote research <name>` | everyone | Start a research vote |
| `/cvote planet <name>` | everyone | Start a planet-switch vote |
| `/cvote destination <sector>` | everyone | Start a launch-pad target vote |
| `/cvote redirect` | everyone | Start a planet-wide redirect vote |
| `/cvote pause` | everyone | Start a pause/resume vote |
| `/cvote wave` | everyone | Start a wave-skip vote |
| `/cvote abandon` | everyone | Start an abandon vote (surrender) |
| `/cvote yes` / `/cvote no` | everyone | Cast a vote |
| `/cvote cancel` | admin | Cancel the running vote |
| `/cvote info` | everyone | Current vote state |
| `/cvote help` | everyone | List every vote command |
| `/destination <name\|clear>` | admin | Set this sector's launch pad target |
| `/redirect` | admin | Redirect every export to this sector |
| `/planets` | everyone | List participating planets and progress |
| `/planet <name>` | per config | Switch planet |

"per config" means `sectorSwitchAdminOnly` / `researchAdminOnly` / `planetSwitchAdminOnly`:
when `true`, only admins may use the command directly.

## 4. Voting

Eight kinds: `sector`, `planet`, `research`, `destination`, `redirect`, `pause`, `wave`, `abandon`.

- Every vote has a **confirmation page**: pick a target from the list → confirm → the vote starts.
  If the target sector has no base yet, the launch setup opens after confirming and only "Launch" starts the vote.
- Lists hold 6 rows per page (research is two columns, so 12 entries), with search and a "show all" toggle.
  Locked sectors carry their missing prerequisite in the list; clicking them only prints the reason.
  An empty list shows a placeholder page instead of closing the menu.
- The decision mode comes from `voteDecision`, **`majority` by default**:
  - `majority`: decided as soon as everyone voted, or on timeout with the current tally — yes > no passes,
    a tie fails. A minority cannot veto.
  - `threshold`: passes at yes ≥ `ceil(online × votePercent)`; fails when no reaches the same number.
  - `veto`: one no kills it immediately; yes still has to reach the threshold.
- Three timers, constrained to `timeout < lead < window`:
  - `voteDuration`: total window, the hard limit
  - `votePushSeconds`: how many seconds before the end the ballot pops up
  - `voteTimeout`: seconds of silence after the popup before it settles, `0` disables
- **AFK players** (no movement for `afkSeconds`) do not count towards the quorum and cannot start a vote;
  becoming AFK and coming back are both announced in chat. A paused simulation does not accumulate idle time.
- Admins can cancel at any time; starting twice in a row waits `voteCooldown` seconds.
- Fewer than `voteMinPlayers` online means no vote. Set it to `1` to allow single-player votes —
  the starter's ballot is then the whole tally.
- Results apply immediately (a sector vote reloads the world) and are broadcast to chat.

## 4.1 Launch setup and the core blueprint library

Entering a sector that has no base opens the launch setup (the `/ca` direct entry goes through it too):

- **Blueprint library**: `.msch` files in `config/mods/Campaign Server Side/blueprints/` plus generated
  single-core presets for the current planet
- **Upload**: paste the base64 the game exports; validated for decoding, non-empty, exactly one core,
  every block unlocked, and a footprint of at most `core size + 10`
  (vanilla `maxLoadoutSchematicPad = 5`, so five tiles per side)
- **Rename / delete**: only uploaded blueprints; the built-in core presets are read-only.
  File names are ASCII slugs, the readable name lives in a sidecar `.name` file
- **Cargo**: ±50 per item, max, max-all; per item the limit is `min(origin stock − blueprint demand, capacity)`
  with `capacity = launchCapacityMultiplier × core.itemCapacity`; launching is refused when the origin cannot pay
- Cancelling clears this launch's cargo; a completed launch deducts it from the origin sector

## 5. Configuration

Path: `<server>/config/mods/Campaign Server Side/config.json` (created on first start, **every key is written out**,
so edit it directly). At runtime the plugin only touches `planet`/`sector` and merges your other values back;
run `campaign-reload` afterwards.

| Key | Default | Description |
| --- | --- | --- |
| `planet` | `"serpulo"` | Campaign planet, updated on sector switch |
| `sector` | `-1` | Last sector ID, `-1` picks automatically |
| `language` | `"auto"` | Console/bundle language: `auto` (JVM locale) / `en` / `zh_CN` |
| `autoStart` | `false` | Enter the last sector on boot |
| `autosave` | `true` | Periodic autosave |
| `autosaveSeconds` | `0` | Autosave interval; `0` follows vanilla `saveinterval` (60 s), a positive value overrides it |
| `saveOnExit` | `true` | Save on shutdown |
| `paused` | `false` | Whether the game was deliberately paused; restored after a restart |
| `clearSectorOnLose` | `false` | Clear-on-loss: `true` = not saved on loss and regenerated next time; `false` = ruined state saved and rebuilt vanilla-style |
| `blueprintOnRebuild` | `true` | Apply the selected core blueprint after a lost-sector rebuild (vanilla only places the default core) |
| `afkEnabled` | `true` | Enable AFK detection |
| `afkSeconds` | `60` | Seconds without movement before a player counts as AFK |
| `skipWaveCooldown` | `30` | `/cj` cooldown in seconds |
| `allowSectorSwitch` | `true` | Master switch for sector switching |
| `allowResearch` | `true` | Master switch for research |
| `sectorSwitchAdminOnly` | `true` | Only admins may use `/sector` (votes unaffected) |
| `researchAdminOnly` | `true` | Only admins may use `/research` (votes unaffected) |
| `voting` | `true` | Master switch for voting |
| `voteSector` | `true` | Allow sector votes |
| `voteResearch` | `true` | Allow research votes |
| `voteDestination` | `true` | Allow launch-pad target votes |
| `voteRedirect` | `true` | Allow redirect votes |
| `votePercent` | `0.6` | Passing ratio in `threshold` mode |
| `voteMinPlayers` | `2` | Minimum online players to start a vote (`1` allows solo votes) |
| `voteDuration` | `60` | Total vote window in seconds, the hard limit |
| `votePushSeconds` | `15` | Seconds before the end when the ballot pops up |
| `voteDecision` | `"majority"` | Decision mode: `majority` / `threshold` / `veto` |
| `voteTimeout` | `10` | Seconds of silence after the popup before settling, `0` disables |
| `allowPlanetSwitch` | `true` | Master switch for planet switching |
| `planetSwitchAdminOnly` | `true` | Only admins may use `/planet` (votes unaffected) |
| `votePlanet` | `true` | Allow planet votes |
| `voteCooldown` | `30` | Cooldown between two votes of the same player, seconds |
| `announce` | `true` | Announce captures and losses |
| `resyncClients` | `true` | Resend world data after a sector switch (otherwise clients get kicked) |
| `enforceSectorUnlock` | `true` | Restrict entry to unlocked sectors like vanilla |
| `adminBypassLock` | `true` | Admins ignore the unlock restriction (the console always can) |
| `launchPadLinking` | `true` | Point a new sector's pads at its origin |
| `joinInfo` | `true` | Send a short help message on join |
| `worldProcessors` | `true` | Keep world processors running until the sector is captured |
| `pauseWhenEmpty` | `true` | Pause while nobody is online |

Saves: `config/saves/sector-<planet>-<id>.msav`; per-sector economy lives in `config/settings.bin`
(key `<planet>-s-<id>-info`). To wipe a sector completely use `campaign-reset <sector>`.

## 6. Network API (client mods)

The server advertises the protocol version through `state.rules.tags["campaign-api"]`, so a client mod can
check availability; after the handshake it can switch sectors, unlock research, save, vote and read sector info.
Packet layout, JSON fields and a Java example are in [`PROTOCOL.md`](PROTOCOL.md).

## 7. How close this is to vanilla

**Sector unlocking** (`enforceSectorUnlock`, on by default) mirrors `PlanetDialog#canSelect`:

1. shielded sectors cannot be entered (`Sector#isShielded`);
2. sectors with a base and the planet's starting sector are always available;
3. preset sectors follow the tech tree: `preset.unlocked()`, or the parent unlocked and (if the parent is a
   sector) that sector has a base;
4. numbered sectors follow `PlanetGenerator#allowLanding` (Serpulo: a neighbour has a base and the core is level 4+).

Console commands always force a switch (with a warning); in-game admins bypass the check when `adminBypassLock`
is on; votes may never target a locked sector. `campaign-why <sector>` explains any decision.

**Automatic research**: vanilla `Control#checkAutoUnlocks` only runs on clients. The plugin runs the same check
after a capture, after entering a sector and after a manual unlock: content whose parent is unlocked, needs no
items and has all objectives completed gets unlocked. That is the "capture a sector → unlock the next ones" chain.

**Launch pads / receivers**: the vanilla target is set from a client UI (`LaunchPad#buildConfiguration` is
disabled in multiplayer), so the plugin takes over: a new sector points `origin`/`destination` at its source
(`launchPadLinking`, the same meaning as launching into a new sector), `campaign-destination` / `/destination`
set it manually, `campaign-redirect` / `/redirect` redirect everything at once, and targets must be on the same
planet (as in vanilla). Serpulo pads settle transport through `Universe#runTurn` every turn, Erekir receivers pull
by import rate; both rely on `destination` and the `export` counters set up here.

**World processors**: vanilla `Logic#sectorCapture()` sets `state.rules.disableWorldProcessors = true`, and
`LogicBlock#updateTile` returns immediately once that flag is set. `Rules` are written into the sector save via
`JsonIO`, so **once a sector has been captured its world processors never run again** — vanilla does not care
because captured sectors are not revisited, but a dedicated server keeps re-entering them. The plugin clears the
flag on `WorldLoadEvent` / `RulesLoadEvent` and after every capture (`worldProcessors`), and **never touches**
`allowEditWorldProcessors`, which only controls building/editing.

**Clear on loss (`clearSectorOnLose`)** follows `Control#playSector`:

- **on**: not saved on loss, the sector save is deleted, the next entry regenerates it (no leftovers)
- **off**: the ruined state is saved; the next entry rebuilds it the vanilla way — load the old save →
  place a core at `spawnPosition` → `SectorDamage.apply(1f)` along enemy paths → record surviving buildings /
  derelicts / plans → `logic.reset()` → regenerate → restore the survivors as `derelict` inside
  `playNewSector`'s `beforePlay` → `logic.play()` → finally apply the core blueprint if `blueprintOnRebuild`

Rotated backups (`*.msav-backup.msav`) are only used when a save **fails to load**; they never replace the
lost-sector flow.

**Launch loadout**: vanilla `LaunchLoadoutDialog` is client-only; the launch setup UI replaces it and places
blocks with vanilla `Schematics#placeLoadout`, with the same capacity and item deduction rules.

**Not reproduced**: the cross-sector launch animation and sounds (client-only polish); blueprints and cargo are
global state rather than per-launch.

## 8. Why vanilla cannot do this

1. `Vars.control` is `null` on a headless server, and sector save management (`Saves#saveSector`,
   `Saves$SaveSlot`) hangs off it → the plugin builds its own `Saves`; `Core.assets` is empty in headless mode,
   so it temporarily supplies an `AssetManager` while constructing and restores it afterwards.
2. The save call in `Logic#sectorCapture()` is guarded by `if(!headless && !net.client())` → the plugin listens
   to `SectorCaptureEvent` and saves itself.
3. `ServerControl.gameOverListener` switches to the next map, which destroys the campaign → the listener is
   replaced via reflection (the `server` module is not available at mod compile time); a campaign game over
   becomes a pause plus an announcement, waiting for an admin.
4. Entering a sector copies the server-compatible subset of `Control#playSector`: load with
   `SaveIO.load(world.makeSectorContext(sector))` when a save exists, otherwise `world.loadSector` +
   `logic.play()`, then `SaveIO.save`; a sector whose cores are gone is rebuilt at the old position.
5. Switching the world for online players reuses the vanilla `/sync` path: `Call.worldDataBegin(con)` +
   `NetServer#sendWorldData`.
6. Research uses the vanilla path: `unlock()` → `UnlockEvent` → `Logic` sends `Call.researched`; joining players
   get `rules.researched` through `NetworkIO#writeWorld`.

## 9. Code layout

```
src/main/java/top/colorgarden/
├── CampaignServerMod.java     entry point (Plugin), shared by client and server
├── command/                   CampaignCommands / AdminPanel / LaunchPanel
├── config/                    CampaignConfig
├── core/                      SectorService / CoreBlueprints / LaunchCargo / ResearchService /
│                              CampaignHooks / ActivityTracker / ServerControlHook
├── net/                       CampaignNetwork / CampaignClientApi / CampaignInfo / CampaignProtocol
│   └── packet/                Hello / HelloAck / Request / Reply
├── vote/                      CampaignVote / VoteMenu
└── util/                      I18n / CampaignException
bundles/                       bundle.properties (English fallback) + bundle_zh_CN.properties
```

## 10. Building

**Gradle** (needs network access for JitPack)

```bash
gradle jar                 # build/libs/CampaignServerSide.jar
gradle installToServer -PserverDir=/path/to/server
```

**JDK only (no Gradle)**

```powershell
./build.ps1 -MindustryJar /path/to/server-release.jar
```

`build.ps1` compiles with `javac --release 17` and packs `mod.hjson` plus `bundles/` into the jar root.
`mod.hjson` must not start with a UTF-8 BOM, otherwise the game silently skips the mod (the script guards this).

## 11. Known limitations

- There is no planet map on the client: move between sectors with `/cv menu`, `/ca menu` or the commands.
- The client's `LaunchPad`/`Accelerator` launch flow depends on single-player saves; do not rely on it.
- **Blueprints and cargo are global state**, not carried per launch; two players preparing a launch overwrite
  each other.
- A lost-sector rebuild applies the *currently selected* blueprint, not the one used to launch that sector.
- Sector economy (`Universe#runTurn`, every 2 minutes) runs on the server and only links sectors that have bases.
- After a loss the server pauses and announces; an admin decides what happens next
  (`/ca sector`, `/ca rule clearonlose`, …).
- The server does not verify that a player has unlocked a block (vanilla multiplayer does not either); combine it
  with a whitelist-style plugin if you need that.
- Logs are UTF-8 by default; if the machine has an empty `LANG`, start with `-Dfile.encoding=UTF-8`
  `-Dstdout.encoding=UTF-8`.
- Blueprint file names go through `sun.jnu.encoding`, so non-UTF-8 locales turn Chinese into `?`; hence the ASCII
  slug plus sidecar name.

## 12. Smoke test checklist

```text
campaign-diag                      # save manager ok, 78/78 maps, API advertised: true, snapshot round trip ok
campaign-host groundZero           # generates a new sector: "Started new sector"
campaign-status                    # hasBase: true, save exists: true
runwave runwave campaign-save      # reach wave 3 and write the save
campaign-goto facility32m          # switch to another sector
campaign-goto groundZero           # switch back, wave is still 3
campaign-cores                     # list cores and allowed blueprint sizes (3/4/5/6 -> 13/14/15/16)
campaign-rule clearonlose on|off   # toggle clear-on-loss and watch the save behaviour
campaign-votelist sector --all     # locked sectors must show a red prerequisite
campaign-skipwave                  # skip one wave, cooldown applies
campaign-capture                   # "captured (saved: true)", waves becomes false
gameover                           # "Sector ... lost.", pauses instead of switching maps
campaign-research conveyor         # unlock one item
campaign-vote info                 # "no vote running" when idle
campaign-goto erekir:onset         # cross planet
```

## 13. Test server

**Address: `colorgarden.top`** (port `6567`, join from a v160 client).
**QQ group: `1135085472`**

The repository only ships the mod source, the language files and the build scripts; the operations scripts
(start/stop, backups, port mapping) are deliberately kept out of it. The commands below show how this
deployment is run, as a reference for your own server.

### Service management (systemd)

The game server runs under `campaign-server.service`: enabled at boot, restarted 10 seconds after a crash,
output appended to `logs/server.log`.

```bash
systemctl status  campaign-server.service
systemctl restart campaign-server.service
journalctl -u campaign-server.service -f      # or ./server.sh logs
```

Manual control goes through the local `server.sh`, which switches to `systemctl` once the unit exists
(and falls back to `setsid + nohup` when it does not):

```bash
./server.sh start | stop | restart | status
./server.sh console 'campaign-status'   # one command through the console.in FIFO
./server.sh shell                       # interactive console (log + input)
./server.sh tmux                        # log on top, commands below
./server.sh logs                        # journalctl -f
```

Both the unit file and `server.sh` are site-specific. If you write your own unit, the behaviour above comes from
`Type=simple`, `ExecStart=start.sh`, `User=colorgarden`, `StandardOutput=append:logs/server.log`,
`Restart=on-failure`, `KillSignal=SIGTERM` and `TimeoutStopSec=90`.

The unit stops with SIGTERM so Mindustry's shutdown hook saves; the wrapper still prefers sending `exit` to the
console first so the plugin can finish its own save flow. A polkit rule is what lets the unprivileged service user
manage the units without interactive authentication.

## 14. Backups

Backups are separate from the game server: an independent systemd timer (`campaign-backup.timer`) takes one
snapshot per day and **keeps only the last five days**, deleting older ones.

Each snapshot goes to `config/backups/<timestamp>/` and contains:

- every `*.msav` from `config/saves/` (current save, rotated `-backup.msav`, preserved `.lost-*.msav`)
- the plugin `config.json` and the `blueprints/` library
- Mindustry's `settings.bin` (admin list, bans, per-sector economy `<planet>-s-<id>-info`)

The backup script is site-specific too and not part of the repository; these are the commands used on this
deployment:

```bash
systemctl list-timers campaign-backup.timer   # next run
bash /usr/local/sbin/campaign-backup.sh       # take one right now
tail -f /var/log/campaign-backup.log          # log
```

Restoring a snapshot:

```bash
runuser -u colorgarden -- .../server.sh stop
cp config/backups/<timestamp>/sector-serpulo-99.msav config/saves/
runuser -u colorgarden -- .../server.sh start
```

The retention window is `RETENTION_DAYS=5` at the top of the script; changing it needs no timer reinstall.
