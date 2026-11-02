// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.ResearchService;
import top.colorgarden.core.SectorService;
import top.colorgarden.net.CampaignProtocol;
import top.colorgarden.vote.CampaignVote;


import arc.util.serialization.Json;
import arc.util.serialization.JsonWriter;
import mindustry.gen.Groups;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;

import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * Snapshot of the campaign state, sent to clients as JSON.
 *
 * <p>Kept as plain public fields with an array for the sector list so that libgdx's
 * {@link Json} can serialize and deserialize it without any custom mapping.</p>
 */
public class CampaignInfo{
    private static final Json json = new Json();
    static{
        json.setUsePrototypes(false);
        json.setOutputType(JsonWriter.OutputType.json);
    }

    public int protocol = CampaignProtocol.version;
    /** Whether the server is currently hosting a campaign sector. */
    public boolean campaign;
    public String planet = "";
    public String planetName = "";

    /** Currently played sector; -1 when nothing is hosted. */
    public int sectorId = -1;
    public String sectorName = "";
    public String presetName = "";
    /** playing / captured / base / cleared / available / locked / unknown */
    public String status = "unknown";
    public boolean captured;
    public boolean hasBase;
    public boolean waves;
    public int wave;
    public int winWave;
    public int worldWidth;
    public int worldHeight;
    public int cores;
    public int players;
    public int lockedResearch;
    public String saveFile = "";

    /** Whether the requesting player may use the mutating actions. */
    public boolean admin;
    public boolean allowSwitch;
    public boolean allowResearch;

    /** Running vote, if any: "" / "sector" / "research". */
    public String voteKind = "";
    public String voteTarget = "";
    public String voteStarter = "";
    public int voteYes;
    public int voteNo;
    public int voteRequired;
    public int voteSecondsLeft;

    /** Sector list; empty when only a single sector was requested. */
    public Entry[] sectors = new Entry[0];

    public String toJson(){
        return json.toJson(this);
    }

    public static CampaignInfo fromJson(String text){
        if(text == null || text.isEmpty()) return null;
        try{
            return json.fromJson(CampaignInfo.class, text);
        }catch(Throwable t){
            return null;
        }
    }

    /**
     * Builds an info snapshot on the server.
     *
     * @param selector empty/{@code all} for the current sector plus the planet's sector list,
     *                 otherwise a sector query as accepted by {@link SectorService#resolve(String)}.
     */
    public static CampaignInfo build(String selector){
        CampaignInfo info = new CampaignInfo();
        info.campaign = state.isGame() && state.rules != null && state.rules.sector != null;
        info.allowSwitch = CampaignConfig.data().allowSectorSwitch;
        info.allowResearch = CampaignConfig.data().allowResearch;
        info.players = Groups.player.size();

        Planet planet = SectorService.planet();
        if(planet != null){
            info.planet = planet.name;
            info.planetName = planet.localizedName;
        }

        Sector current = SectorService.current();
        boolean list = selector == null || selector.trim().isEmpty() || "all".equalsIgnoreCase(selector.trim());

        Sector target = current;
        if(!list){
            target = SectorService.resolve(selector);
        }

        if(target != null){
            fill(info, target, list || target == current);
        }

        CampaignVote.Vote vote = CampaignVote.active();
        if(vote != null){
            info.voteKind = vote.kind == CampaignVote.Kind.sector ? "sector" : "research";
            info.voteTarget = vote.label;
            info.voteStarter = vote.starterName;
            info.voteYes = vote.yes.size;
            info.voteNo = vote.no.size;
            info.voteRequired = CampaignVote.requiredVotes();
            info.voteSecondsLeft = vote.secondsLeft();
        }

        if(list && planet != null){
            SectorPreset[] presets = SectorService.presets(planet).toArray(SectorPreset.class);
            Entry[] entries = new Entry[presets.length];
            for(int i = 0; i < presets.length; i++){
                entries[i] = entry(presets[i]);
            }
            info.sectors = entries;
        }

        return info;
    }

    private static void fill(CampaignInfo info, Sector sector, boolean withDetails){
        info.sectorId = sector.id;
        info.sectorName = sector.name();
        info.presetName = sector.preset == null ? "" : sector.preset.name;
        info.status = SectorService.statusOf(sector);
        info.captured = sector.info != null && sector.info.wasCaptured;
        info.hasBase = sector.hasBase();
        info.lockedResearch = ResearchService.lockedCount(sector.planet);
        info.saveFile = SectorService.sectorFile(sector).name();

        if(withDetails){
            info.waves = state.rules != null && state.rules.waves;
            info.wave = state.wave;
            info.winWave = state.rules == null ? 0 : state.rules.winWave;
            info.worldWidth = world.width();
            info.worldHeight = world.height();
            info.cores = state.teams.playerCores().size;
        }
    }

    private static Entry entry(SectorPreset preset){
        Sector sector = preset.sector;
        Entry entry = new Entry();
        entry.preset = preset.name;
        entry.captureWave = preset.captureWave;
        if(sector != null){
            entry.id = sector.id;
            entry.name = sector.name();
            entry.status = SectorService.statusOf(sector);
            entry.captured = sector.info != null && sector.info.wasCaptured;
            entry.hasBase = sector.hasBase();
            entry.locked = sector.locked();
        }
        return entry;
    }

    /** One row of the sector list. */
    public static class Entry{
        public int id;
        public String preset = "";
        public String name = "";
        public String status = "unknown";
        public boolean captured;
        public boolean hasBase;
        public boolean locked;
        public int captureWave;

        public Entry(){
        }
    }
}
