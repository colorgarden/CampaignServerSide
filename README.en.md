# Campaign Server Side

[简体中文](README.md) | **English**

> [!WARNING]
> This mod is at a **very early testing stage** and certainly has bugs nobody has found yet.
> If you hit one, please open an issue or send a PR — vibe-coded PRs are welcome too.
>
> This project **contains AI-generated content**.

A server-side plugin that keeps **Mindustry v160.x campaign mode running on a dedicated server**.

The vanilla campaign lives on the client: sector maps, launch pad wiring, the planet view and the launch loadout are all client-side, and on a dedicated server `Vars.control` is null while `Logic#sectorCapture()` never saves. This plugin fills those gaps on the server — generating, loading and saving sectors, maintaining the sector economy, resolving capture and loss, and taking over research unlocking and the launch pad chain — and exposes them through vanilla dialogs (`followUpMenu` / `textInput` / `infoPopup`).

**Players join with an unmodified client.** Client mods that want programmatic access can use the optional protocol (see [`PROTOCOL.md`](PROTOCOL.md)).

- Version: **0.0.5.1-beta** ([release](https://github.com/colorgarden/CampaignServerSide/releases/tag/v0.0.5.1-beta))
- Runtime: Mindustry **v160.x** dedicated server (`server-release.jar`), **Java 17+**
- License: **AGPL-3.0-or-later** (see [`LICENSE`](LICENSE))

> [!TIP]
> **Test server: `colorgarden.top`** (port `6567`, join straight from a v160 client)
> **QQ group: `1135085472`** (bug reports, finding people to play with, release news)

---

## 1. Features

### Campaign

- Sectors load from their save; without one they are generated from the planet generator and written to disk immediately
- Saves on a timer, on shutdown, on capture and on loss
- Sector states: never launched / launched / captured / lost (ruins)
- Sector economy: core storage, production per minute, exports per minute, imports per minute; the detail page also names the source sectors and the current launch destination
- Launch/landing pad chain: a new sector points at its origin automatically; can be set by hand or rewired for the whole planet (same planet only)
- Loss handling: ruins are saved and rebuilt the vanilla way (load old save → place core → damage along enemy paths → restore surviving buildings as derelicts), or the save can be discarded

### UI without a client mod

- `/c` hub: votes / admin / sector browser
- `/cv` vote menu and `/ca` admin panel
- Launch setup: core blueprint, cargo, capacity checks, confirm launch
- Sector detail page: localized name, state, storage, production, exports, imports, sources and destination, refreshed in place every 1.5 s

### Voting

Eight kinds, each with a target list and a confirmation page:

`sector switch` `research unlock` `planet switch` `launch destination` `redirect all exports` `pause/resume` `wave skip` `abandon sector`

### Research

- Server-side research: resolves content, checks tech-tree parents, objectives (sector completion / prior research) and item costs, then pays from the **planet-wide stock** and unlocks
- Auto unlock: mirrors vanilla `Control#checkAutoUnlocks`, which only runs on a client
- Core item unlock: mirrors vanilla `Control#update`'s "unlock every item sitting in the core" — Erekir's beryllium depends on it
- Unlocks travel the vanilla path (`UnlockEvent` → `Call.researched`); late joiners receive them with the world data

### Campaign rules

Stored **per planet**, so Serpulo and Erekir keep separate settings:

`difficulty` `sector invasion` `fog` `hide spawns` `random wave AI` `RTS AI` `pause disabled` `clear sector on loss` `reactor explosions`

### Environment

- Weather: rebuilds the weather schedule from terrain (same rules as vanilla `PlanetGenerator#addWeather`) and drives actual weather events
- Day/night: derives the time from the planet rotation, maps it to ambient light and broadcasts it at a limited rate

### Maintenance

- **Physics rebuild**: vanilla only recycles a unit's physics body when the unit changes collision layer, so despawned units leak bodies; after wave skips or mass deaths the server drops to 1 tps with an idle CPU. The plugin rebuilds the physics world from the live units on sector load, every five minutes, and on `campaign-fixphysics`
- **World processors**: vanilla sets `disableWorldProcessors` on capture, which permanently stops logic blocks in that sector on every later load; the plugin clears it on load and after capture
- Diagnostic commands cover saves, maps, rules, pads, weather, physics and sector accessibility

---

## 2. Installing

1. Download `CampaignServerSide.jar` from [Releases](https://github.com/colorgarden/CampaignServerSide/releases)
2. Put it in the server's `config/mods/`:

   ```
   <server>/
     server-release.jar
     config/mods/CampaignServerSide.jar
   ```

3. Start the server
4. Run `campaign-host` (or `campaign-host <sector>`) in the console
5. To resume the last sector after a restart, set `"autoStart"` to `true`

Campaign maps (`maps/serpulo/*.msav`, `maps/erekir/*.msav`) ship inside `server-release.jar`; no extra `assets.jar` is needed.

---

## 3. In-game commands

Prefix `/`; `/campaignhelp` lists everything.

| Command | Permission | Description |
| --- | --- | --- |
| `/c` `/campaign` | everyone | Campaign hub (votes / admin / sector browser) |
| `/cv` `/cvote` | everyone | Vote menu; `menu` opens the dialog |
| `/cvote <kind> [target]` | everyone | Start a vote: `sector` `planet` `research` `destination` `redirect` `pause` `wave [count]` `abandon` |
| `/cvote list <kind> [query]` | everyone | List vote targets; `--all` includes locked research |
| `/cvote yes` `/cvote no` `/cvote cancel` | everyone | Vote, withdraw |
| `/cvote info` | everyone | Current vote state |
| `/cvote help` | everyone | Vote command help |
| `/ca` `/cadmin` | admin | Admin panel and direct actions |
| `/ca menu` | admin | Open the dialog |
| `/ca research <name\|all>` | admin | Unlock research |
| `/ca sector <sector>` | admin | Switch sector |
| `/ca planet <planet>` | admin | Switch planet |
| `/ca rule <name> [on\|off]` | admin | Toggle a campaign rule of the current planet |
| `/ca difficulty [name]` | admin | Show or set the current planet's difficulty |
| `/ca sim [pause\|resume]` | admin | Pause or resume the simulation |
| `/ca save` `/ca capture` | admin | Save the sector / force-capture it |
| `/cj [count]` `/cjump [count]` | admin | Skip waves (default 1, max 50, cooldown applies) |
| `/sector <name\|id>` | see config | Switch sector |
| `/sectors [planet\|page]` | everyone | Sector list with states |
| `/planet <planet>` | see config | Switch planet (its last or start sector) |
| `/planets` | everyone | Planets and capture progress |
| `/research <name\|all>` | see config | Unlock research |
| `/campaign-status` | everyone | Current sector, wave, remaining research |
| `/campaignsave` | admin | Save the current sector |
| `/capture` | admin | Force-capture |
| `/destination <sector\|clear>` | admin | Set this sector's launch destination |
| `/redirect` | admin | Point every exporting sector at this one |
| `/resources [planet]` | everyone | Planet-wide resources |
| `/sectorinfo <sector>` | everyone | Storage, production and transport of a sector |
| `/sim [pause\|resume\|toggle]` | admin | Pause or resume |
| `/difficulty [name]` | admin | Show or set the current planet's difficulty |
| `/rule [name] [on\|off]` | admin | Show or toggle a rule of the current planet |

`see config` means `sectorSwitchAdminOnly` / `researchAdminOnly`: when `true`, only admins may use it; votes are unaffected.

Chat shortcuts: `y` / `1` (yes), `n` / `0` (no), `a` / `2` (abstain).

---

## 4. Console commands

`campaign-help` lists everything. Grouped by purpose:

**Running the campaign**

| Command | Description |
| --- | --- |
| `campaign-host [sector]` | Host the campaign (last sector or the planet start) |
| `campaign-goto <sector>` | Switch sector (saves the current one first) |
| `campaign-save` | Save the current sector now |
| `campaign-stop` | Save and stop hosting |
| `campaign-status` | Current sector, wave, cores, save file |
| `campaign-config` | Print the configuration and its path |
| `campaign-reload` | Reload `config.json` |

**Sector data**

| Command | Description |
| --- | --- |
| `campaign-sectors [planet\|page]` | List sector presets and states |
| `campaign-reset <sector>` | Delete the save and generate again |
| `campaign-reset-sector <sector>` | Wipe save, sector info and preset unlock |
| `campaign-uncapture <sector>` | Clear the captured flag (replay) |
| `campaign-capture` | Force-capture the current sector |
| `campaign-why <sector>` | Explain whether it can be entered, and why not |
| `campaign-sectorinfo <sector>` | Storage, production and transport details |
| `campaign-resources [planet]` | Planet-wide resources |
| `campaign-destination [sector\|clear]` | Show or set the current sector's destination |
| `campaign-redirect` | Point every exporter at the current sector |
| `campaign-pads` | Trace the pad chain: destination, export means, import rates, cooldowns, per-pad state |
| `campaign-exports` | List destinations and export statistics |
| `campaign-fix-spawn [x] [y]` | Repair a polluted spawn position |
| `campaign-respawn [sector\|all]` | Clear the spawn position so generation rerolls it |

**Planets and research**

| Command | Description |
| --- | --- |
| `campaign-planets` | Planets and capture progress |
| `campaign-planet [planet]` | Show or switch planet |
| `campaign-research <name...>` | Unlock research including parents |
| `campaign-research-all` | Unlock everything |
| `campaign-relock <name...>` / `campaign-relock-all` | Lock research again |
| `campaign-unlocked` | List everything currently unlocked |
| `campaign-difficulty [name]` | Show or set the current planet's difficulty |
| `campaign-rule [name] [on\|off]` | Show or toggle a rule (`invasion` `fog` `hidespawns` `randomwaveai` `rtsai` `pausedisabled` `clearonlose` `reactorexplosions`) |

**Votes and simulation**

| Command | Description |
| --- | --- |
| `campaign-votelist [kind] [query]` | List the targets a vote could use right now |
| `campaign-vote [info\|cancel]` | Show or cancel the running vote |
| `campaign-sim [pause\|resume\|toggle]` | Pause or resume the simulation |
| `campaign-skipwave [count]` | Skip waves (default 1, max 50, cooldown applies) |
| `campaign-autopause [on\|off]` | Show or set vanilla "pause while nobody is online" |

**Environment and diagnostics**

| Command | Description |
| --- | --- |
| `campaign-weather [list\|floors\|refill\|<name> <seconds>]` | Weather schedule, terrain statistics, rebuild, force an event |
| `campaign-daynight` | Current light level and mapped ambient darkness |
| `campaign-logic [on\|off\|probe]` | World processor state, keep-alive switch, execution probe |
| `campaign-fixphysics` | Rebuild the physics world from the live units |
| `campaign-refresh-buildings` | Rebuild building proximity caches (fixes stalled logistics/logic) |
| `campaign-cores` | Core blocks, sizes and allowed blueprint footprint |
| `campaign-joininfo` | Preview the join notice |
| `campaign-diag` | Save manager, map availability, API state, snapshot round trip |
| `campaign-dumprules` | Dump the rules JSON clients receive (debug) |

Sector syntax: preset name `groundZero`, ID `170`, `id:170`, cross-planet `erekir:onset`.

---

## 5. Voting

### Starting a vote

- The `voting` master switch and the switch of that kind are on
- Online **non-AFK** players ≥ `voteMinPlayers`
- The starter is not on cooldown (`voteCooldown`)
- The target is usable (sector enterable, research unlockable, destination legal)

### Timing

| Setting | Default | Meaning |
| --- | --- | --- |
| `voteDuration` | `15` | Total window in seconds; settles when it ends |
| `votePushSeconds` | `5` | How long before the end the ballot is pushed (must be shorter than the window) |
| `voteTimeout` | `10` | Idle time after the ballot before settling; `0` disables |

### Decision

**The yes quorum always applies**: yes votes must reach `ceil(participants × votePercent)` (`votePercent` defaults to `0.6`, minimum 1 vote). Above that quorum, `voteDecision` decides:

| Mode | Passes when | Fails when |
| --- | --- | --- |
| `majority` (default) | quorum met and yes > no | yes ≤ no, or quorum unreachable |
| `threshold` | quorum met | no votes reach the quorum |
| `veto` | quorum met and no no-votes | any no vote |

### Silence and abstaining

- `unvoted`: how players who never answered count — `abstain` (default) / `yes` / `no`
- At settlement the quorum is recomputed over **participants**: abstainers, and (in `abstain` mode) silent players, leave the denominator, so an AFK player cannot inflate it
- While the vote runs the quorum stays frozen at its start value

### AFK

Players who have not moved for `afkSeconds` (default 60) are excluded from the denominator, cannot start a vote and are not pushed a ballot; entering and leaving AFK is announced in chat. Paused time does not count.

Several votes may run in parallel, each on its own clock.

---

## 6. Campaign rules

Rules live in the **planet's own `campaignRules`** (the vanilla mechanism, persisted with the server settings), so the two planets can use different difficulties and switches. `reactorExplosions` has no `CampaignRules` field, so it is stored per planet in the plugin config under `planetReactorExplosions`.

- The first planet to be initialized inherits the old global settings; later planets start from their own vanilla defaults (Erekir ships fog and RTS AI on)
- Change them with `/ca rule`, `/ca difficulty`, `/rule`, `/difficulty`, `campaign-rule`, `campaign-difficulty`, or the matching votes
- Changes apply to the current sector immediately and are written to that planet's rule record

---

## 7. Launch setup and blueprints

Entering a sector without a base (or an admin cutting straight in) opens the launch setup:

- **Blueprint library**: per planet, at `config/campaign/blueprints/<planet>/`; each blueprint is a `.msch` plus a sidecar `.name`; single-core presets are generated from the cores unlocked on that planet
- **Upload**: paste the base64 text the game exports; validated for decoding, non-empty content, exactly one core, all blocks unlocked, and a footprint no larger than core size + 10 (vanilla `maxLoadoutSchematicPad = 5`)
- **Rename / delete**: only uploaded blueprints; generated presets are read-only
- **Cargo**: ±50 / maximize / maximize all per item; each item is capped at `min(origin stock, core capacity)` with `launchCapacityMultiplier × itemCapacity`, and is deducted from the origin sector
- Planets that do not allow a launch blueprint/loadout (Erekir) get neither option and are never charged

The cargo list is stored in `config/campaign/cargo.txt`.

---

## 8. Configuration

Path: `<server>/config/mods/Campaign Server Side/config.json`, created on first start; run `campaign-reload` after editing.

### Campaign

| Key | Default | Meaning |
| --- | --- | --- |
| `planet` | `"serpulo"` | Current campaign planet |
| `sector` | `-1` | Last sector ID; `-1` means automatic |
| `language` | `"auto"` | Message language: `auto` / `en` / `zh_CN` |
| `autoStart` | `false` | Resume the last sector on startup |

### Saving and loss

| Key | Default | Meaning |
| --- | --- | --- |
| `autosave` | `true` | Periodic saving |
| `autosaveSeconds` | `0` | `0` follows vanilla `saveinterval` |
| `saveOnExit` | `true` | Save on shutdown |
| `paused` | `false` | Whether the game was paused on purpose (restored on restart) |
| `clearSectorOnLose` | `false` | Discard the save on loss (`true`) or keep ruins (`false`) |
| `regenerateLostSector` | `false` | Force a rebuild when a save has no core left |
| `blueprintOnRebuild` | `true` | Apply the selected blueprint when a lost sector is rebuilt |

### Permissions

| Key | Default | Meaning |
| --- | --- | --- |
| `allowSectorSwitch` | `true` | Master switch for sector switching |
| `allowResearch` | `true` | Master switch for research |
| `sectorSwitchAdminOnly` | `true` | Only admins may switch sectors directly |
| `researchAdminOnly` | `true` | Only admins may unlock research directly |
| `allowPlanetSwitch` | `true` | Master switch for planet switching |
| `planetSwitchAdminOnly` | `true` | Only admins may switch planets directly |
| `enforceSectorUnlock` | `true` | Restrict entry to unlocked sectors (vanilla rules) |
| `adminBypassLock` | `true` | Admins ignore that restriction |

### Voting

| Key | Default | Meaning |
| --- | --- | --- |
| `voting` | `true` | Master switch |
| `voteSector` `voteResearch` `votePlanet` | `true` | Per-kind switches |
| `voteDestination` `voteRedirect` `votePause` `voteWave` `voteAbandon` | `true` | Per-kind switches |
| `votePercent` | `0.6` | Yes quorum ratio |
| `voteMinPlayers` | `2` | Minimum online players to start |
| `voteDuration` | `15` | Total window in seconds |
| `votePushSeconds` | `5` | Ballot lead time in seconds |
| `voteTimeout` | `10` | Idle settle time in seconds; `0` disables |
| `voteCooldown` | `2` | Start cooldown in seconds |
| `voteDecision` | `"majority"` | `majority` / `threshold` / `veto` |
| `unvoted` | `"abstain"` | Silent players: `abstain` / `yes` / `no` |

### Rules and environment

| Key | Default | Meaning |
| --- | --- | --- |
| `difficulty` | `"normal"` | Seed used when the first planet is initialized |
| `ruleSectorInvasion` `ruleFog` `ruleHideSpawns` | `false` | Seed values |
| `ruleRandomWaveAI` `ruleRtsAI` `rulePauseDisabled` | `false` | Seed values |
| `ruleReactorExplosions` | `true` | Seed value |
| `weatherSystem` | `true` | Enable weather |
| `dayNightCycle` | `true` | Enable the day/night cycle |
| `dayNightSyncSeconds` | `5` | Light broadcast interval in seconds |

### Other

| Key | Default | Meaning |
| --- | --- | --- |
| `afkEnabled` | `true` | AFK detection |
| `afkSeconds` | `60` | Idle threshold in seconds |
| `skipWaveCooldown` | `30` | Wave skip cooldown in seconds |
| `worldProcessors` | `true` | Keep world processors running |
| `joinInfo` | `true` | Join notice |
| `announce` | `true` | Capture/loss announcements |
| `announceUnlocks` | `true` | Research unlock announcements |
| `launchPadLinking` | `true` | Link a new sector to its origin |
| `promptLaunch` | `true` | Show the launch setup for a sector without a base |
| `resyncClients` | `true` | Resend world data after switching |

Saves live in `config/saves/sector-<planet>-<id>.msav`; sector economy data is part of the server settings.

---

## 9. Differences from vanilla

- **No client campaign UI**: the planet view, sector picking and pad configuration are replaced by server dialogs and commands; the client cannot see the planet map
- **Pad destinations are server-side**: vanilla disables `LaunchPad#buildConfiguration` in multiplayer; the plugin owns `destination` and the transport settlement
- **Blueprint library is server-side**: vanilla reads client blueprint files; here they are uploaded, and kept **per planet**
- **Loss keeps ruins by default**: with `clearSectorOnLose = false` the ruins are saved and rebuilt; vanilla decides this through a client rule
- **Several client-only steps are patched in**: core item unlock, auto-unlock polling, world processor reset and the physics rebuild reproduce steps vanilla only performs on a client, or never performs at all
- **The server owns the save**: generation writes to disk immediately, which vanilla leaves to the client

## 10. Known limitations

- Pre-release; **save compatibility is not guaranteed**
- Production/export statistics of a sector that is not loaded stay at the values from when it was last left (vanilla behaviour)
- World processors only run in the loaded sector (vanilla behaviour)
- The client HUD shows `TPS 1` permanently: vanilla puts `Core.graphics.getFramesPerSecond()` into the state snapshot and that value is fixed at 1 on a headless server; unrelated to this plugin
- Blueprints and cargo are server-wide settings; two players preparing a launch overwrite each other
- A rebuilt lost sector uses the currently selected blueprint, not the one it was launched with
- The server does not verify that a player has unlocked a block (vanilla multiplayer does not either)

---

## 11. Client API

The plugin broadcasts its protocol version through `state.rules.tags["campaign-api"]`; after a handshake a client mod can switch sectors/planets, unlock research, save, start votes and read sector information.

Packet layout, fields and examples live in [`PROTOCOL.md`](PROTOCOL.md).

---

## 12. Feedback

- Issues / PRs: welcome, vibe-coded ones included
- QQ group: `1135085472`
- Test server: `colorgarden.top:6567`
