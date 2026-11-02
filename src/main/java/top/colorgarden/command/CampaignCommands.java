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


import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
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
            infoHelp("campaign.help.config");
            infoHelp("campaign.help.reload");
            infoHelp("campaign.help.vote");
            infoHelp("campaign.help.sectorarg");
        });

        handler.register("campaign-host", "[sector]", "Host the campaign, starting at a sector.", args -> {
            if(state.isGame()){
                err("campaign.msg.already-hosting");
                return;
            }

            try{
                Sector sector = SectorService.resolve(args.length > 0 ? args[0] : null);
                SectorService.enter(sector);
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
                SectorService.enter(sector);
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
                SectorService.enter(sector, true);
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
            info("campaign.status.research-locked", ResearchService.lockedCount(sector.planet));
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

        handler.register("campaign-sectors", "[page]", "List sector presets for the campaign planet.", args -> {
            Planet planet = SectorService.planet();
            Seq<SectorPreset> presets = SectorService.presets(planet);

            if(presets.isEmpty()){
                err("campaign.sectors.none");
                return;
            }

            int page = pageOf(args.length > 0 ? args[0] : null, presets.size);
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

            boolean changed = ResearchService.research(unlockable, true);
            info(changed ? "campaign.research.ok" : "campaign.research.already", unlockable.localizedName);
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
            info("campaign.research.relocked", unlockable.localizedName);
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

        handler.register("campaign-config", "Show the plugin configuration.", args -> {
            CampaignConfig config = CampaignConfig.data();
            info("campaign.config.campaign", config.planet, config.sector, config.autoStart);
            info("campaign.config.saving", config.autosave, config.autosaveSeconds, config.saveOnExit, config.clearSectorOnLose);
            info("campaign.config.permissions", config.allowSectorSwitch, config.sectorSwitchAdminOnly, config.allowResearch, config.researchAdminOnly);
            info("campaign.config.voting", config.voting, config.voteSector, config.voteResearch, config.votePercent, config.voteMinPlayers, config.voteDuration);
            info("campaign.config.file", CampaignConfig.location() == null ? "-" : CampaignConfig.location().path());
        });

        handler.register("campaign-reload", "Reload config.json.", args -> {
            CampaignConfig.load();
            info("campaign.config.reloaded");
        });
    }

    // ---------------------------------------------------------------- in-game

    public static void registerClient(CommandHandler handler){
        handler.<Player>register("sector", "<name|id>", "[admin] Switch the campaign sector.", (args, player) -> {
            if(!requirePermission(player, CampaignConfig.data().sectorSwitchAdminOnly)) return;
            if(!CampaignConfig.data().allowSectorSwitch){
                player.sendMessage(I18n.get("campaign.error.switch-disabled"));
                return;
            }

            try{
                Sector sector = requireSector(args[0]);
                player.sendMessage(I18n.get("campaign.client.switching", sector.name()));
                SectorService.enter(sector);
            }catch(CampaignException e){
                player.sendMessage("[scarlet]" + e.getMessage());
            }catch(Throwable t){
                Log.err(t);
                player.sendMessage(I18n.get("campaign.error.unexpected", Strings.getSimpleMessage(t)));
            }
        });

        handler.<Player>register("sectors", "[page]", "List campaign sectors.", (args, player) -> {
            Planet planet = SectorService.planet();
            Seq<SectorPreset> presets = SectorService.presets(planet);

            if(presets.isEmpty()){
                player.sendMessage(I18n.get("campaign.sectors.none"));
                return;
            }

            int page = pageOf(args.length > 0 ? args[0] : null, presets.size);
            int pages = Math.max(1, (presets.size + pageSize - 1) / pageSize);

            StringBuilder builder = new StringBuilder(I18n.get("campaign.client.sectors.header", planet.localizedName, page + 1, pages)).append("\n");
            for(int i = page * pageSize; i < Math.min(presets.size, (page + 1) * pageSize); i++){
                builder.append(describeSector(presets.get(i))).append("\n");
            }
            builder.append(I18n.get("campaign.client.sectors.footer"));

            player.sendMessage(builder.toString());
        });

        handler.<Player>register("research", "<name|all>", "[admin] Unlock research.", (args, player) -> {
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

            boolean changed = ResearchService.research(unlockable, true);
            player.sendMessage(I18n.get(changed ? "campaign.client.research.ok" : "campaign.client.research.already", unlockable.localizedName));
        });

        handler.<Player>register("campaign", "Show campaign status.", (args, player) -> {
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

        handler.<Player>register("campaignsave", "Save the current sector.", (args, player) -> {
            if(!requirePermission(player, true)) return;

            player.sendMessage(I18n.get(SectorService.saveCurrent() ? "campaign.client.save-ok" : "campaign.client.save-failed"));
        });

        handler.<Player>register("capture", "Force-capture the current sector.", (args, player) -> {
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

        handler.<Player>register("cvote", "[sector|research|yes|no|cancel|info] [target...]", "Vote to switch the sector or unlock research.", (args, player) -> {
            if(!CampaignHooks.enabled){
                player.sendMessage(I18n.get("campaign.error.inactive"));
                return;
            }

            if(args.length == 0 || "info".equalsIgnoreCase(args[0])){
                showVote(player);
                return;
            }

            String option = args[0].toLowerCase();
            if("yes".equalsIgnoreCase(option) || "no".equalsIgnoreCase(option) || "cancel".equalsIgnoreCase(option)){
                String problem = CampaignVote.cast(player, option);
                if(problem != null) player.sendMessage("[scarlet]" + problem);
                return;
            }

            if("sector".equalsIgnoreCase(option) || "research".equalsIgnoreCase(option)){
                if(args.length < 2){
                    player.sendMessage(I18n.get("campaign.vote.target-required"));
                    return;
                }

                CampaignVote.Kind kind = "sector".equalsIgnoreCase(option) ? CampaignVote.Kind.sector : CampaignVote.Kind.research;
                String problem = CampaignVote.start(player, kind, args[1]);
                if(problem != null) player.sendMessage("[scarlet]" + problem);
                return;
            }

            player.sendMessage(I18n.get("campaign.vote.usage"));
        });
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

    private static Sector requireSector(String query) throws CampaignException{
        Sector sector = SectorService.resolve(query);
        if(sector == null){
            throw new CampaignException(I18n.get("campaign.error.no-such-sector", query));
        }
        return sector;
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
            sector == null ? "?" : sector.id, preset.localizedName, status, preset.captureWave);
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
