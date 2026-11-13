// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.command;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.CampaignHooks;
import top.colorgarden.core.ResearchService;
import top.colorgarden.core.SectorService;
import top.colorgarden.net.CampaignInfo;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.net.CampaignProtocol;
import top.colorgarden.util.CampaignException;
import top.colorgarden.util.I18n;
import top.colorgarden.vote.CampaignVote;
import top.colorgarden.vote.VoteMenu;


import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.type.Item;
import mindustry.type.ItemSeq;
import mindustry.type.ItemStack;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Player;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;

import static mindustry.Vars.*;

/** Console and in-game commands. Every message goes through {@link I18n}. */
public class CampaignCommands{
    private static final int pageSize = 10;

    // ---------------------------------------------------------------- console

    public static void registerServer(CommandHandler handler){
        handler.register("campaign-help", "List the campaign commands.", args -> {
            infoHelp("campaign.help.header");
            infoHelp("campaign.help.host");
            infoHelp("campaign.help.goto");
            infoHelp("campaign.help.reset");
            infoHelp("campaign.help.save");
            infoHelp("campaign.help.stop");
            infoHelp("campaign.help.status");
            infoHelp("campaign.help.sectors");
            infoHelp("campaign.help.capture");
            infoHelp("campaign.help.research");
            infoHelp("campaign.help.researchall");
            infoHelp("campaign.help.relock");
            infoHelp("campaign.help.relockall");
            infoHelp("campaign.help.config");
            infoHelp("campaign.help.reload");
            infoHelp("campaign.help.vote");
            infoHelp("campaign.help.destination");
            infoHelp("campaign.help.redirect");
            infoHelp("campaign.help.why");
            infoHelp("campaign.help.joininfo");
            infoHelp("campaign.help.logic");
            infoHelp("campaign.help.uncapture");
            infoHelp("campaign.help.pause");
            infoHelp("campaign.help.planets");
            infoHelp("campaign.help.planet");
            infoHelp("campaign.help.resources");
            infoHelp("campaign.help.sectorinfo");
            infoHelp("campaign.help.sectorarg");
        });

        handler.register("campaign-host", "[sector]", "Host the campaign, starting at a sector.", args -> {
            if(state.isGame()){
                err("campaign.msg.already-hosting");
                return;
            }

            try{
                Sector sector = SectorService.resolve(args.length > 0 ? args[0] : null);
                warnIfLocked(sector);
                SectorService.enter(sector, false, true);
                info("campaign.msg.hosting", sector.name(), sector.id);
            }catch(CampaignException e){
                err(e.getMessage());
            }catch(Throwable t){
                Log.err(t);
                err("campaign.error.unexpected", Strings.getSimpleMessage(t));
            }
        });

        handler.register("campaign-goto", "<sector>", "Switch the campaign to another sector.", args -> {
            try{
                Sector sector = requireSector(args[0]);
                warnIfLocked(sector);
                SectorService.enter(sector, false, true);
                info("campaign.msg.switched", sector.name());
            }catch(CampaignException e){
                err(e.getMessage());
            }catch(Throwable t){
                Log.err(t);
                err("campaign.error.unexpected", Strings.getSimpleMessage(t));
            }
        });

        handler.register("campaign-reset", "<sector>", "Delete a sector's save data and generate it again.", args -> {
            try{
                Sector sector = requireSector(args[0]);
                Sector previous = SectorService.current();
                warnIfLocked(sector);
                SectorService.enter(sector, true, true);
                info("campaign.msg.regenerated", sector.name(), previous == null ? "-" : previous.name());
            }catch(CampaignException e){
                err(e.getMessage());
            }catch(Throwable t){
                Log.err(t);
                err("campaign.error.unexpected", Strings.getSimpleMessage(t));
            }
        });

        handler.register("campaign-stop", "Stop hosting the campaign.", args -> {
            if(!state.isGame()){
                err("campaign.msg.not-hosting");
                return;
            }
            SectorService.stopHosting();
            info("campaign.msg.stopped");
        });

        handler.register("campaign-save", "Save the current sector.", args -> {
            Sector sector = SectorService.current();
            if(sector == null){
                err("campaign.msg.not-playing");
                return;
            }
            if(SectorService.saveCurrent()){
                info("campaign.msg.saved", sector.name());
            }else{
                err("campaign.msg.save-failed");
            }
        });

        handler.register("campaign-status", "Show the current campaign status.", args -> {
            Sector sector = SectorService.current();
            if(sector == null){
                info("campaign.msg.not-playing");
                return;
            }

            info("campaign.status.planet", sector.planet.localizedName, sector.name(), sector.id);
            info("campaign.status.state", statusText(SectorService.statusOf(sector)), state.getState(), state.wave, state.rules.waves, sector.info.wasCaptured);
            info("campaign.status.entities", state.enemies, mindustry.gen.Groups.unit.size(), mindustry.gen.Groups.player.size());
            info("campaign.status.world", world.width(), world.height(), state.teams.playerCores().size, sector.hasBase());
            info("campaign.status.save-file", SectorService.sectorFile(sector).name(), SectorService.sectorFile(sector).exists());
            info("campaign.status.destination", sector.info.destination == null ? "-" : sector.info.destination.name(),
                sector.info.origin == null ? "-" : sector.info.origin.name());
            info("campaign.status.research-locked", ResearchService.lockedCount(sector.planet));
            int[] logic = CampaignHooks.logicCounts();
            info("campaign.status.logic", state.rules.disableWorldProcessors, logic[0], logic[2], logic[3]);
        });

        handler.register("campaign-capture", "Force-capture the current sector (admin tool).", args -> {
            if(!state.isGame() || SectorService.current() == null){
                err("campaign.msg.not-playing");
                return;
            }

            try{
                mindustry.core.Logic.sectorCapture();
                info("campaign.msg.captured");
            }catch(Throwable t){
                Log.err(t);
                err("campaign.msg.capture-failed", Strings.getSimpleMessage(t));
            }
        });

        handler.register("campaign-diag", "Print diagnostics about the campaign setup.", args -> {
            info("campaign.diag.version", mindustry.core.Version.buildString(), headless, CampaignHooks.enabled);
            info("campaign.diag.save-manager", SectorService.saves != null);
            info("campaign.diag.config", CampaignConfig.data().autoStart, I18n.language());

            Sector sector = SectorService.current();
            if(sector == null){
                info("campaign.diag.no-sector");
            }else{
                info("campaign.diag.sector", sector.name(), sector.id, sector.planet.name);
                infoRaw("  " + I18n.get("campaign.diag.sector-preset", sector.preset == null ? "-" : sector.preset.name,
                    sector.preset == null || sector.preset.generator == null ? "-" : sector.preset.generator.getClass().getSimpleName(),
                    sector.preset == null || sector.preset.generator == null || sector.preset.generator.map == null ? "-" : sector.preset.generator.map.name()));
                infoRaw("  " + I18n.get("campaign.diag.sector-state", sector.hasBase(), sector.save == null ? "-" : sector.save.file.name(), sector.info.wasCaptured));
                infoRaw("  " + I18n.get("campaign.diag.world", world.width(), world.height(), state.wave, spawner.countSpawns()));
            }

            Seq<SectorPreset> presets = SectorService.presets(SectorService.planet());
            info("campaign.diag.maps", presets.count(p -> p.generator != null && p.generator.map != null), presets.size);

            info("campaign.diag.api", CampaignProtocol.version, CampaignNetwork.serverSupportsApi(), CampaignNetwork.capableCount());
            CampaignInfo probe = CampaignInfo.build("");
            CampaignInfo back = CampaignInfo.fromJson(probe.toJson());
            info("campaign.diag.snapshot", probe.toJson().length(),
                back != null && back.protocol == probe.protocol && back.sectors.length == probe.sectors.length,
                probe.sectors.length);
        });

        handler.register("campaign-planets", "List the planets that take part in the campaign.", args -> {
            Planet currentPlanet = SectorService.planet();
            for(Planet planet : SectorService.planets()){
                int captured = 0;
                for(Sector sector : planet.sectors){
                    if(sector != null && sector.hasBase()) captured ++;
                }
                info("campaign.planets.line", planet == currentPlanet ? "[accent]" : "", planet.localizedName,
                    planet.name, captured, planet.sectors.size);
            }
            info("campaign.planets.hint");
        });

        handler.register("campaign-planet", "[name]", "Show or switch the campaign planet.", args -> {
            if(args.length == 0){
                Planet planet = SectorService.planet();
                info("campaign.planet.current", planet == null ? "-" : planet.localizedName,
                    planet == null ? "-" : planet.name,
                    SectorService.targetFor(planet) == null ? "-" : SectorService.targetFor(planet).name());
                return;
            }

            Planet planet = SectorService.planetByName(args[0]);
            if(planet == null){
                err("campaign.planet.invalid", args[0]);
                return;
            }

            Sector target = SectorService.targetFor(planet);
            if(target == null){
                err("campaign.planet.empty", planet.localizedName);
                return;
            }

            try{
                warnIfLocked(target);
                SectorService.enter(target, false, true);
                info("campaign.planet.switched", planet.localizedName, target.name());
            }catch(CampaignException e){
                err(e.getMessage());
            }
        });

        handler.register("campaign-sectors", "[planet|page] [page]", "List sector presets for the campaign planet.", args -> {
            Planet planet = SectorService.planet();
            String pageArg = args.length > 0 ? args[0] : null;

            if(pageArg != null && !pageArg.matches("\\d+")){
                Planet named = SectorService.planetByName(pageArg);
                if(named == null){
                    err("campaign.planet.invalid", pageArg);
                    return;
                }
                planet = named;
                pageArg = args.length > 1 ? args[1] : null;
            }

            Seq<SectorPreset> presets = SectorService.presets(planet);

            if(presets.isEmpty()){
                err("campaign.sectors.none");
                return;
            }

            int page = pageOf(pageArg, presets.size);
            int pages = Math.max(1, (presets.size + pageSize - 1) / pageSize);

            info("campaign.sectors.header", planet.localizedName, presets.size, page + 1, pages);
            for(int i = page * pageSize; i < Math.min(presets.size, (page + 1) * pageSize); i++){
                infoRaw("  " + describeSector(presets.get(i)));
            }
        });

        handler.register("campaign-research", "<name...>", "Unlock research by content name.", args -> {
            UnlockableContent unlockable = ResearchService.find(args[0]);
            if(unlockable == null){
                err("campaign.research.unknown", args[0]);
                return;
            }

            if(ResearchService.autoUnlockable(unlockable.techNode)){
                err("campaign.research.auto", I18n.content(unlockable));
                return;
            }

            String blocker = ResearchService.researchBlocker(unlockable);
            if(blocker != null){
                err(blocker);
                return;
            }

            if(CampaignConfig.data().researchCosts && state.isCampaign() && !unlockable.unlocked()){
                infoCost(unlockable);
            }

            boolean changed = ResearchService.research(unlockable, true);
            if(changed){
                info("campaign.research.ok", I18n.content(unlockable));
            }else if(!unlockable.unlocked()){
                err("campaign.research.missing", formatStacks(ResearchService.missing(unlockable)));
            }else{
                info("campaign.research.already", I18n.content(unlockable));
            }
        });

        handler.register("campaign-research-all", "Unlock every research entry.", args -> {
            int count = ResearchService.researchAll(SectorService.planet());
            info("campaign.research.all", count);
        });

        handler.register("campaign-relock", "<name...>", "Lock research again (clients must reconnect).", args -> {
            UnlockableContent unlockable = ResearchService.find(args[0]);
            if(unlockable == null){
                err("campaign.research.unknown", args[0]);
                return;
            }

            ResearchService.relock(unlockable);
            info("campaign.research.relocked", I18n.content(unlockable));
        });

        handler.register("campaign-relock-all", "Lock every research entry again and clear its cost progress.", args -> {
            int count = ResearchService.relockAll();
            info("campaign.research.relocked-all", count);
        });

        handler.register("campaign-unlocked", "List the research entries that are unlocked right now.", args -> {
            Seq<String> names = new Seq<>();
            for(UnlockableContent unlockable : ResearchService.unlockedNow()){
                names.add(I18n.content(unlockable));
            }
            names.sort();
            info("campaign.research.unlocked-list", names.size, names.toString(", "));
        });

        //same filter as /cvote list, so the console can check what players are offered
        handler.register("campaign-votelist", "[kind] [query...]", "List the targets a vote could use right now (add --all for locked research).", args -> {
            String kindArg = args.length > 0 ? args[0].toLowerCase() : "sector";
            if(!isVoteKind(kindArg)){
                err("campaign.vote.usage");
                return;
            }

            boolean includeBlocked = args.length > 1 && args[1].contains("--all");
            CampaignVote.Kind kind = voteKind(kindArg);
            String query = args.length > 1 ? args[1].replace("--all", "").trim() : "";
            Seq<String> found = CampaignVote.search(kind, query, 40, includeBlocked);

            info("campaign.vote.list-header", I18n.get("campaign.vote.kind." + kind.name()), found.size);
            for(String entry : found){
                infoRaw("  " + entry);
            }
        });

        handler.register("campaign-vote", "[info|cancel]", "Show or cancel the running player vote.", args -> {
            if(args.length > 0 && "cancel".equalsIgnoreCase(args[0])){
                if(CampaignVote.cancel(null)){
                    info("campaign.vote.cancelled-console");
                }else{
                    err("campaign.vote.no-vote");
                }
                return;
            }

            CampaignVote.Vote vote = CampaignVote.active();
            if(vote == null){
                info("campaign.vote.info-none");
                return;
            }
            info("campaign.vote.info", vote.starterName, CampaignVote.describe(vote), vote.yes.size, CampaignVote.requiredVotes(), vote.secondsLeft());
        });

        handler.register("campaign-joininfo", "Preview the notice a player gets after joining.", args -> {
            info("campaign.join.preview-player");
            for(String line : CampaignHooks.joinInfoLines(false)) infoRaw("  " + line);
            info("campaign.join.preview-admin");
            for(String line : CampaignHooks.joinInfoLines(true)) infoRaw("  " + line);
        });

        handler.register("campaign-destination", "[sector]", "Show or set the launch/landing pad link of the current sector.", args -> {
            Sector sector = SectorService.current();
            if(sector == null){
                err("campaign.msg.not-playing");
                return;
            }

            if(args.length == 0){
                info("campaign.destination.header", sector.name(),
                    sector.info.destination == null ? "-" : sector.info.destination.name());
                return;
            }

            if("clear".equalsIgnoreCase(args[0])){
                SectorService.setDestination(sector, null);
                info("campaign.destination.cleared", sector.name());
                CampaignNetwork.pushInfoToAll();
                return;
            }

            Sector destination = SectorService.resolve(args[0]);
            if(destination == null){
                err("campaign.destination.invalid", args[0]);
                return;
            }
            try{
                SectorService.setDestination(sector, destination);
                info("campaign.destination.set", sector.name(), destination.name());
                CampaignNetwork.pushInfoToAll();
            }catch(CampaignException e){
                err(e.getMessage());
            }
        });

        handler.register("campaign-redirect", "Point every sector that exports items at the current sector.", args -> {
            Sector sector = SectorService.current();
            if(sector == null){
                err("campaign.msg.not-playing");
                return;
            }

            int count = SectorService.redirectAllExports(sector);
            info("campaign.destination.redirected", count, sector.name());
            CampaignNetwork.pushInfoToAll();
        });

        handler.register("campaign-logic", "[on|off|probe]", "Report world processor state, keep them running, or probe execution.", args -> {
            CampaignConfig config = CampaignConfig.data();

            if(args.length > 0 && "probe".equalsIgnoreCase(args[0])){
                probeLogic();
                return;
            }

            if(args.length > 0){
                boolean on = !"off".equalsIgnoreCase(args[0]);
                config.worldProcessors = on;
                CampaignConfig.save();
                if(on) CampaignHooks.applyLogicRules();
                info("campaign.logic.set", on);
            }

            int[] counts = CampaignHooks.logicCounts();
            info("campaign.logic.header", config.worldProcessors, state.rules.disableWorldProcessors);
            info("campaign.logic.counts", counts[0], counts[1], counts[2], counts[3]);
        });

        handler.register("campaign-dumprules", "Dump the rules JSON that clients receive (debug).", args -> {
            arc.files.Fi file = arc.Core.settings.getDataDirectory().child("rules-dump.json");
            String json = mindustry.io.JsonIO.write(state.rules);
            file.writeString(json);
            info("campaign.dump.done", file.path(), json.length(),
                json.contains("sector:") ? "yes" : "no",
                state.rules.researched.size);
        });

        handler.register("campaign-resources", "[planet]", "Show the total resources of a planet.", args -> {
            Planet planet = args.length > 0 ? SectorService.planetByName(args[0]) : SectorService.planet();
            if(planet == null){
                err("campaign.planet.invalid", args.length > 0 ? args[0] : "-");
                return;
            }

            ItemSeq items = ResearchService.planetItems(planet);
            ObjectMap<Item, Float> production = ResearchService.planetProduction(planet);
            ObjectMap<Item, Float> exports = ResearchService.planetExports(planet);

            info("campaign.resources.header", planet.localizedName, items.total);
            boolean any = false;
            for(Item item : content.items()){
                int amount = items.get(item);
                float produced = production.get(item, 0f);
                float exported = exports.get(item, 0f);
                if(amount <= 0 && produced <= 0f && exported <= 0f) continue;
                any = true;
                info("campaign.resources.line", I18n.content(item), amount, produced, exported);
            }
            if(!any) info("campaign.resources.none");
        });

        handler.register("campaign-sectorinfo", "<sector>", "Show storage, production and transport of a sector.", args -> {
            Sector sector = requireSector(args[0]);
            info("campaign.sectorinfo.header", sector.name(), sector.id, sector.planet.localizedName);
            info("campaign.sectorinfo.status", SectorService.statusOf(sector), sector.info.storageCapacity,
                sector.info.items.total, sector.info.origin == null ? "-" : sector.info.origin.name(),
                sector.info.destination == null ? "-" : sector.info.destination.name());

            boolean any = false;
            for(Item item : content.items()){
                int amount = sector.info.items.get(item);
                float produced = sector.info.production.get(item) == null ? 0f : sector.info.production.get(item).mean * 60f;
                float exported = sector.info.export.get(item) == null ? 0f : sector.info.export.get(item).mean * 60f;
                float imported = sector.info.getImportRate(sector.planet, item) * 60f;
                if(amount <= 0 && produced <= 0f && exported <= 0f && imported <= 0f) continue;
                any = true;
                info("campaign.sectorinfo.item", I18n.content(item), amount, produced, exported, imported);
            }
            if(!any) info("campaign.resources.none");
        });

        //empty-server pausing is vanilla's own setting (Administration.Config.autoPause)
        handler.register("campaign-autopause", "[on|off]", "Show or set vanilla autoPause (pause while nobody is online).", args -> {
            mindustry.net.Administration.Config autoPause = mindustry.net.Administration.Config.autoPause;

            if(args.length > 0){
                autoPause.set(!"off".equalsIgnoreCase(args[0]) && !"false".equalsIgnoreCase(args[0]));
            }

            info("campaign.pause.state", autoPause.bool(), state.isPaused());
        });

        handler.register("campaign-uncapture", "<sector>", "Clear the captured flag of a sector.", args -> {
            Sector sector = requireSector(args[0]);
            SectorService.setCaptured(sector, false);
            info("campaign.uncapture.done", sector.name());
        });

        handler.register("campaign-sim", "[pause|resume|toggle]", "Pause or resume the simulation directly (vanilla game state).", args -> {
            boolean pause = toggleSimulation(args.length > 0 ? args[0] : "toggle");
            info(pause ? "campaign.sim.paused" : "campaign.sim.resumed");
        });

        handler.register("campaign-cores", "List every core block, its size and the blueprint footprint it allows.", args -> {
            for(var block : mindustry.Vars.content.blocks()){
                if(!(block instanceof mindustry.world.blocks.storage.CoreBlock core)) continue;
                info("campaign.core.list", core.name, core.size, core.size + top.colorgarden.core.CoreBlueprints.pad() * 2,
                    core.unlocked() ? I18n.get("campaign.core.unlocked") : I18n.get("campaign.core.locked"));
            }
        });
        handler.register("campaign-skipwave", "[count]", "Skip waves at once (default 1, up to " + MAX_SKIP_WAVES + "; cooldown applies).", args -> {
            int count = 1;
            if(args.length > 0){
                try{
                    count = clampWaveCount(Integer.parseInt(args[0]));
                }catch(NumberFormatException e){
                    err("campaign.wave.invalid-count", args[0], MAX_SKIP_WAVES);
                    return;
                }
            }

            String problem = skipWaves(null, count);
            if(problem != null){
                err(problem);
            }else{
                info("campaign.wave.skipped", count, state.wave);
            }
        });

        handler.register("campaign-refresh-buildings", "Rebuild proximity caches of the current sector's buildings (repairs a sector where logistics/logic stopped working).", args -> {
            if(!state.isGame()){
                err("campaign.msg.not-playing");
                return;
            }
            int touched = SectorService.refreshBuildings();
            info("campaign.log.buildings-refreshed", touched);
        });

        handler.register("campaign-fixphysics", "Rebuild the physics world from the live units (last resort; collision is dropped for one frame).", args -> {
            if(!state.isGame()){
                err("campaign.msg.not-playing");
                return;
            }
            int rebuilt = SectorService.resetPhysics();
            info("campaign.log.physics-reset", rebuilt, mindustry.gen.Groups.unit.size());
        });

        handler.register("campaign-purgephysics", "Remove physics bodies that no live unit references (safe: live units keep their collision).", args -> {
            if(!state.isGame()){
                err("campaign.msg.not-playing");
                return;
            }
            int removed = SectorService.purgeStalePhysicsBodies();
            info("campaign.log.physics-purged", removed, SectorService.physicsBodyCount(),
                mindustry.gen.Groups.unit.size());
        });

        handler.register("campaign-exports", "List every sector of the planet with its destination, export statistics and whether a redirect would touch it.", args -> {
            var planet = SectorService.planet();
            if(planet == null){
                err("campaign.pads.no-sector");
                return;
            }

            int listed = 0;
            for(mindustry.type.Sector sector : planet.sectors){
                if(sector == null || !sector.hasBase()) continue;

                var info = sector.info;
                boolean exports = info.anyExports();
                if(!exports && info.destination == null) continue;

                StringBuilder means = new StringBuilder();
                info.export.each((item, stat) -> {
                    if(stat.mean <= 0f) return;
                    if(means.length() > 0) means.append(", ");
                    means.append(item.name).append('=').append(arc.util.Strings.fixed(stat.mean, 2));
                });

                info("campaign.exports.line", sector.name(), sector.id,
                    info.destination == null ? "-" : info.destination.name(),
                    exports, means.length() == 0 ? "-" : means.toString());
                listed++;
            }

            if(listed == 0) info("campaign.exports.none");
        });
        handler.register("campaign-weather", "[list|floors|refill|name] [seconds]", "Show the sector's weather schedule, dump the terrain statistics vanilla's addWeather uses, rebuild the schedule the way generation did, or force an event (e.g. rain 60).", args -> {
            if(args.length == 0 || "list".equalsIgnoreCase(args[0])){
                top.colorgarden.core.EnvironmentService.describeSchedule();
                return;
            }

            if("floors".equalsIgnoreCase(args[0]) || "terrain".equalsIgnoreCase(args[0])){
                for(String line : top.colorgarden.core.EnvironmentService.floorReport()){
                    infoRaw("  " + line);
                }
                return;
            }

            if("refill".equalsIgnoreCase(args[0]) || "rebuild".equalsIgnoreCase(args[0])){
                int count = top.colorgarden.core.EnvironmentService.refill();
                if(count < 0){
                    err("campaign.weather.unknown", "-");
                    return;
                }
                info("campaign.weather.refilled", count);
                top.colorgarden.core.EnvironmentService.describeSchedule();
                //the schedule lives in the sector rules, so it has to reach the save
                top.colorgarden.core.SectorService.saveCurrent();
                return;
            }

            float seconds = args.length > 1 ? Float.parseFloat(args[1]) : 60f;
            if(!top.colorgarden.core.EnvironmentService.force(args[0], seconds)){
                err("campaign.weather.unknown", args[0]);
            }
        });

        handler.register("campaign-daynight", "Show the current day/night light level and ambient darkness.", args -> {
            var sector = SectorService.current();
            if(sector == null){
                err("campaign.pads.no-sector");
                return;
            }

            float light = sector.getLight();
            float alpha = arc.math.Mathf.clamp(arc.math.Mathf.map(light,
                sector.planet.lightSrcFrom, sector.planet.lightSrcTo,
                sector.planet.lightDstFrom, sector.planet.lightDstTo));

            info("campaign.daynight.state", light, alpha, 1f - alpha,
                sector.planet.updateLighting, sector.preset != null && sector.preset.noLighting,
                mindustry.Vars.state.rules.lighting, CampaignConfig.data().dayNightCycle,
                CampaignConfig.data().dayNightSyncSeconds);
        });
        handler.register("campaign-pads", "Trace the launch/landing pad chain of the current sector: destination, export means, import rates, cooldown timers and every pad's state.", args -> {
            var sector = SectorService.current();
            if(sector == null){
                err("campaign.pads.no-sector");
                return;
            }

            var rules = mindustry.Vars.state.rules;
            var info = sector.info;
            info("campaign.pads.header", sector.name(), sector.id,
                mindustry.Vars.state.isCampaign(),
                sector.planet.campaignRules.legacyLaunchPads);
            info("campaign.pads.destination",
                info.destination == null ? "-" : info.destination.name(),
                info.destination == null ? false : info.destination.hasBase());

            //export means: what the source sectors report, which is what the receiver sums up
            if(info.export.isEmpty()){
                info("campaign.pads.no-export");
            }else{
                info.export.each((item, stat) -> info("campaign.pads.export", item.localizedName, stat.mean, stat.counter));
            }

            float[] rates = info.getImportRates(sector.planet);
            for(mindustry.type.Item item : mindustry.Vars.content.items()){
                if(rates[item.id] > 0f){
                    info("campaign.pads.import-rate", item.localizedName, rates[item.id],
                        info.importCooldownTimers.get(item, 0f));
                }
            }

            int pads = 0;
            for(mindustry.gen.Building build : mindustry.Vars.state.rules.defaultTeam.data().buildings){
                if(build instanceof mindustry.world.blocks.campaign.LandingPad.LandingPadBuild pad){
                    pads++;
                    info("campaign.pads.landing", pad.tileX(), pad.tileY(),
                        pad.config == null ? "-" : pad.config.localizedName,
                        pad.items.total(), pad.cooldown, pad.arriving != null);
                }else if(build.block instanceof mindustry.world.blocks.campaign.LaunchPad){
                    pads++;
                    info("campaign.pads.launch", build.tileX(), build.tileY(),
                        build.items.total(), build.block.itemCapacity);
                }
            }

            if(pads == 0) info("campaign.pads.none");
            info("campaign.pads.total", pads);
        });

        handler.register("campaign-fix-spawn", "[x] [y]", "Repair the stored spawn position of the active sector (no args prints a suggestion from the ruins).", args -> {
            var sector = SectorService.current();
            if(sector == null){
                err("campaign.spawn.no-sector");
                return;
            }

            int old = sector.info.spawnPosition;
            if(args.length >= 2){
                try{
                    int x = Integer.parseInt(args[0]), y = Integer.parseInt(args[1]);
                    sector.info.spawnPosition = arc.math.geom.Point2.pack(x, y);
                    sector.saveInfo();
                    info("campaign.spawn.fixed", x, y,
                        old == 0 ? "-" : (arc.math.geom.Point2.x(old) + "," + arc.math.geom.Point2.y(old)));
                }catch(NumberFormatException e){
                    err("campaign.spawn.bad-coords");
                }
                return;
            }

            //average of the surviving buildings: a compact base puts this close to the old core
            long sx = 0, sy = 0;
            int n = 0;
            for(mindustry.gen.Building build : mindustry.Vars.state.rules.defaultTeam.data().buildings){
                sx += build.tileX();
                sy += build.tileY();
                n++;
            }
            for(mindustry.gen.Building build : mindustry.game.Team.derelict.data().buildings){
                sx += build.tileX();
                sy += build.tileY();
                n++;
            }

            String current = old == 0 ? "-" : (arc.math.geom.Point2.x(old) + "," + arc.math.geom.Point2.y(old));
            if(n == 0){
                info("campaign.spawn.no-buildings", current);
                return;
            }

            info("campaign.spawn.suggestion", sx / n, sy / n, n, current);
        });

        handler.register("campaign-respawn", "[sector|all]", "Clear the stored spawn position so the next generation rerolls it.", args -> {
            int count = SectorService.clearSpawnPositions(args.length > 0 ? args[0] : null);
            info("campaign.spawn.cleared", count);
        });
        handler.register("campaign-difficulty", "[name]", "Show or set the campaign difficulty.", args -> {
            mindustry.game.Difficulty current = SectorService.difficulty();
            info("campaign.difficulty.current", I18n.get("campaign.difficulty." + current.name()));

            if(args.length == 0){
                StringBuilder list = new StringBuilder();
                for(mindustry.game.Difficulty value : mindustry.game.Difficulty.all){
                    if(list.length() > 0) list.append(", ");
                    list.append(value.name());
                }
                infoRaw("  " + I18n.get("campaign.difficulty.list", list.toString()));
                return;
            }

            mindustry.game.Difficulty applied = SectorService.setDifficulty(args[0]);
            if(applied == null){
                err("campaign.difficulty.unknown", args[0]);
            }else{
                info("campaign.difficulty.set", I18n.get("campaign.difficulty." + applied.name()));
            }
        });

        handler.register("campaign-rule", "[name] [on|off]", "Show or toggle a campaign rule flag.", args -> {
            if(args.length == 0){
                info("campaign.rule.header");
                for(String rule : new String[]{"invasion", "fog", "hidespawns", "randomwaveai", "rtsai", "pausedisabled", "clearonlose", "reactorexplosions"}){
                    infoRaw("  " + I18n.get("campaign.rule.name." + rule) + " = " + SectorService.currentRule(rule));
                }
                return;
            }

            if(!SectorService.isRule(args[0])){
                err("campaign.rule.unknown", args[0]);
                return;
            }

            Boolean force = args.length > 1 ? Boolean.valueOf(!("off".equalsIgnoreCase(args[1]) || "false".equalsIgnoreCase(args[1]) || "0".equals(args[1]))) : null;
            Boolean value = SectorService.toggleRule(args[0], force);
            if(value == null){
                err("campaign.rule.unknown", args[0]);
            }else{
                info("campaign.rule.set", args[0], value);
            }
        });

        handler.register("campaign-reset-sector", "<sector>", "Wipe a sector save, clear its info and lock its preset again.", args -> {
            Sector sector = requireSector(args[0]);
            if(SectorService.resetSector(sector)){
                info("campaign.reset-sector.done", sector.name());
            }else{
                err("campaign.reset-sector.refused", sector.name());
            }
        });

        handler.register("campaign-why", "<sector>", "Explain whether a sector can be entered right now.", args -> {            Sector sector = requireSector(args[0]);
            String status = SectorService.statusOf(sector);
            String reason = SectorService.lockReason(sector);
            info("campaign.why.header", sector.name(), sector.id, sector.planet.name, status);
            info("campaign.why.result", SectorService.canEnter(sector), reason.isEmpty() ? "-" : reason);
            String hint = SectorService.launchHint(sector);
            if(!hint.isEmpty()) info("campaign.why.hint", hint);

            //raw data behind the decision, so the vanilla rule can be checked instead of guessed
            mindustry.type.SectorPreset preset = sector.preset;
            if(preset == null){
                infoRaw("  preset: none / hasBase=" + sector.hasBase() + " startSector=" + (sector.id == sector.planet.startSector));
            }else{
                infoRaw("  preset: requireUnlock=" + preset.requireUnlock + " unlocked=" + preset.unlocked()
                    + " locked=" + preset.locked() + " hasBase=" + sector.hasBase());
                mindustry.content.TechTree.TechNode node = preset.techNode;
                if(node == null){
                    infoRaw("  techNode: null");
                }else{
                    String parent = node.parent == null ? "-"
                        : node.parent.content.name + " unlocked=" + node.parent.content.unlocked();
                    infoRaw("  techNode.parent: " + parent);
                    int pending = ResearchService.pendingObjectives(node);
                    infoRaw("  objectives: " + (node.objectives == null ? 0 : node.objectives.size) + ", pending=" + pending);
                    if(node.objectives != null){
                        for(var objective : node.objectives){
                            infoRaw("    " + objective + " complete=" + objective.complete());
                        }
                    }
                }
            }
        });

        handler.register("campaign-config", "Show the plugin configuration.", args -> {
            CampaignConfig config = CampaignConfig.data();
            info("campaign.config.campaign", config.planet, config.sector, config.autoStart);
            info("campaign.config.saving", config.autosave, config.autosaveSeconds, config.saveOnExit, config.clearSectorOnLose);
            info("campaign.config.permissions", config.allowSectorSwitch, config.sectorSwitchAdminOnly, config.allowResearch, config.researchAdminOnly);
            info("campaign.config.voting", config.voting, config.voteSector, config.voteResearch, config.votePercent, config.voteMinPlayers, config.voteDuration);
            info("campaign.config.voting2", config.votePlanet, config.voteDestination, config.voteRedirect, config.voteDecision);
            info("campaign.config.misc", config.joinInfo, config.announce, config.resyncClients);
            info("campaign.config.lock", config.enforceSectorUnlock, config.adminBypassLock, config.launchPadLinking);
            info("campaign.config.logic", config.worldProcessors);
            info("campaign.config.votes", config.voteDecision, config.voteTimeout, config.voteCooldown,
                config.afkEnabled ? (int)config.afkSeconds : "-");
            info("campaign.config.planet", config.allowPlanetSwitch, config.planetSwitchAdminOnly, config.votePlanet);
            info("campaign.config.research", config.researchCosts, config.researchStrict);
            info("campaign.config.file", CampaignConfig.location() == null ? "-" : CampaignConfig.location().path());
        });

        handler.register("campaign-reload", "Reload config.json.", args -> {
            CampaignConfig.load();
            info("campaign.config.reloaded");
        });
    }

    // ---------------------------------------------------------------- in-game

    /**
     * Registers a player command whose replies are resolved in that player's own language; the
     * previous language is restored afterwards so the console keeps the server language.
     */
    private static void client(CommandHandler handler, String name, String params, String description,
                               arc.util.CommandHandler.CommandRunner<Player> runner){
        handler.<Player>register(name, params, description,
            (args, player) -> I18n.withLocale(I18n.localeOf(player), () -> runner.accept(args, player)));
    }

    /** Same, for commands without parameters. */
    private static void client(CommandHandler handler, String name, String description,
                               arc.util.CommandHandler.CommandRunner<Player> runner){
        client(handler, name, "", description, runner);
    }

    public static void registerClient(CommandHandler handler){
        client(handler, "sector", "<name|id>", "[admin] Switch the campaign sector.", (args, player) -> {
            if(!requirePermission(player, CampaignConfig.data().sectorSwitchAdminOnly)) return;
            if(!CampaignConfig.data().allowSectorSwitch){
                player.sendMessage(I18n.get("campaign.error.switch-disabled"));
                return;
            }

            try{
                Sector sector = requireSector(args[0]);
                player.sendMessage(I18n.get("campaign.client.switching", sector.name()));
                SectorService.enter(player, sector, false, canBypass(player));
            }catch(CampaignException e){
                player.sendMessage("[scarlet]" + e.getMessage());
            }catch(Throwable t){
                Log.err(t);
                player.sendMessage(I18n.get("campaign.error.unexpected", Strings.getSimpleMessage(t)));
            }
        });

        client(handler, "campaignhelp", "List the campaign chat commands.", (args, player) -> {
            StringBuilder builder = new StringBuilder(I18n.get("campaign.clienthelp.header"));
            for(String key : new String[]{
                "campaign.clienthelp.sector", "campaign.clienthelp.sectors", "campaign.clienthelp.campaign",
                "campaign.clienthelp.research", "campaign.clienthelp.vote", "campaign.clienthelp.destination",
                "campaign.clienthelp.save", "campaign.clienthelp.planets", "campaign.clienthelp.planet",
                "campaign.clienthelp.resources", "campaign.clienthelp.sectorinfo", "campaign.clienthelp.admin",
                "campaign.clienthelp.jump", "campaign.clienthelp.sim", "campaign.clienthelp.difficulty",
                "campaign.clienthelp.rule", "campaign.clienthelp.help"}){
                builder.append("\n").append(I18n.get(key));
            }
            player.sendMessage(builder.toString());
        });

        client(handler, "planets", "List the planets that take part in the campaign.", (args, player) -> {
            Planet currentPlanet = SectorService.planet();
            StringBuilder builder = new StringBuilder(I18n.get("campaign.planets.header"));
            for(Planet planet : SectorService.planets()){
                int captured = 0;
                for(Sector sector : planet.sectors){
                    if(sector != null && sector.hasBase()) captured ++;
                }
                builder.append("\n").append(I18n.get("campaign.planets.line",
                    planet == currentPlanet ? "[accent]" : "", planet.localizedName,
                    planet.name, captured, planet.sectors.size));
            }
            player.sendMessage(builder.toString());
        });

        client(handler, "planet", "<name>", "[admin] Switch the campaign planet.", (args, player) -> {
            if(!requirePermission(player, CampaignConfig.data().planetSwitchAdminOnly)) return;
            if(!CampaignConfig.data().allowPlanetSwitch){
                player.sendMessage(I18n.get("campaign.error.switch-disabled"));
                return;
            }

            Planet planet = SectorService.planetByName(args[0]);
            if(planet == null){
                player.sendMessage(I18n.get("campaign.planet.invalid", args[0]));
                return;
            }

            Sector target = SectorService.targetFor(planet);
            if(target == null){
                player.sendMessage(I18n.get("campaign.planet.empty", planet.localizedName));
                return;
            }

            try{
                player.sendMessage(I18n.get("campaign.client.planet-switching", planet.localizedName, target.name()));
                SectorService.enter(target, false, canBypass(player));
            }catch(CampaignException e){
                player.sendMessage("[scarlet]" + e.getMessage());
            }
        });

        client(handler, "sectors", "[planet|page] [page]", "List campaign sectors.", (args, player) -> {
            Planet planet = SectorService.planet();
            String pageArg = args.length > 0 ? args[0] : null;

            if(pageArg != null && !pageArg.matches("\\d+")){
                Planet named = SectorService.planetByName(pageArg);
                if(named == null){
                    player.sendMessage(I18n.get("campaign.planet.invalid", pageArg));
                    return;
                }
                planet = named;
                pageArg = args.length > 1 ? args[1] : null;
            }

            Seq<SectorPreset> presets = SectorService.presets(planet);

            if(presets.isEmpty()){
                player.sendMessage(I18n.get("campaign.sectors.none"));
                return;
            }

            int page = pageOf(pageArg, presets.size);
            int pages = Math.max(1, (presets.size + pageSize - 1) / pageSize);

            StringBuilder builder = new StringBuilder(I18n.get("campaign.client.sectors.header", planet.localizedName, page + 1, pages)).append("\n");
            for(int i = page * pageSize; i < Math.min(presets.size, (page + 1) * pageSize); i++){
                builder.append(describeSector(presets.get(i))).append("\n");
            }
            builder.append(I18n.get("campaign.client.sectors.footer"));

            player.sendMessage(builder.toString());
        });

        client(handler, "research", "<name|all>", "[admin] Unlock research.", (args, player) -> {
            if(!requirePermission(player, CampaignConfig.data().researchAdminOnly)) return;
            if(!CampaignConfig.data().allowResearch){
                player.sendMessage(I18n.get("campaign.error.research-disabled"));
                return;
            }

            if("all".equalsIgnoreCase(args[0])){
                int count = ResearchService.researchAll(SectorService.planet());
                player.sendMessage(I18n.get("campaign.client.research.all", count));
                mindustry.gen.Call.sendMessage(I18n.get("campaign.client.research.all-broadcast", player.plainName()));
                return;
            }

            UnlockableContent unlockable = ResearchService.find(args[0]);
            if(unlockable == null){
                player.sendMessage(I18n.get("campaign.research.unknown", args[0]));
                return;
            }

            if(!ResearchService.shownOnPlanet(unlockable, SectorService.planet())){
                player.sendMessage(I18n.get("campaign.research.other-planet", I18n.content(unlockable)));
                return;
            }

            if(unlockable instanceof mindustry.type.SectorPreset){
                player.sendMessage(I18n.get("campaign.research.sector-preset", I18n.content(unlockable)));
                return;
            }

            if(ResearchService.autoUnlockable(unlockable.techNode)){
                player.sendMessage(I18n.get("campaign.research.auto", I18n.content(unlockable)));
                return;
            }

            String blocker = ResearchService.researchBlocker(unlockable);
            if(blocker != null){
                player.sendMessage(blocker);
                return;
            }

            boolean changed = ResearchService.research(unlockable, true);
            player.sendMessage(I18n.get(changed ? "campaign.client.research.ok" : "campaign.client.research.already", I18n.content(unlockable)));
        });

        //the status text moved into the hub; the command is the hub now (/campaign, /c)
        client(handler, "campaign-status", "Show campaign status in the chat.", (args, player) -> {
            Sector sector = SectorService.current();
            if(sector == null){
                player.sendMessage(I18n.get("campaign.client.not-campaign"));
                return;
            }

            player.sendMessage(I18n.get("campaign.client.status",
                sector.planet.localizedName,
                sector.name(),
                sector.id,
                SectorService.statusOf(sector),
                state.wave,
                Math.max(0, state.rules.winWave),
                state.rules.waves,
                ResearchService.lockedCount(sector.planet)));
        });

        client(handler, "destination", "<name|clear>", "[admin] Set the launch pad destination of this sector.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            Sector sector = SectorService.current();
            if(sector == null){
                player.sendMessage(I18n.get("campaign.client.no-sector"));
                return;
            }

            if("clear".equalsIgnoreCase(args[0])){
                SectorService.setDestination(sector, null);
                player.sendMessage(I18n.get("campaign.destination.cleared", sector.name()));
                CampaignNetwork.pushInfoToAll();
                return;
            }

            Sector destination = SectorService.resolve(args[0]);
            if(destination == null){
                player.sendMessage(I18n.get("campaign.destination.invalid", args[0]));
                return;
            }

            try{
                SectorService.setDestination(sector, destination);
                player.sendMessage(I18n.get("campaign.destination.set", sector.name(), destination.name()));
                CampaignNetwork.pushInfoToAll();
            }catch(CampaignException e){
                player.sendMessage("[scarlet]" + e.getMessage());
            }
        });

        client(handler, "redirect", "[admin] Point every exporting sector at this one.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            Sector sector = SectorService.current();
            if(sector == null){
                player.sendMessage(I18n.get("campaign.client.no-sector"));
                return;
            }

            int count = SectorService.redirectAllExports(sector);
            player.sendMessage(I18n.get("campaign.destination.redirected", count, sector.name()));
            CampaignNetwork.pushInfoToAll();
        });

        client(handler, "resources", "[planet]", "Show the total resources of a planet.", (args, player) -> {
            Planet planet = args.length > 0 ? SectorService.planetByName(args[0]) : SectorService.planet();
            if(planet == null){
                player.sendMessage(I18n.get("campaign.planet.invalid", args.length > 0 ? args[0] : "-"));
                return;
            }

            ItemSeq items = ResearchService.planetItems(planet);
            ObjectMap<Item, Float> production = ResearchService.planetProduction(planet);
            ObjectMap<Item, Float> exports = ResearchService.planetExports(planet);

            StringBuilder builder = new StringBuilder(I18n.get("campaign.resources.header", planet.localizedName, items.total));
            for(Item item : content.items()){
                int amount = items.get(item);
                float produced = production.get(item, 0f);
                float exported = exports.get(item, 0f);
                if(amount <= 0 && produced <= 0f && exported <= 0f) continue;
                builder.append("\n").append(I18n.get("campaign.resources.line", I18n.content(item), amount, produced, exported));
            }
            player.sendMessage(builder.toString());
        });

        client(handler, "sectorinfo", "<sector>", "Show storage, production and transport of a sector.", (args, player) -> {
            Sector sector = SectorService.resolve(args[0]);
            if(sector == null){
                player.sendMessage(I18n.get("campaign.msg.no-sector-hint"));
                return;
            }

            StringBuilder builder = new StringBuilder(I18n.get("campaign.sectorinfo.header",
                sector.name(), sector.id, sector.planet.localizedName));
            builder.append("\n").append(I18n.get("campaign.sectorinfo.status", SectorService.statusOf(sector),
                sector.info.storageCapacity, sector.info.items.total,
                sector.info.origin == null ? "-" : sector.info.origin.name(),
                sector.info.destination == null ? "-" : sector.info.destination.name()));

            for(Item item : content.items()){
                int amount = sector.info.items.get(item);
                float produced = sector.info.production.get(item) == null ? 0f : sector.info.production.get(item).mean * 60f;
                float exported = sector.info.export.get(item) == null ? 0f : sector.info.export.get(item).mean * 60f;
                float imported = sector.info.getImportRate(sector.planet, item) * 60f;
                if(amount <= 0 && produced <= 0f && exported <= 0f && imported <= 0f) continue;
                builder.append("\n").append(I18n.get("campaign.sectorinfo.item", I18n.content(item), amount, produced, exported, imported));
            }
            player.sendMessage(builder.toString());
        });

        client(handler, "campaignsave", "Save the current sector.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            player.sendMessage(I18n.get(SectorService.saveCurrent() ? "campaign.client.save-ok" : "campaign.client.save-failed"));
        });

        client(handler, "capture", "Force-capture the current sector.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            if(SectorService.current() == null){
                player.sendMessage(I18n.get("campaign.client.no-sector"));
                return;
            }

            try{
                mindustry.core.Logic.sectorCapture();
                player.sendMessage(I18n.get("campaign.client.capture-ok"));
            }catch(Throwable t){
                Log.err(t);
                player.sendMessage(I18n.get("campaign.error.unexpected", Strings.getSimpleMessage(t)));
            }
        });

        client(handler, "campaign", "[menu]", "Open the campaign hub: votes, admin panel and the sector browser.", (args, player) -> MainMenu.openMain(player));
        client(handler, "c", "[menu]", "Short form of /campaign.", (args, player) -> MainMenu.openMain(player));

        client(handler, "cvote", "[option] [args...]", "Vote to switch sector/planet, unlock research or rewire launch pads.", (args, player) -> handleVote(player, args));
        client(handler, "cv", "[option] [args...]", "Short form of /cvote.", (args, player) -> handleVote(player, args));

        //admins: the same actions as the console, plus the dialog panel
        client(handler, "ca", "[sub] [args...]", "[admin] Admin panel and direct actions.", (args, player) -> handleAdmin(player, args));
        client(handler, "cadmin", "[sub] [args...]", "[admin] Alias of /ca.", (args, player) -> handleAdmin(player, args));

        client(handler, "sim", "[pause|resume|toggle]", "[admin] Pause or resume the simulation.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            boolean pause = toggleSimulation(args.length > 0 ? args[0] : "toggle");
            player.sendMessage(I18n.get(pause ? "campaign.sim.paused" : "campaign.sim.resumed"));
        });

        arc.util.CommandHandler.CommandRunner<Player> skip = (args, player) -> {
            if(!requirePermission(player, true)) return;

            int count = 1;
            if(args.length > 0){
                try{
                    count = clampWaveCount(Integer.parseInt(args[0]));
                }catch(NumberFormatException e){
                    player.sendMessage("[scarlet]" + I18n.get("campaign.wave.invalid-count", args[0], MAX_SKIP_WAVES));
                    return;
                }
            }

            String problem = skipWaves(player, count);
            player.sendMessage(problem != null
                ? "[scarlet]" + problem
                : I18n.get("campaign.wave.skipped", count, state.wave));
        };

        client(handler, "cj", "[count] [admin] Skip waves (default 1).", skip);
        client(handler, "cjump", "[count] [admin] Alias of /cj.", skip);

        client(handler, "difficulty", "[name]", "[admin] Show or set the campaign difficulty.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            mindustry.game.Difficulty current = SectorService.difficulty();
            if(args.length == 0){
                StringBuilder list = new StringBuilder();
                for(mindustry.game.Difficulty value : mindustry.game.Difficulty.all){
                    if(list.length() > 0) list.append(" / ");
                    list.append(value.name());
                }
                player.sendMessage(I18n.get("campaign.difficulty.current",
                    I18n.get("campaign.difficulty." + current.name())) + "\n" + I18n.get("campaign.difficulty.list", list.toString()));
                return;
            }

            mindustry.game.Difficulty applied = SectorService.setDifficulty(args[0]);
            player.sendMessage(applied == null
                ? I18n.get("campaign.difficulty.unknown", args[0])
                : I18n.get("campaign.difficulty.set", I18n.get("campaign.difficulty." + applied.name())));
        });

        client(handler, "rule", "[name] [on|off]", "[admin] Show or toggle a campaign rule flag.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            if(args.length == 0){
                StringBuilder builder = new StringBuilder(I18n.get("campaign.rule.header"));
                for(String rule : new String[]{"invasion", "fog", "hidespawns", "randomwaveai", "rtsai", "pausedisabled", "clearonlose", "reactorexplosions"}){
                    builder.append("\n").append(I18n.get("campaign.rule.name." + rule))
                        .append(" = ").append(SectorService.currentRule(rule));
                }
                player.sendMessage(builder.toString());
                return;
            }

            if(!SectorService.isRule(args[0])){
                player.sendMessage(I18n.get("campaign.rule.unknown", args[0]));
                return;
            }

            Boolean force = args.length > 1 ? Boolean.valueOf(!("off".equalsIgnoreCase(args[1]) || "false".equalsIgnoreCase(args[1]) || "0".equals(args[1]))) : null;
            Boolean value = SectorService.toggleRule(args[0], force);
            player.sendMessage(value == null
                ? I18n.get("campaign.rule.unknown", args[0])
                : I18n.get("campaign.rule.set", args[0], value));
        });
    }

    // ---------------------------------------------------------------- vote command

    private static CampaignVote.Kind voteKind(String option){
        return switch(option){
            case "sector" -> CampaignVote.Kind.sector;
            case "planet" -> CampaignVote.Kind.planet;
            case "destination" -> CampaignVote.Kind.destination;
            case "redirect" -> CampaignVote.Kind.redirect;
            case "pause" -> CampaignVote.Kind.pause;
            case "wave", "skipwave", "cj" -> CampaignVote.Kind.wave;
            default -> CampaignVote.Kind.research;
        };
    }

    private static boolean isVoteKind(String option){
        return "sector".equals(option) || "planet".equals(option) || "research".equals(option)
            || "destination".equals(option) || "redirect".equals(option) || "pause".equals(option)
            || "wave".equals(option) || "skipwave".equals(option) || "cj".equals(option)
            || "abandon".equals(option);
    }

    /** @return true when any token is {@code help} or {@code ?}; arc puts the first one in args[0]. */
    private static boolean isHelp(String[] args){
        if(args == null) return false;

        for(String arg : args){
            if(arg == null) continue;
            String value = arg.trim();
            if("help".equalsIgnoreCase(value) || "?".equals(value)) return true;
        }

        return false;
    }
    /** Handles both {@code /cvote} and its short form {@code /cv}. */
    private static void handleVote(Player player, String[] args){
        if(!CampaignHooks.enabled){
            player.sendMessage(I18n.get("campaign.error.inactive"));
            return;
        }

        //"help" and "?" print the usage instead of an unknown-kind error
        if(isHelp(args)){
            player.sendMessage(I18n.get("campaign.vote.help"));
            return;
        }

        //arc hands the tail over as one string ("list research 钻"), so split it back into tokens
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for(int i = 1; i < args.length; i++){
            if(args[i] == null) continue;
            for(String part : args[i].trim().split("\\s+")){
                if(!part.isEmpty()) tokens.add(part);
            }
        }

        if(args.length == 0 || args[0] == null || args[0].trim().isEmpty() || "info".equalsIgnoreCase(args[0])){
            showVote(player);
            return;
        }

        String option = args[0].toLowerCase();

        if("yes".equalsIgnoreCase(option) || "y".equalsIgnoreCase(option)
            || "no".equalsIgnoreCase(option) || "n".equalsIgnoreCase(option)
            || "cancel".equalsIgnoreCase(option)){
            String problem = CampaignVote.cast(player, option, tokens.isEmpty() ? null : tokens.get(0));
            if(problem != null) player.sendMessage("[scarlet]" + problem);
            return;
        }

        //"menu" opens the native dialog front end
        if("menu".equalsIgnoreCase(option) || "m".equalsIgnoreCase(option)){
            if(!VoteMenu.available()){
                player.sendMessage(I18n.get("campaign.menu.unavailable"));
                return;
            }
            VoteMenu.openMain(player);
            return;
        }

        //"list <kind> [search]" - the searchable target list; "--all" also shows locked research
        if("list".equalsIgnoreCase(option) || "ls".equalsIgnoreCase(option)){
            boolean includeBlocked = tokens.contains("--all") || tokens.contains("-a");
            tokens.remove("--all");
            tokens.remove("-a");

            String kindArg = tokens.size() > 0 ? tokens.get(0).toLowerCase() : "sector";
            String query = tokens.size() > 1 ? joinFrom(tokens, 1) : "";
            if(!isVoteKind(kindArg)){
                player.sendMessage(I18n.get("campaign.vote.usage"));
                return;
            }
            showVoteTargets(player, voteKind(kindArg), query, includeBlocked);
            return;
        }

        if(isVoteKind(option)){
            CampaignVote.Kind kind = voteKind(option);

            //these two take no target
            if(kind == CampaignVote.Kind.pause || kind == CampaignVote.Kind.redirect
                || kind == CampaignVote.Kind.wave){
                String problem = CampaignVote.start(player, kind, tokens.isEmpty() ? "" : joinFrom(tokens, 0));
                if(problem != null) player.sendMessage("[scarlet]" + problem);
                return;
            }

            if(tokens.isEmpty()){
                //no target: show what can be voted for instead of a bare error
                player.sendMessage(I18n.get("campaign.vote.target-required"));
                showVoteTargets(player, kind, "", false);
                return;
            }

            String problem = CampaignVote.start(player, kind, joinFrom(tokens, 0));
            if(problem != null) player.sendMessage("[scarlet]" + problem);
            return;
        }

        player.sendMessage(I18n.get("campaign.vote.usage"));
    }

    /** Handles both {@code /ca} and {@code /cadmin}. */
    private static void handleAdmin(Player player, String[] args){
        if(!requirePermission(player, true)) return;

        //"help" and "?" print the subcommand list
        if(isHelp(args)){
            player.sendMessage(I18n.get("campaign.admin.help"));
            return;
        }

        java.util.List<String> tokens = new java.util.ArrayList<>();
        for(int i = 1; i < args.length; i++){
            if(args[i] == null) continue;
            for(String part : args[i].trim().split("\\s+")){
                if(!part.isEmpty()) tokens.add(part);
            }
        }

        String sub = args.length == 0 || args[0] == null || args[0].trim().isEmpty() ? "menu" : args[0].toLowerCase();
        String rest = joinFrom(tokens, 0);

        try{
            switch(sub){
                case "menu", "m", "panel" -> AdminPanel.openMain(player);

                case "research", "res" -> {
                    if(rest.isEmpty()){
                        player.sendMessage(I18n.get("campaign.admin.usage"));
                        return;
                    }
                    if("all".equalsIgnoreCase(rest)){
                        int count = ResearchService.researchAll(SectorService.planet());
                        player.sendMessage(I18n.get("campaign.client.research.all", count));
                        return;
                    }
                    UnlockableContent content = ResearchService.find(rest);
                    if(content == null){
                        player.sendMessage(I18n.get("campaign.research.unknown", rest));
                        return;
                    }
                    boolean changed = ResearchService.research(content, true);
                    player.sendMessage(I18n.get(changed
                        ? "campaign.client.research.ok" : "campaign.client.research.already", I18n.content(content)));
                }

                case "sector", "s" -> {
                    Sector sector = requireSector(rest);
                    SectorService.enter(player, sector, false, true);
                }

                case "planet", "p" -> {
                    Planet planet = SectorService.planetByName(rest);
                    Sector target = planet == null ? null : SectorService.targetFor(planet);
                    if(target == null){
                        player.sendMessage(I18n.get("campaign.planet.empty", rest));
                        return;
                    }
                    SectorService.enter(target, false, true);
                }

                case "destination", "dest", "pad" -> {
                    Sector current = SectorService.current();
                    if(current == null){
                        player.sendMessage(I18n.get("campaign.client.no-sector"));
                        return;
                    }
                    if(rest.isEmpty() || "clear".equalsIgnoreCase(rest)){
                        SectorService.setDestination(current, null);
                        player.sendMessage(I18n.get("campaign.destination.cleared", current.name()));
                        return;
                    }
                    Sector destination = requireSector(rest);
                    SectorService.setDestination(current, destination);
                    player.sendMessage(I18n.get("campaign.destination.set", current.name(), destination.name()));
                }

                case "redirect" -> {
                    Sector current = SectorService.current();
                    if(current == null){
                        player.sendMessage(I18n.get("campaign.client.no-sector"));
                        return;
                    }
                    int count = SectorService.redirectAllExports(current);
                    player.sendMessage(I18n.get("campaign.destination.redirected", count, current.name()));
                }

                case "pause", "sim" -> {
                    boolean paused = toggleSimulation(rest.isEmpty() ? "toggle" : rest);
                    player.sendMessage(I18n.get(paused ? "campaign.sim.paused" : "campaign.sim.resumed"));
                }

                case "difficulty", "diff" -> {
                    mindustry.game.Difficulty applied = rest.isEmpty() ? null : SectorService.setDifficulty(rest);
                    if(rest.isEmpty()){
                        player.sendMessage(I18n.get("campaign.difficulty.current",
                            I18n.get("campaign.difficulty." + SectorService.difficulty().name())));
                    }else{
                        player.sendMessage(applied == null
                            ? I18n.get("campaign.difficulty.unknown", rest)
                            : I18n.get("campaign.difficulty.set", I18n.get("campaign.difficulty." + applied.name())));
                    }
                }

                case "rule" -> {
                    if(rest.isEmpty() || !SectorService.isRule(tokens.get(0))){
                        StringBuilder builder = new StringBuilder(I18n.get("campaign.rule.header"));
                        for(String rule : new String[]{"invasion", "fog", "hidespawns", "randomwaveai", "rtsai", "pausedisabled", "clearonlose", "reactorexplosions"}){
                            builder.append("\n").append(I18n.get("campaign.rule.name." + rule))
                                .append(" = ").append(SectorService.currentRule(rule));
                        }
                        player.sendMessage(builder.toString());
                        return;
                    }

                    Boolean force = tokens.size() > 1
                        ? Boolean.valueOf(!("off".equalsIgnoreCase(tokens.get(1)) || "0".equals(tokens.get(1)))) : null;
                    Boolean value = SectorService.toggleRule(tokens.get(0), force);
                    player.sendMessage(value == null
                        ? I18n.get("campaign.rule.unknown", tokens.get(0))
                        : I18n.get("campaign.rule.set", tokens.get(0), value));
                }

                                case "vote" -> handleVoteConfig(player, tokens);

                case "save" -> player.sendMessage(I18n.get(SectorService.saveCurrent()
                    ? "campaign.client.save-ok" : "campaign.client.save-failed"));

                case "capture" -> {
                    mindustry.core.Logic.sectorCapture();
                    player.sendMessage(I18n.get("campaign.client.capture-ok"));
                }

                default -> player.sendMessage(I18n.get("campaign.admin.usage"));
            }
        }catch(CampaignException e){
            player.sendMessage("[scarlet]" + e.getMessage());
        }catch(Throwable t){
            Log.err(t);
            player.sendMessage(I18n.get("campaign.error.unexpected", Strings.getSimpleMessage(t)));
        }
    }

    /** {@code /ca vote <key> <value>} — the same numbers the panel adjusts. */
    private static void handleVoteConfig(Player player, java.util.List<String> tokens){
        CampaignConfig config = CampaignConfig.data();

        if(tokens.isEmpty()){
            player.sendMessage(I18n.get("campaign.admin.vote-state",
                (int)config.voteDuration, (int)config.voteTimeout, (int)config.voteCooldown,
                (int)(config.votePercent * 100), config.voteMinPlayers, config.voteDecision));
            return;
        }

        String key = tokens.get(0).toLowerCase();

        if(tokens.size() < 2){
            player.sendMessage(I18n.get("campaign.admin.usage"));
            return;
        }

        String raw = tokens.get(1);
        try{
            switch(key){
                case "window", "duration" -> config.voteDuration = Float.parseFloat(raw);
                case "timeout" -> config.voteTimeout = Float.parseFloat(raw);
                case "cooldown" -> config.voteCooldown = Float.parseFloat(raw);
                case "percent" -> config.votePercent = Float.parseFloat(raw) > 1f
                    ? Float.parseFloat(raw) / 100f : Float.parseFloat(raw);
                case "minplayers", "min" -> config.voteMinPlayers = Integer.parseInt(raw);
                case "decision" -> config.voteDecision = raw.toLowerCase();
                default -> {
                    player.sendMessage(I18n.get("campaign.admin.usage"));
                    return;
                }
            }
        }catch(NumberFormatException e){
            player.sendMessage(I18n.get("campaign.admin.usage"));
            return;
        }

        CampaignConfig.save();
        player.sendMessage(I18n.get("campaign.admin.vote-state",
            (int)config.voteDuration, (int)config.voteTimeout, (int)config.voteCooldown,
            (int)(config.votePercent * 100), config.voteMinPlayers, config.voteDecision));
    }

    /** Joins the tokens from {@code start} with single spaces (names may contain spaces). */
    private static String joinFrom(java.util.List<String> tokens, int start){        StringBuilder builder = new StringBuilder();
        for(int i = start; i < tokens.size(); i++){
            if(builder.length() > 0) builder.append(' ');
            builder.append(tokens.get(i));
        }
        return builder.toString();
    }

    private static void showVoteTargets(Player player, CampaignVote.Kind kind, String query, boolean includeBlocked){
        Seq<String> found = CampaignVote.search(kind, query, 20, includeBlocked);

        String kindName = I18n.get("campaign.vote.kind." + kind.name());
        if(found.isEmpty()){
            player.sendMessage(I18n.get("campaign.vote.list-empty", kindName, query));
            return;
        }

        StringBuilder builder = new StringBuilder(I18n.get("campaign.vote.list-header", kindName, found.size));
        if(includeBlocked) builder.append(I18n.get("campaign.vote.list-blocked-hint"));
        for(String entry : found){
            builder.append("\n[white]  ").append(entry);
        }
        builder.append("\n").append(I18n.get("campaign.vote.list-footer", kind.name()));
        player.sendMessage(builder.toString());
    }

    // ---------------------------------------------------------------- helpers

    private static void showVote(Player player){
        CampaignVote.Vote vote = CampaignVote.active();
        if(vote == null){
            player.sendMessage(I18n.get("campaign.vote.info-none"));
            return;
        }

        player.sendMessage(I18n.get("campaign.vote.info", vote.starterName, CampaignVote.describe(vote),
            vote.yes.size, CampaignVote.requiredVotes(), vote.secondsLeft()));
    }

    /** @return true when the simulation is now paused. */
    private static boolean toggleSimulation(String action){
        boolean pause;
        if("pause".equalsIgnoreCase(action) || "on".equalsIgnoreCase(action)){
            pause = true;
        }else if("resume".equalsIgnoreCase(action) || "play".equalsIgnoreCase(action) || "off".equalsIgnoreCase(action)){
            pause = false;
        }else{
            pause = !state.isPaused();
        }

        state.set(pause ? mindustry.core.GameState.State.paused : mindustry.core.GameState.State.playing);
        top.colorgarden.core.CampaignHooks.setPauseIntent(pause);
        return pause;
    }

    /** Cooldown end per player uuid ("-" for the console). */
    private static final arc.struct.ObjectMap<String, Long> waveSkipUntil = new arc.struct.ObjectMap<>();

    /**
     * Mirrors {@code HudFragment#canSkipWave}: waves have to be enabled and sending, no enemy may be
     * alive and the spawner must not be busy. Skipping past the sector's win wave is refused as well,
     * because the capture check would resolve the sector immediately.
     *
     * @return null when skipping is allowed, otherwise the reason shown to the player.
     */
    static @Nullable String skipBlocked(){
        if(!mindustry.Vars.state.rules.waves) return I18n.get("campaign.wave.no-waves");
        if(!mindustry.Vars.state.rules.waveSending) return I18n.get("campaign.wave.sending-off");
        if(mindustry.Vars.state.enemies > 0) return I18n.get("campaign.wave.enemies-left", mindustry.Vars.state.enemies);
        if(mindustry.Vars.spawner != null && mindustry.Vars.spawner.isSpawning()) return I18n.get("campaign.wave.spawning");

        if(mindustry.Vars.state.rules.winWave > 0 && mindustry.Vars.state.wave >= mindustry.Vars.state.rules.winWave){
            return I18n.get("campaign.wave.at-limit", mindustry.Vars.state.rules.winWave);
        }

        return null;
    }
    /** Upper bound for one skip action; more waves at once would flood the sector with enemies. */
    public static final int MAX_SKIP_WAVES = 50;

    /** @return the requested count clamped to a sane range. */
    public static int clampWaveCount(int count){
        return Math.max(1, Math.min(MAX_SKIP_WAVES, count));
    }

    /**
     * @return how many waves may still be skipped before the sector's win wave, or
     *         {@link #MAX_SKIP_WAVES} when the map has no win wave (endless / numbered sectors).
     *
     * <p>{@code rules.winWave} is the wave the sector is won on; vanilla resolves the capture as soon as
     * the wave counter reaches it. Skipping past it would end the sector, so the remaining distance is
     * the real limit of a skip.</p>
     */
    public static int remainingWaves(){
        int win = state.rules == null ? 0 : state.rules.winWave;
        if(win <= 0) return MAX_SKIP_WAVES;
        return Math.max(0, win - state.wave);
    }

    /** @return null when {@code count} waves fit before the win wave, otherwise the reason. */
    public static @Nullable String waveLimitProblem(int count){
        int limit = remainingWaves();
        if(count <= limit) return null;

        return I18n.get("campaign.wave.would-win", count, state.wave, state.rules.winWave, limit);
    }

    /**
     * Skips exactly one wave, see {@link #skipWaves}.
     *
     * @return null on success, otherwise a player-readable reason.
     */
    static @Nullable String skipOneWave(@Nullable Player player){
        return skipWaves(player, 1);
    }

    /**
     * Skips {@code count} waves with the vanilla routine ({@code Logic#skipWave}, the same call the
     * campaign's skip-wave button makes), honouring {@code skipWaveCooldown} once for the whole action.
     *
     * @return null on success, otherwise a player-readable reason.
     */
    static @Nullable String skipWaves(@Nullable Player player, int count){
        String key = player == null ? "-" : player.uuid();
        long now = arc.util.Time.millis();

        Long until = waveSkipUntil.get(key);
        if(until != null && now < until){
            return I18n.get("campaign.wave.cooldown", (until - now) / 1000 + 1);
        }

        if(!state.isGame()) return I18n.get("campaign.msg.not-playing");

        //same conditions as vanilla's skip button (HudFragment#canSkipWave)
        String blocked = skipBlocked();
        if(blocked != null) return blocked;

        int waves = clampWaveCount(count);

        //a skip must never run past the wave the sector is won on
        String overLimit = waveLimitProblem(waves);
        if(overLimit != null) return overLimit;

        for(int i = 0; i < waves; i++){
            mindustry.Vars.logic.skipWave();
        }

        waveSkipUntil.put(key, now + (long)(CampaignConfig.data().skipWaveCooldown * 1000f));
        return null;
    }

    private static Sector requireSector(String query) throws CampaignException{
        Sector sector = SectorService.resolve(query);
        if(sector == null){
            throw new CampaignException(I18n.get("campaign.error.no-such-sector", query));
        }
        return sector;
    }

    /** Starts the live accumulator probe (sampled from the tick hook, so pausing cannot stall it). */
    private static void probeLogic(){
        info("campaign.logic.probe.start", CampaignHooks.logicCounts()[0]);
        CampaignHooks.startLogicProbe();
    }
    /** Prints the remaining research cost, so the operator knows what the planet still needs. */
    private static void infoCost(UnlockableContent content){
        Seq<ItemStack> missing = ResearchService.missing(content);
        if(!missing.isEmpty()){
            info("campaign.research.cost", formatStacks(missing));
        }
    }

    private static String formatStacks(Seq<ItemStack> stacks){
        if(stacks.isEmpty()) return "-";

        StringBuilder builder = new StringBuilder();
        for(ItemStack stack : stacks){
            if(builder.length() > 0) builder.append(", ");
            builder.append(I18n.content(stack.item)).append(" x").append(stack.amount);
        }
        return builder.toString();
    }

    /** console commands bypass the unlock gate; admins only bypass it when configured */
    private static boolean canBypass(Player player){
        return player != null && player.admin && CampaignConfig.data().adminBypassLock;
    }

    /** @return whether a sector may be entered, without switching to it. */
    private static boolean checkEnterable(Sector sector){
        return !CampaignConfig.data().enforceSectorUnlock || SectorService.canEnter(sector);
    }

    private static void warnIfLocked(Sector sector){
        if(sector != null && !checkEnterable(sector)){
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.forced-locked", sector.name(), SectorService.lockReason(sector)));
        }
    }

    private static boolean requirePermission(Player player, boolean adminOnly){
        if(!CampaignHooks.enabled){
            player.sendMessage(I18n.get("campaign.error.inactive"));
            return false;
        }
        if(adminOnly && !player.admin){
            player.sendMessage(I18n.get("campaign.error.admin-only"));
            return false;
        }
        return true;
    }

    //status ids are stable API values; the display text is localized
    private static String statusText(String id){
        String key = "campaign.status." + id;
        return I18n.has(key) ? I18n.get(key) : id;
    }

    private static int pageOf(String value, int total){
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        if(value == null || !Strings.canParsePositiveInt(value)) return 0;
        return Math.max(0, Math.min(Strings.parseInt(value) - 1, pages - 1));
    }

    private static String describeSector(SectorPreset preset){
        Sector sector = preset.sector;
        String status = SectorService.statusOf(sector);

        String color = switch(status){
            case "playing" -> "[accent]";
            case "captured", "cleared" -> "[green]";
            case "base" -> "[yellow]";
            case "available" -> "[lightgray]";
            default -> "[gray]";
        };

        return color + I18n.get("campaign.sectors.line",
            sector == null ? "?" : sector.id, I18n.content(preset), status, preset.captureWave);
    }

    //the console has no color codes, so markers are stripped for log output
    private static void info(String key, Object... args){
        infoRaw(I18n.get(key, args));
    }

    private static void infoHelp(String key){
        infoRaw("  " + I18n.get(key));
    }

    private static void infoRaw(String text){
        Log.info("[CampaignServer] @", Strings.stripColors(text));
    }

    private static void err(String keyOrText, Object... args){
        String text = I18n.has(keyOrText) ? I18n.get(keyOrText, args) : keyOrText;
        Log.err("[CampaignServer] " + Strings.stripColors(text));
    }
}
