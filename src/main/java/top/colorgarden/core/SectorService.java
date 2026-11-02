// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.util.CampaignException;
import top.colorgarden.util.I18n;




import arc.Events;
import arc.Core;
import arc.assets.AssetManager;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import mindustry.content.Planets;
import mindustry.core.GameState.State;
import mindustry.ctype.ContentType;
import mindustry.game.EventType.*;
import mindustry.game.Saves;
import mindustry.game.Saves.SaveSlot;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.maps.Map;
import mindustry.maps.generators.FileMapGenerator;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;
import mindustry.world.WorldParams;

import java.util.Locale;

import static mindustry.Vars.*;

/**
 * Owns the campaign life cycle on the server: picking sectors, generating/loading them,
 * saving them back to disk and re-syncing connected clients.
 *
 * <p>Vanilla Mindustry never runs the campaign on a dedicated server: {@code Vars.control}
 * (and therefore the {@code Saves} manager used for sector slots) does not exist in headless
 * mode, and {@code Logic#sectorCapture()} explicitly skips saving. This class reimplements
 * the missing pieces with server-only APIs.</p>
 */
public class SectorService{
    /** Only used to create {@link SaveSlot} instances; sector save files are written directly. */
    public static @Nullable Saves saves;
    private static boolean initialized;

    public static void init(){
        if(initialized) return;
        initialized = true;

        saves = createSaves();

        scanSaves();
    }

    /**
     * Headless Arc never creates {@code Core.assets}, and the {@code Saves} constructor registers a
     * texture loader on it. A throwaway {@link AssetManager} is supplied for the few microseconds
     * the constructor needs, then the field is restored.
     */
    private static @Nullable Saves createSaves(){
        try{
            return new Saves();
        }catch(Throwable first){
            if(Core.assets != null){
                Log.err("[CampaignServer] " + I18n.get("campaign.log.save-manager-failed"), first);
                return null;
            }
        }

        AssetManager previous = Core.assets;
        Core.assets = new AssetManager();
        try{
            Saves created = new Saves();
            Log.info("[CampaignServer] " + I18n.get("campaign.log.save-manager-headless"));
            return created;
        }catch(Throwable second){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.save-manager-failed"), second);
            return null;
        }finally{
            Core.assets = previous;
        }
    }

    // ---------------------------------------------------------------- state helpers

    /** @return the planet used by the campaign; never null once content is loaded. */
    public static @Nullable Planet planet(){
        Object found = content.getByName(ContentType.planet, CampaignConfig.data().planet);
        if(found instanceof Planet p && p.sectors != null && p.sectors.size > 0){
            return p;
        }
        return Planets.serpulo;
    }

    /** @return every sector preset that belongs to this planet. */
    public static Seq<SectorPreset> presets(@Nullable Planet planet){
        Planet target = planet == null ? planet() : planet;
        Seq<SectorPreset> result = new Seq<>();
        if(target == null) return result;

        for(SectorPreset preset : content.sectors()){
            if(preset != null && preset.planet == target && preset.sector != null){
                result.add(preset);
            }
        }
        return result;
    }

    /** @return the sector currently being played, if any. */
    public static @Nullable Sector current(){
        return state.rules == null ? null : state.rules.sector;
    }

    /** @return whether a campaign sector is currently being played. */
    public static boolean playing(){
        return state.isGame() && state.rules != null && state.rules.sector != null;
    }

    /** @return the sector to use when no explicit sector is given. */
    public static @Nullable Sector defaultSector(){
        Planet planet = planet();
        if(planet == null) return null;

        int id = CampaignConfig.data().sector;
        if(id >= 0 && id < planet.sectors.size){
            Sector sector = planet.sectors.get(id);
            if(sector != null) return sector;
        }

        Sector last = planet.getLastSector();
        return last != null ? last : planet.getStartSector();
    }

    /**
     * Resolves a sector by preset name, by sector id ({@code 170} or {@code id:170}), by its in-game
     * display name, or by a planet-qualified name such as {@code erekir:onset}.
     */
    public static @Nullable Sector resolve(@Nullable String query){
        Planet planet = planet();
        if(planet == null) return null;
        if(query == null || query.trim().isEmpty()) return defaultSector();

        String raw = Strings.stripColors(query).trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        //optional "planet:" prefix
        int colon = lower.indexOf(':');
        if(colon > 0){
            String prefix = lower.substring(0, colon);
            Object found = "id".equals(prefix) ? null : content.getByName(ContentType.planet, prefix);
            if(found instanceof Planet other && other.sectors != null && other.sectors.size > 0){
                planet = other;
                lower = lower.substring(colon + 1);
                raw = raw.substring(raw.indexOf(':') + 1);
            }
        }

        if(lower.startsWith("id:")){
            lower = lower.substring(3);
        }

        SectorPreset preset = content.sector(lower);
        if(preset != null && preset.planet == planet && preset.sector != null){
            return preset.sector;
        }

        if(Strings.canParsePositiveInt(lower)){
            int id = Strings.parseInt(lower);
            if(id >= 0 && id < planet.sectors.size){
                return planet.sectors.get(id);
            }
        }

        for(SectorPreset other : presets(planet)){
            if(other.localizedName != null && other.localizedName.equalsIgnoreCase(raw)) return other.sector;
            if(other.name.equalsIgnoreCase(raw)) return other.sector;
        }

        for(Sector sector : planet.sectors){
            if(sector != null && sector.name() != null && sector.name().equalsIgnoreCase(raw)){
                return sector;
            }
        }

        return null;
    }

    /** @return a short, human readable description of a sector's progress. */
    public static String statusOf(@Nullable Sector sector){
        if(sector == null) return "unknown";
        if(sector == current() && playing()) return "playing";
        if(sector.hasBase()) return sector.info.wasCaptured ? "captured" : "base";
        if(sector.info != null && sector.info.wasCaptured) return "cleared";
        if(sector.unlocked()) return "available";
        return "locked";
    }

    // ---------------------------------------------------------------- life cycle

    /** Saves the current sector (if any), then loads or generates the given one and hosts it. */
    public static void enter(@Nullable Sector sector) throws CampaignException{
        enter(sector, false);
    }

    /**
     * @param fresh if true, the sector save data is discarded and the sector is generated again.
     */
    public static void enter(@Nullable Sector sector, boolean fresh) throws CampaignException{
        if(net.client()){
            throw new CampaignException(I18n.get("campaign.msg.server-only"));
        }
        if(sector == null){
            throw new CampaignException(I18n.get("campaign.msg.no-sector-hint"));
        }

        Sector previous = current();
        if(previous != null && !(fresh && previous == sector)){
            saveCurrent();
        }

        try{
            startSector(sector, fresh);
        }catch(CampaignException e){
            //don't leave the server with an empty world if the new sector cannot be opened
            if(previous != null && previous != sector){
                Log.warn("[CampaignServer] " + I18n.get("campaign.log.switch-failed-recover", sector.name(), previous.name()));
                try{
                    startSector(previous, false);
                }catch(Throwable ignored){
                    Log.err("[CampaignServer] " + I18n.get("campaign.log.recover-failed"), ignored);
                }
            }
            throw e;
        }

        //the API tag has to be in the rules before the world is sent to any client
        CampaignNetwork.updateServerTag();

        if(!net.server()){
            netServer.openServer();
        }else if(Groups.player.size() > 0 && CampaignConfig.data().resyncClients){
            resyncAll();
        }

        //tell API clients (client mods) about the new sector
        CampaignNetwork.pushInfoToAll();
    }

    /** Loads the sector save or generates the sector, then makes it the active one. */
    private static void startSector(Sector sector, boolean fresh) throws CampaignException{
        logic.reset();

        boolean loaded = !fresh && loadSectorSave(sector);

        //a sector whose core is gone cannot continue; vanilla restarts it with a fresh core
        if(loaded && state.rules.defaultTeam.cores().isEmpty()){
            Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-no-core", sector.name()));
            logic.reset();
            loaded = false;
        }

        if(!loaded){
            if(fresh){
                deleteSaveData(sector);
            }
            createSector(sector);
        }

        sector.planet.setLastSector(sector);
        CampaignConfig.data().planet = sector.planet.name;
        CampaignConfig.data().sector = sector.id;
        CampaignConfig.save();

        state.set(State.playing);
    }

    /** Generates a brand new sector and starts playing it. */
    private static void createSector(Sector sector) throws CampaignException{
        SectorPreset preset = sector.preset;
        if(preset != null){
            try{
                preset.quietUnlock();
            }catch(Throwable ignored){
                //unlocking the preset is only cosmetic
            }
        }

        ensureGenerator(sector);

        WorldParams params = new WorldParams();
        //re-place the core where the old base was, like vanilla does when a sector is retried
        if(sector.info.spawnPosition != 0){
            params.corePositionOverride = sector.info.spawnPosition;
        }

        try{
            world.loadSector(sector, params);
        }catch(Throwable t){
            logic.reset();
            throw new CampaignException(I18n.get("campaign.error.generate-failed", sector.name(), Strings.getSimpleMessage(t)), t);
        }

        state.rules.sector = sector;
        //a freshly generated sector has no launch link yet
        sector.info.origin = null;
        sector.info.destination = null;
        sector.info.attempts ++;

        logic.play();
        saveSector(sector);
        Events.fire(new SectorLaunchEvent(sector));

        Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-started", sector.name(), sector.id));
    }

    private static boolean loadSectorSave(Sector sector){
        SaveSlot slot = sector.save;
        if(slot == null || slot.file == null || !slot.file.exists()){
            return false;
        }

        if(!SaveIO.isSaveValid(slot.file)){
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.sector-save-corrupt", slot.file.name()));
            return false;
        }

        try{
            SaveIO.load(slot.file, world.makeSectorContext(sector));
            state.rules.sector = sector;
            state.rules.cloudColor = sector.planet.landCloudColor;
            state.set(State.playing);

            slot.meta = SaveIO.getMeta(slot.file);
            Events.fire(new RulesLoadEvent(state.rules, true));

            Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-loaded", sector.name(), sector.id, slot.file.name()));
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.sector-load-failed", slot.file.name()), t);
            logic.reset();
            return false;
        }
    }

    /** Stops hosting and clears the world. */
    public static void stopHosting(){
        saveCurrent();

        logic.reset();

        if(net.active()){
            net.closeServer();
        }
        state.set(State.menu);
    }

    // ---------------------------------------------------------------- saving

    /** @return whether the playing sector was saved successfully. */
    public static boolean saveCurrent(){
        Sector sector = current();
        return sector != null && saveSector(sector);
    }

    /** Writes the sector to its save file and flushes its {@code SectorInfo}. */
    public static boolean saveSector(@Nullable Sector sector){
        if(sector == null || net.client() || state.isMenu()){
            return false;
        }
        //only the active sector can be serialized: SaveIO writes the current world
        if(sector != current()){
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.sector-not-active", sector.name()));
            return false;
        }

        try{
            Fi file = sectorFile(sector);
            SaveIO.save(file);

            if(sector.save == null || sector.save.file == null || !sector.save.file.equals(file)){
                sector.save = createSlot(file, null);
            }else{
                sector.save.meta = SaveIO.getMeta(file);
            }

            sector.saveInfo();
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.sector-save-failed", sector.name()), t);
            return false;
        }
    }

    /** @return the save file used for a sector, matching vanilla's naming scheme. */
    public static Fi sectorFile(Sector sector){
        return saveDirectory.child("sector-" + sector.planet.name + "-" + sector.id + "." + saveExtension);
    }

    /** Deletes a sector's save file and its {@code SectorInfo}. */
    public static void deleteSaveData(@Nullable Sector sector){
        if(sector == null) return;

        Fi file = sectorFile(sector);
        deleteFile(file);
        deleteFile(SaveIO.backupFileFor(file));

        sector.save = null;
        sector.clearInfo();
    }

    /** Looks for existing {@code sector-*.msav} files and re-attaches them to their sectors. */
    public static void scanSaves(){
        if(saveDirectory == null || !saveDirectory.exists()) return;

        int found = 0;
        for(Fi file : saveDirectory.list()){
            if(!file.name().startsWith("sector-") || !file.extEquals(saveExtension)) continue;
            //backups share the sector- prefix but must not be picked as the primary slot
            if(file.name().contains("backup")) continue;

            try{
                SaveMeta meta = SaveIO.getMeta(file);
                if(meta == null || meta.rules == null || meta.rules.sector == null) continue;

                Sector sector = meta.rules.sector;
                if(sector.save == null){
                    sector.save = createSlot(file, meta);
                }
                found ++;
            }catch(Throwable t){
                Log.warn("[CampaignServer] " + I18n.get("campaign.log.sector-save-unreadable", file.name(), Strings.getSimpleMessage(t)));
            }
        }

        if(found > 0){
            Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-saves-found", found));
        }
    }

    private static @Nullable SaveSlot createSlot(Fi file, @Nullable SaveMeta meta){
        if(saves == null) return null;

        try{
            SaveSlot slot = saves.new SaveSlot(file);
            slot.meta = meta != null ? meta : SaveIO.getMeta(file);
            slot.setName(file.nameWithoutExtension());
            slot.setAutosave(true);
            return slot;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.slot-failed", file.name()), t);
            return null;
        }
    }

    private static void deleteFile(@Nullable Fi file){
        try{
            if(file != null && file.exists()){
                file.delete();
            }
        }catch(Throwable t){
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.delete-failed", file == null ? "null" : file.path()));
        }
    }

    // ---------------------------------------------------------------- client sync

    /** Sends the current world to every connected player. */
    public static void resyncAll(){
        for(Player player : Groups.player.copy()){
            resync(player);
        }
    }

    /** Sends the current world to one player. */
    public static void resync(@Nullable Player player){
        if(player == null || player.con == null || !player.isAdded()) return;

        try{
            Call.worldDataBegin(player.con);
            netServer.sendWorldData(player);
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.resync-failed", player.plainName()), t);
        }
    }

    // ---------------------------------------------------------------- sector map assets

    /**
     * Sector presets ship their maps inside Mindustry's assets, which a bare
     * {@code server-release.jar} does not contain. If the map is missing, try to load an
     * equivalent {@code .msav} from the server's map folder or the plugin data folder.
     */
    private static void ensureGenerator(Sector sector) throws CampaignException{
        SectorPreset preset = sector.preset;
        if(preset == null || preset.generator == null) return;
        if(preset.generator.map != null) return;

        Map map = findExternalMap(preset);
        if(map != null){
            preset.generator = new FileMapGenerator(map, preset);
            Log.info("[CampaignServer] " + I18n.get("campaign.log.external-map", map.file.name(), preset.name));
            return;
        }

        throw new CampaignException(assetHint(preset));
    }

    private static @Nullable Map findExternalMap(SectorPreset preset){
        Seq<Fi> candidates = new Seq<>();
        candidates.add(customMapDirectory.child(preset.name + "." + saveExtension));
        candidates.add(customMapDirectory.child(preset.planet.name + "/" + preset.name + "." + saveExtension));

        Fi pluginData = CampaignConfig.dataFolder();
        if(pluginData != null){
            candidates.add(pluginData.child("maps/" + preset.name + "." + saveExtension));
            candidates.add(pluginData.child("maps/" + preset.planet.name + "/" + preset.name + "." + saveExtension));
        }

        for(Fi file : candidates){
            if(file == null || !file.exists()) continue;
            try{
                return MapIO.createMap(file, true);
            }catch(Throwable t){
                Log.warn("[CampaignServer] " + I18n.get("campaign.log.map-read-failed", file.path(), Strings.getSimpleMessage(t)));
            }
        }

        return maps.byName(preset.name);
    }

    private static String assetHint(SectorPreset preset){
        return I18n.get("campaign.error.map-missing", preset.name, preset.planet.name);
    }
}
