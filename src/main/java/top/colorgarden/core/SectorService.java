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

    /** @return every planet that can host a campaign (has sectors and a tech tree). */
    public static Seq<Planet> planets(){
        Seq<Planet> result = new Seq<>();
        for(Planet planet : content.planets()){
            if(planet != null && planet.sectors != null && planet.sectors.size > 0 && planet.techTree != null){
                result.add(planet);
            }
        }
        return result;
    }

    /** @return a campaign planet by internal name or localized name, ignoring case. */
    public static @Nullable Planet planetByName(@Nullable String query){
        if(query == null || query.isEmpty()) return null;

        for(Planet planet : planets()){
            if(planet.name.equalsIgnoreCase(query) || planet.localizedName.equalsIgnoreCase(query)){
                return planet;
            }
        }
        return null;
    }

    /** @return the sector to enter when switching to this planet: the last one played, else its start. */
    public static @Nullable Sector targetFor(@Nullable Planet planet){
        if(planet == null) return null;
        Sector last = planet.getLastSector();
        return last != null ? last : planet.getStartSector();
    }

    /**
     * Clears launch/landing pad links that point at another planet. Vanilla ignores them anyway
     * ({@code Universe#runTurn} requires {@code to.planet == planet}) and {@link #setDestination}
     * refuses to create them, but a hand-edited or older save can still contain one.
     *
     * @return how many links were cleared.
     */
    public static int sanitizeLaunchLinks(@Nullable Planet planet){
        if(planet == null) return 0;

        int cleared = 0;
        for(Sector sector : planet.sectors){
            if(sector == null || sector.info == null) continue;
            if(sector.info.destination != null && sector.info.destination.planet != planet){
                sector.info.destination = null;
                sector.saveInfo();
                cleared ++;
            }
        }
        return cleared;
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

        //menus append the sector state, e.g. "遗迹海岸 已占领"
        for(String key : new String[]{"current", "captured", "launched", "unlaunched"}){
            String suffix = Strings.stripColors("current".equals(key) ? I18n.get("campaign.sector.current") : I18n.get("campaign.sector.status." + key));
            if(suffix != null && !suffix.isEmpty() && raw.endsWith(suffix)){
                raw = raw.substring(0, raw.length() - suffix.length()).trim();
                break;
            }
        }
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

        //menus show numbered sectors as "#175"
        if(lower.startsWith("#")){
            lower = lower.substring(1);
            raw = raw.substring(1);
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
            //menu labels follow the client language, so match that too
            if(I18n.content(other).equalsIgnoreCase(raw)) return other.sector;
            if(other.name.equalsIgnoreCase(raw)) return other.sector;
        }

        for(Sector sector : planet.sectors){
            if(sector != null && sector.name() != null && sector.name().equalsIgnoreCase(raw)){
                return sector;
            }
        }

        //partial match: typing "零号" or "ground" should still find it, as long as it is unambiguous
        Sector partial = null;
        int hits = 0;
        for(SectorPreset other : presets(planet)){
            if(other.sector == null) continue;
            if(contains(other.name, lower) || contains(other.localizedName, lower) || contains(I18n.content(other), lower)){
                partial = other.sector;
                if(++hits > 1) return null;
            }
        }

        for(Sector sector : planet.sectors){
            if(sector == null || sector == partial) continue;
            if(contains(sector.name(), lower) || contains(String.valueOf(sector.id), lower)){
                partial = sector;
                if(++hits > 1) return null;
            }
        }

        return hits == 1 ? partial : null;
    }

    private static boolean contains(@Nullable String value, String needle){
        return value != null && !needle.isEmpty() && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** @return the campaign rules of the given planet, creating the object when the game has none. */
    public static mindustry.game.CampaignRules campaignRules(@Nullable Planet planet){
        if(planet == null) return new mindustry.game.CampaignRules();
        if(planet.campaignRules == null) planet.campaignRules = new mindustry.game.CampaignRules();
        return planet.campaignRules;
    }

    /** @return the difficulty named in the config, falling back to normal. */
    public static mindustry.game.Difficulty difficulty(){
        String name = CampaignConfig.data().difficulty;

        for(mindustry.game.Difficulty value : mindustry.game.Difficulty.all){
            if(value.name().equalsIgnoreCase(name)) return value;
        }
        return mindustry.game.Difficulty.normal;
    }

    /**
     * Copies the config into the planet's campaign rules and applies them to the running rules object,
     * which is what the vanilla difficulty dialog does.
     */
    public static void applyCampaignRules(@Nullable Planet planet){
        if(planet == null) return;

        CampaignConfig config = CampaignConfig.data();
        mindustry.game.CampaignRules rules = campaignRules(planet);

        rules.difficulty = difficulty();
        rules.sectorInvasion = config.ruleSectorInvasion;
        rules.fog = config.ruleFog;
        rules.hideSpawns = config.ruleHideSpawns;
        rules.randomWaveAI = config.ruleRandomWaveAI;
        rules.rtsAI = config.ruleRtsAI;
        rules.pauseDisabled = config.rulePauseDisabled;
        //vanilla campaign rule: wipe the sector when it is lost instead of keeping the buildings
        rules.clearSectorOnLose = config.clearSectorOnLose;

        if(state.rules != null){
            rules.apply(planet, state.rules);
            //reactors only explode when this is on; it defaults to true but a sector save can carry false,
            //and CampaignRules has no field for it - it lives on Rules only
            state.rules.reactorExplosions = config.ruleReactorExplosions;
        }
    }

    /** Sets the difficulty by name. @return the applied difficulty, or null when the name is unknown. */
    public static @Nullable mindustry.game.Difficulty setDifficulty(String name){
        mindustry.game.Difficulty found = null;
        for(mindustry.game.Difficulty value : mindustry.game.Difficulty.all){
            if(value.name().equalsIgnoreCase(name)){
                found = value;
                break;
            }
        }
        if(found == null) return null;

        CampaignConfig.data().difficulty = found.name();
        applyCampaignRules(planet());
        return found;
    }

    /** @return the new value, or null when the rule name is unknown. */
    public static @Nullable Boolean toggleRule(String name, @Nullable Boolean force){
        CampaignConfig config = CampaignConfig.data();
        boolean value;
        java.util.function.Consumer<Boolean> setter;

        switch(name.toLowerCase(Locale.ROOT)){
            case "invasion", "sectorinvasion" -> setter = v -> config.ruleSectorInvasion = v;
            case "fog" -> setter = v -> config.ruleFog = v;
            case "hidespawns" -> setter = v -> config.ruleHideSpawns = v;
            case "randomwaveai" -> setter = v -> config.ruleRandomWaveAI = v;
            case "rtsai" -> setter = v -> config.ruleRtsAI = v;
            case "pausedisabled" -> setter = v -> config.rulePauseDisabled = v;
            case "clearonlose", "clear" -> setter = v -> config.clearSectorOnLose = v;
            case "reactorexplosions", "reactor" -> setter = v -> config.ruleReactorExplosions = v;
            default -> {
                return null;
            }
        }

        value = force != null ? force : !currentRule(name);
        setter.accept(value);
        applyCampaignRules(planet());
        return value;
    }

    /** @return the current value of a campaign rule flag. */
    public static boolean currentRule(String name){
        CampaignConfig config = CampaignConfig.data();
        return switch(name.toLowerCase(Locale.ROOT)){
            case "invasion", "sectorinvasion" -> config.ruleSectorInvasion;
            case "fog" -> config.ruleFog;
            case "hidespawns" -> config.ruleHideSpawns;
            case "randomwaveai" -> config.ruleRandomWaveAI;
            case "rtsai" -> config.ruleRtsAI;
            case "pausedisabled" -> config.rulePauseDisabled;
            case "clearonlose", "clear" -> config.clearSectorOnLose;
            case "reactorexplosions", "reactor" -> config.ruleReactorExplosions;
            default -> false;
        };
    }

    /** @return whether a campaign rule flag name exists. */
    public static boolean isRule(String name){
        return switch(name.toLowerCase(Locale.ROOT)){
            case "invasion", "sectorinvasion", "fog", "hidespawns", "randomwaveai", "rtsai", "pausedisabled",
                 "clearonlose", "clear", "reactorexplosions", "reactor" -> true;
            default -> false;
        };
    }

    /** @return a short, human readable description of a sector's progress. */
    public static String statusOf(@Nullable Sector sector){
        if(sector == null) return "unknown";
        if(sector == current() && playing()) return "playing";
        if(sector.hasBase()) return sector.info.wasCaptured ? "captured" : "base";
        if(sector.info != null && sector.info.wasCaptured) return "cleared";
        if(sector.isShielded()) return "shielded";
        if(canEnter(sector)) return "available";
        return "locked";
    }

    /**
     * Mirrors the vanilla "may this sector be launched at" rule
     * ({@code PlanetDialog#canSelect}, sector-launch mode): shielded sectors are closed, sectors with a
     * base or the planet start sector are always open, preset sectors need their tech node unlocked, and
     * numbered sectors follow {@code PlanetGenerator#allowLanding}.
     */
    public static boolean canEnter(@Nullable Sector sector){
        if(sector == null) return false;

        Planet planet = sector.planet;
        if(planet == null || planet.generator == null) return false;
        if(sector.isShielded()) return false;
        if(sector.hasBase() || sector.id == planet.startSector) return true;

        SectorPreset preset = sector.preset;
        //PlanetDialog#isLocked: a preset with an unlock node cannot be played until it is unlocked,
        //which for sector presets means its SectorComplete objective (the gate sector) is met.
        //canSelect() alone is only about picking the sector in the planet view, not about playing it.
        if(preset != null && preset.techNode != null && preset.locked() && !sector.hasBase()) return false;

        if(preset != null && preset.requireUnlock){
            mindustry.content.TechTree.TechNode node = preset.techNode;
            return preset.unlocked() || node == null || node.parent == null
                || (node.parent.content.unlocked()
                    && (!(node.parent.content instanceof SectorPreset parent) || parent.sector.hasBase()));
        }

        return planet.generator.allowLanding(sector);
    }

    /**
     * @return true when the sector should be shown as a plain number ("#24") rather than by name.
     *
     * <p>A preset does not imply a campaign map: community submissions are presets with a slug name
     * and no localization ({@code SectorPreset#localizedName} falls back to the slug), and they carry
     * no tech node. Those slots behave like numbered sectors - they are not part of the campaign chain
     * - so the pickers label them by id.</p>
     */
    public static boolean isNumbered(@Nullable Sector sector){
        if(sector == null) return false;
        mindustry.type.SectorPreset preset = sector.preset;
        if(preset == null) return true;
        if(preset.techNode != null) return false;

        String localized = preset.localizedName;
        return localized == null || localized.isEmpty() || localized.equals(preset.name);
    }

    /** @return the reason entering is blocked ("shielded"/"locked"), or an empty string when it is allowed. */
    public static String lockReason(@Nullable Sector sector){
        if(sector == null) return "locked";
        if(canEnter(sector)) return "";
        return sector.isShielded() ? "shielded" : "locked";
    }

    /**
     * Clears {@code SectorInfo#spawnPosition} so the next generation picks a spawn marker again.
     *
     * @param selector null for the current sector, "all" for every sector of the current planet,
     *                 otherwise anything {@link #resolve} understands
     * @return how many sectors were cleared
     */
    public static int clearSpawnPositions(@Nullable String selector){
        int count = 0;

        if(selector == null || selector.trim().isEmpty()){
            Sector current = current();
            if(current != null){
                current.info.spawnPosition = 0;
                current.saveInfo();
                count = 1;
            }
            return count;
        }

        Planet planet = planet();
        if(planet == null) return 0;

        for(Sector sector : planet.sectors){
            if(sector == null) continue;

            if(!"all".equalsIgnoreCase(selector)){
                Sector target = resolve(selector);
                if(target != sector) continue;
            }

            sector.info.spawnPosition = 0;
            sector.saveInfo();
            count ++;
        }

        return count;
    }
    // ---------------------------------------------------------------- launch hints

    /**
     * @return the sector a player has to launch from to reach this one, or null when there is none.
     *         Vanilla pads reach a numbered sector from a neighbouring sector that has a base, so when
     *         none has one yet the hint points at the unlocked neighbour to play first.
     */
    public static @Nullable Sector launchSource(@Nullable Sector target){
        if(target == null || target.planet == null) return null;

        for(Sector near : target.near()){
            if(near != null && near != target && near.hasBase()) return near;
        }

        for(Sector near : target.near()){
            if(near == null || near == target) continue;
            if(near.preset == null) continue;
            if(canEnter(near)) return near;
        }

        return null;
    }

    /**
     * @return a localized hint for a locked sector: what is actually missing from the tech tree, or -
     *         when nothing is missing - which neighbouring sector to launch from
     */
    public static String launchHint(@Nullable Sector target){
        if(target == null || canEnter(target)) return "";

        //a preset sector is gated by its tech node; that is the real reason, the launch route is only
        //how you get there once it is unlocked
        if(target.preset != null){
            Seq<String> missing = ResearchService.missingObjectives(target.preset.techNode);
            if(!missing.isEmpty()){
                return I18n.get("campaign.hint.missing", missing.toString(", "));
            }
        }

        Sector source = launchSource(target);
        return source == null ? I18n.get("campaign.hint.no-launcher")
            : I18n.get("campaign.hint.launcher", source.name());
    }

    // ---------------------------------------------------------------- life cycle

    /** Saves the current sector (if any), then loads or generates the given one and hosts it. */
    public static void enter(@Nullable Sector sector) throws CampaignException{
        enter(sector, false, false);
    }

    /**
     * @param fresh if true, the sector save data is discarded and the sector is generated again.
     */
    public static void enter(@Nullable Sector sector, boolean fresh) throws CampaignException{
        enter(sector, fresh, false);
    }

    /**
     * @param force skip the vanilla sector-unlock check; used by console commands and by admins when
     *              {@code adminBypassLock} is enabled.
     */
    public static void enter(@Nullable Sector sector, boolean fresh, boolean force) throws CampaignException{
        enter(null, sector, fresh, force);
    }

    /** Convenience overload for player-triggered launches (prompt applies). */
    public static void enter(@Nullable Player initiator, @Nullable Sector sector) throws CampaignException{
        enter(initiator, sector, false, false);
    }

    /** A launch that waits for the player to confirm the core/cargo in the launch dialog. */
    public static class PendingLaunch{
        public final Sector sector;
        public final boolean fresh, force;

        public PendingLaunch(Sector sector, boolean fresh, boolean force){
            this.sector = sector;
            this.fresh = fresh;
            this.force = force;
        }
    }

    /**
     * Entry point with the player that triggered it. When the sector has no save yet it will be
     * generated, so the initiator is asked for the launch configuration first (vanilla shows
     * {@code LaunchLoadoutDialog} at this point); the generation runs from
     * {@link top.colorgarden.command.LaunchPanel} once confirmed.
     */
    public static void enter(@Nullable Player initiator, @Nullable Sector sector, boolean fresh, boolean force) throws CampaignException{
        //diagnostic: shows exactly why the launch dialog is shown or skipped
        boolean launchSetup = sector != null && (needsGeneration(sector) || !sector.hasBase());

        Log.info("[CampaignServer] launch: initiator=@ prompt=@ needsGeneration=@ hasBase=@ sector=@", initiator == null ? "-" : initiator.plainName(), CampaignConfig.data().promptLaunch, sector != null && needsGeneration(sector), sector != null && sector.hasBase(), sector == null ? "-" : sector.name());

        //a vote, the automatic startup or a console command has no player to ask: the switch runs with
        //the launch configuration that is already selected instead of waiting for an admin to confirm
        if(initiator == null){
            enterInternal(sector, fresh, force);
            return;
        }

        //a sector without a standing base will be set up from scratch, so ask for the configuration
        if(CampaignConfig.data().promptLaunch && launchSetup){
            top.colorgarden.command.LaunchPanel.show(initiator, new PendingLaunch(sector, fresh, force));
            return;
        }

        enterInternal(sector, fresh, force);
    }

    /** Runs a launch that was confirmed in the dialog. */
    public static void enterNow(PendingLaunch launch){
        try{
            enterInternal(launch.sector, launch.fresh, launch.force);
        }catch(CampaignException e){
            Call.sendMessage("[scarlet]" + e.getMessage());
        }
    }

    /** Copies the sector save aside before it is regenerated, so a lost base can be recovered. */
    private static void preserveSave(Sector sector){
        try{
            if(sector == null || sector.save == null || sector.save.file == null) return;
            if(!sector.save.file.exists()) return;

            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            arc.files.Fi copy = sector.save.file.parent().child(sector.save.file.nameWithoutExtension() + ".lost-" + stamp + ".msav");
            sector.save.file.copyTo(copy);

            Log.info("[CampaignServer] " + I18n.get("campaign.log.save-preserved", copy.name()));
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.save-failed", Strings.getSimpleMessage(t)));
        }
    }

    /** @return an online admin, or the first online player, or null when nobody is online. */
    private static @Nullable Player firstAdminOnline(){
        Player fallback = null;

        for(Player player : Groups.player){
            if(player == null || player.con == null) continue;
            if(player.admin) return player;
            if(fallback == null) fallback = player;
        }

        return fallback;
    }

    /** Throws when the origin sector cannot supply the configured launch cargo. */
    private static void requireCargo(@Nullable Sector origin, Sector target) throws CampaignException{
        if(origin == null || origin == target) return;
        if(!needsGeneration(target) && target.hasBase()) return;

        arc.struct.Seq<mindustry.type.ItemStack> cargo = LaunchCargo.current();
        if(cargo.isEmpty()) return;

        Seq<String> missing = new Seq<>();
        for(mindustry.type.ItemStack stack : cargo){
            int have = origin.info == null ? 0 : origin.info.items.get(stack.item);
            if(have < stack.amount){
                missing.add(stack.item.localizedName + " " + have + "/" + stack.amount);
            }
        }

        if(!missing.isEmpty()){
            throw new CampaignException(I18n.get("campaign.cargo.insufficient", Strings.join(", ", missing)));
        }
    }
    /**
     * A save without a core is normally a lost sector, but it can also be a save that was written
     * after a bad regeneration. Before throwing the world away, try the rotated backup: if it still
     * has cores, put it back as the canonical save.
     *
     * @return true when the backup was restored and the world should be reloaded
     */
    private static boolean restoreFromBackup(Sector sector){
        try{
            Fi canonical = sectorFile(sector);
            Fi backup = canonical.parent().child(canonical.nameWithoutExtension() + ".msav-backup.msav");
            if(!backup.exists() || backup.length() == 0) return false;

            preserveSave(sector);
            backup.copyTo(canonical);

            Log.warn("[CampaignServer] " + I18n.get("campaign.log.backup-restored", sector.name(), backup.name()));
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] backup restore failed: " + Strings.getSimpleMessage(t));
            return false;
        }
    }
    /**
     * Vanilla's recovery for a sector that was lost while {@code clearSectorOnLose} is off
     * ({@code Control#playSector}): the loaded world is only used as a source of surviving buildings,
     * then the sector is generated again and those buildings are restored as derelicts.
     *
     * @return true when the sector was rebuilt
     */
    private static boolean recoverLostSector(Sector sector){
        try{
            int spawnPos = sector.info.spawnPosition;
            mindustry.world.Tile spawn = spawnPos != 0 ? world.tile(spawnPos) : null;
            if(spawn == null) return false;

            //1. the core has to exist for SectorDamage to path towards it
            spawn.setBlock(sector.planet.defaultCore, state.rules.defaultTeam);

            //2. simulate the loss: everything along the enemy paths is destroyed
            try{
                mindustry.maps.SectorDamage.apply(1f);
            }catch(Throwable t){
                Log.warn("[CampaignServer] " + I18n.get("campaign.log.damage-skipped", Strings.getSimpleMessage(t)));
            }

            //3. remember what is left; the objects stay usable after logic.reset()
            arc.struct.Seq<mindustry.gen.Building> previousBuildings = new arc.struct.Seq<>();
            for(mindustry.gen.Building build : state.rules.defaultTeam.data().buildings) previousBuildings.add(build);

            arc.struct.Seq<mindustry.gen.Building> previousDerelicts = new arc.struct.Seq<>();
            for(mindustry.gen.Building build : mindustry.game.Team.derelict.data().buildings) previousDerelicts.add(build);

            arc.struct.Seq<mindustry.game.Teams.BlockPlan> previousPlans = new arc.struct.Seq<>();
            for(mindustry.game.Teams.BlockPlan plan : state.rules.defaultTeam.data().plans) previousPlans.add(plan);

            //4. throw the damaged world away and generate a clean one, core at the old position; the
            //buildings are restored inside beforePlay, exactly like vanilla Control#playSector, so
            //logic.play() registers them instead of leaving them unowned
            logic.reset();
            final arc.struct.Seq<mindustry.gen.Building> restoreBuildings = previousBuildings;
            final arc.struct.Seq<mindustry.gen.Building> restoreDerelicts = previousDerelicts;
            final arc.struct.Seq<mindustry.game.Teams.BlockPlan> restorePlans = previousPlans;

            createSector(sector, null, () -> {
                //the generator placed its own derelicts; drop them, the old ones are restored below
                arc.struct.Seq<mindustry.gen.Building> generatedDerelicts = new arc.struct.Seq<>();
                for(mindustry.gen.Building generated : mindustry.game.Team.derelict.data().buildings){
                    generatedDerelicts.add(generated);
                }
                for(mindustry.gen.Building generated : generatedDerelicts){
                    if(generated.tile != null) generated.tile.remove();
                }

                for(mindustry.gen.Building build : restoreDerelicts){
                    mindustry.world.Tile tile = world.tile(build.tileX(), build.tileY());
                    if(tile != null && tile.build == null && mindustry.world.Build.validPlace(build.block, mindustry.game.Team.derelict,
                        build.tileX(), build.tileY(), build.rotation, false, false)){
                        tile.setBlock(build.block, mindustry.game.Team.derelict, build.rotation, () -> build);
                    }
                }

                //the old power graphs point at deleted tiles
                for(mindustry.gen.Building build : restoreBuildings){
                    if(build.power != null){
                        build.power.graph = new mindustry.world.blocks.power.PowerGraph();
                        build.power.links.clear();
                    }
                }

                //surviving buildings come back as derelicts: config and health kept, but ruined
                for(mindustry.gen.Building build : restoreBuildings){
                    mindustry.world.Tile tile = world.tile(build.tileX(), build.tileY());
                    if(tile != null && tile.build == null && mindustry.world.Build.validPlace(build.block, state.rules.defaultTeam,
                        build.tileX(), build.tileY(), build.rotation, false, false)){
                        build.addPlan(false, true);
                        tile.setBlock(build.block, state.rules.defaultTeam, build.rotation, () -> build);
                        build.changeTeam(mindustry.game.Team.derelict);
                        build.dropped();
                    }
                }

                for(mindustry.gen.Building build : restoreBuildings){
                    if(build.isValid()) build.updateProximity();
                }

                //plans that do not already have the block at their position are carried over
                for(mindustry.game.Teams.BlockPlan plan : restorePlans){
                    mindustry.gen.Building build = world.build(plan.x, plan.y);
                    if(!(build != null && build.block == plan.block && build.tileX() == plan.x && build.tileY() == plan.y)){
                        state.rules.defaultTeam.data().plans.add(plan);
                    }
                }
            });

            //vanilla applies the launch loadout after the sector is running, so the selected core
            //blueprint is placed on top of the rebuilt ruins
            if(CampaignConfig.data().blueprintOnRebuild) applyCoreBlueprint(sector, null);

            saveSector(sector);
            Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-recovered", sector.name(), previousBuildings.size));
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.sector-recover-failed", sector.name(), Strings.getSimpleMessage(t)));
            return false;
        }
    }

    /**
     * Puts a fresh core exactly where the old base stood. Vanilla does one thing only:
     * {@code spawn.setBlock(planet.defaultCore, team)} - it never searches for free space, because
     * moving the core would also move the recorded spawn position.
     *
     * @return true when a core was placed
     */
    private static boolean placeEmergencyCore(Sector sector){
        try{
            int pos = sector.info.spawnPosition;
            mindustry.world.Tile tile = pos != 0 ? world.tile(pos) : null;
            if(tile == null) return false;

            tile.setBlock(sector.planet.defaultCore, state.rules.defaultTeam);
            if(tile.build instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild core){
                state.teams.registerCore(core);
            }

            sector.info.hasCore = true;
            sector.saveInfo();

            Log.info("[CampaignServer] " + I18n.get("campaign.log.core-replaced", sector.name()));
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.apply-failed", Strings.getSimpleMessage(t)));
            return false;
        }
    }
    /** @return true when entering this sector would generate it instead of loading a save. */
    public static boolean needsGeneration(@Nullable Sector sector){
        if(sector == null) return false;
        return sector.save == null || sector.save.file == null || !sector.save.file.exists();
    }

    private static void enterInternal(@Nullable Sector sector, boolean fresh, boolean force) throws CampaignException{
        if(net.client()){
            throw new CampaignException(I18n.get("campaign.msg.server-only"));
        }
        if(sector == null){
            throw new CampaignException(I18n.get("campaign.msg.no-sector-hint"));
        }

        Sector previous = current();

        if(!force && sector != previous && CampaignConfig.data().enforceSectorUnlock){
            String reason = lockReason(sector);
            if(!reason.isEmpty()){
                throw new CampaignException(I18n.get("campaign.error.sector-" + reason, sector.name()));
            }
        }

        //vanilla switches maps through a WorldReloader: it clears the units, sends worldDataBegin to
        //every client before the load and world+assets afterwards. Doing it by hand leaves clients on
        //the old map (no world begin packet, no unit reset), so use the vanilla object itself.
        //a launch that has to build a new base costs the configured cargo; refuse it when the origin
        //sector cannot pay, instead of silently arriving empty-handed
        requireCargo(previous, sector);

        //save BEFORE the reloader starts: begin() resets the world, and a save after that is
        //silently skipped because the game is already in the menu state
        if(previous != null && !(fresh && previous == sector)){
            saveCurrent();
        }

        boolean syncClients = net.server() && Groups.player.size() > 0 && CampaignConfig.data().resyncClients;
        mindustry.net.WorldReloader reloader = syncClients ? new mindustry.net.WorldReloader() : null;
        if(reloader != null) reloader.begin();



        try{
            startSector(sector, fresh, previous);
        }catch(CampaignException e){
            //don't leave the server with an empty world if the new sector cannot be opened
            if(previous != null && previous != sector){
                Log.warn("[CampaignServer] " + I18n.get("campaign.log.switch-failed-recover", sector.name(), previous.name()));
                try{
                    startSector(previous, false, null);
                }catch(Throwable ignored){
                    Log.err("[CampaignServer] " + I18n.get("campaign.log.recover-failed"), ignored);
                }
            }
            throw e;
        }

        //a pad link that crosses planets can never transfer anything; drop it defensively
        int cleaned = sanitizeLaunchLinks(sector.planet);
        if(cleaned > 0){
            Log.info("[CampaignServer] " + I18n.get("campaign.log.links-cleaned", cleaned, sector.planet.localizedName));
        }

        //launch/landing pads of both ends need fresh import statistics
        sector.info.refreshImportRates(sector.planet);
        if(sector.info.destination != null){
            sector.info.destination.info.refreshImportRates(sector.planet);
        }

        //vanilla runs this from the client on every capture/unlock; a server has to do it itself
        int autoUnlocked = ResearchService.checkAutoUnlocks();
        if(autoUnlocked > 0){
            Log.info("[CampaignServer] " + I18n.get("campaign.log.auto-unlocked", autoUnlocked));
        }

        //the API tag has to be in the rules before the world is sent to any client
        CampaignNetwork.updateServerTag();

        if(!net.server()){
            netServer.openServer();
        }else if(reloader != null){
            //sends the new world (and its assets) to every player and respawns their units
            reloader.end();
        }

        //tell API clients (client mods) about the new sector
        CampaignNetwork.pushInfoToAll();
    }

    /** @return true when losing this sector must discard it ({@code clearSectorOnLose} in any form). */
    public static boolean lostClears(Sector sector){
        if(CampaignConfig.data().clearSectorOnLose) return true;
        return sector != null && (sector.planet.clearSectorOnLose || sector.planet.campaignRules.clearSectorOnLose);
    }
    /** Loads the sector save or generates the sector, then makes it the active one. */
    private static void startSector(Sector sector, boolean fresh, @Nullable Sector origin) throws CampaignException{
        logic.reset();

        boolean loaded = !fresh && loadSectorSave(sector);
        Log.info("[CampaignServer] enter @: loaded=@ cores=@", sector.name(), loaded, loaded ? state.rules.defaultTeam.cores().size : -1);

        //a backup is only a corruption fallback: it must never replace the vanilla lost-sector flow,
        //otherwise a sector that lost its core silently rolls back to the backup instead of being rebuilt
        if(!loaded && !fresh && sectorFile(sector).exists() && restoreFromBackup(sector)){
            loaded = loadSectorSave(sector);
            Log.info("[CampaignServer] enter @ after backup: loaded=@ cores=@", sector.name(), loaded, loaded ? state.rules.defaultTeam.cores().size : -1);
        }

        if(loaded && state.rules.defaultTeam.cores().isEmpty() && !lostClears(sector)){
            //vanilla Control#playSector: the old save is only read to collect the surviving buildings,
            //then the sector is rebuilt from the generator and those buildings are dropped back in as
            //derelicts, so a lost base looks ruined instead of surviving intact
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.sector-no-core-kept", sector.name()));
            if(recoverLostSector(sector)){
                //a sector that was lost stays paused until an admin decides what to do
                top.colorgarden.core.CampaignHooks.setPauseIntent(true);
                sector.info.hasCore = true;
                sector.saveInfo();
                state.set(State.playing);
                return;
            }

            //fallback: keep the loaded world and put a core back
            placeEmergencyCore(sector);
            sector.info.hasCore = false;
            sector.saveInfo();
            state.set(State.playing);
            return;
        }

        if(loaded && state.rules.defaultTeam.cores().isEmpty()){
            Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-no-core", sector.name()));
            //keep the abandoned save instead of letting the regeneration overwrite it silently
            preserveSave(sector);
            logic.reset();
            loaded = false;
        }

        if(!loaded){
            if(fresh){
                deleteSaveData(sector);
                //vanilla Control#playSector drops the stored spawn when the save is discarded, so a
                //regenerated sector picks a spawn point from the map again instead of reusing one
                sector.info.spawnPosition = 0;
            }
            createSector(sector, origin);
        }

        sector.planet.setLastSector(sector);
        CampaignConfig.data().planet = sector.planet.name;
        CampaignConfig.data().sector = sector.id;
        CampaignConfig.save();

        state.set(State.playing);
    }

    /**
     * Points a sector's launch pads at another sector (or clears the link). This is the server-side
     * replacement for the launch-pad config dialog, which only exists on a client.
     */
    public static void setDestination(Sector sector, @Nullable Sector destination) throws CampaignException{
        //vanilla only allows pad targets on the same planet (LaunchPad#buildConfiguration)
        if(destination != null && destination.planet != sector.planet){
            throw new CampaignException(I18n.get("campaign.destination.other-planet", destination.name(), sector.planet.localizedName));
        }

        sector.info.destination = destination;
        sector.saveInfo();

        sector.info.refreshImportRates(sector.planet);
        if(destination != null){
            destination.info.refreshImportRates(sector.planet);
        }
    }

    /**
     * One-click version of the vanilla landing-pad "redirect exports" button.
     *
     * <p>Vanilla {@code LandingPad#canRedirectExports} requires a base, a different source sector,
     * recorded exports of the pad's configured item, and a destination that is not us yet. The
     * statistics requirement is kept on purpose: without it this would blanket-repoint every sector on
     * the planet and silently undo hand-made routes. When no item is given, any recorded export
     * qualifies, which is the closest server-side equivalent of "this pad's item ships here".</p>
     *
     * @return how many sectors were actually changed
     */
    public static int redirectAllExports(Sector target){
        return redirectAllExports(target, null);
    }

    /**
     * @param item when non-null, only sectors exporting exactly this item are repointed
     *             (vanilla checks {@code SectorInfo#hasExport(item)})
     */
    public static int redirectAllExports(Sector target, @Nullable mindustry.type.Item item){
        int count = 0;

        for(Sector sector : target.planet.sectors){
            if(sector == null || sector == target || !sector.hasBase()) continue;
            if(sector.info.destination == target) continue;
            if(item == null ? !sector.info.anyExports() : !sector.info.hasExport(item)) continue;

            sector.info.destination = target;
            sector.saveInfo();
            count ++;
        }

        target.info.refreshImportRates(target.planet);
        return count;
    }

    /** @return how many sectors already point their exports at {@code target}. */
    public static int countExportsTo(Sector target){
        int count = 0;

        for(Sector sector : target.planet.sectors){
            if(sector == null || sector == target) continue;
            if(sector.info.destination == target) count ++;
        }

        return count;
    }

    /**
     * Wipes a sector back to "never played": deletes its save, clears the stored {@code SectorInfo}
     * and locks its preset again. The sector that is currently loaded is refused, because its world is
     * live.
     *
     * @return true when the sector was reset.
     */
    public static boolean resetSector(@Nullable Sector sector){
        if(sector == null || sector == current() || state.isCampaign() && state.rules.sector == sector){
            return false;
        }

        deleteSaveData(sector);

        sector.save = null;
        sector.info = new mindustry.game.SectorInfo();
        sector.info.hasCore = false;
        sector.info.wasCaptured = false;
        sector.info.destination = null;
        sector.info.origin = null;
        sector.info.items.clear();
        sector.info.production.clear();
        sector.info.rawProduction.clear();
        sector.info.export.clear();
        sector.info.imports.clear();
        sector.saveInfo();

        if(sector.preset != null){
            sector.preset.clearUnlock();
        }

        return true;
    }

    /**
     * Sets the "already captured" flag of a sector. A captured sector cannot be played normally
     * (waves are off) and, more importantly for a persistent server, its save may still carry
     * {@code rules.disableWorldProcessors}.
     */
    public static void setCaptured(Sector sector, boolean captured){
        sector.info.wasCaptured = captured;

        if(!captured && sector == current()){
            //make the sector playable again: a capture turns waves off and can leave the
            //world-processor gate closed in the saved rules
            state.rules.waves = sector.planet.allowWaves;
            state.rules.disableWorldProcessors = false;
        }

        sector.saveInfo();
    }

    /** Generates a brand new sector and starts playing it. */
    private static void createSector(Sector sector, @Nullable Sector origin) throws CampaignException{
        createSector(sector, origin, null);
    }

    /** @param beforePlay runs after the map is loaded and before {@code logic.play()}, like vanilla. */
    private static void createSector(Sector sector, @Nullable Sector origin, @Nullable Runnable beforePlay) throws CampaignException{
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
        //vanilla Control#playNewSector: a sector you launch into exports its production back to the origin
        boolean link = CampaignConfig.data().launchPadLinking && origin != null
            && origin != sector && origin.planet == sector.planet;
        sector.info.origin = link ? origin : null;
        sector.info.destination = link ? origin : null;
        sector.info.attempts ++;

        //vanilla runs beforePlay after loadSector and before logic.play(), so the restored buildings are
        //registered by the time the world starts
        if(beforePlay != null) beforePlay.run();

        //the launch loadout is placed before the world runs, exactly where vanilla generation puts it
        //(FileMapGenerator#placeLaunchLoadout). Placing it afterwards left logic blocks, item links and
        //power graphs uninitialised, which is what broke an entire map.
        if(origin != null) applyCoreBlueprint(sector, origin);

        logic.play();

        //anything the loadout added needs its neighbours and power links rebuilt now that the world runs
        if(origin != null) refreshPlacedBuildings();

        saveSector(sector);
        Events.fire(new SectorLaunchEvent(sector));

        Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-started", sector.name(), sector.id));
    }

    /** @return how many buildings were rewired. */
    public static int refreshBuildings(){
        return refreshPlacedBuildings();
    }

    /**
     * Rebuilds the neighbour caches of the team's buildings after a loadout placement.
     *
     * <p>Only {@code updateProximity()} is safe here. {@code PowerNode#links} is *player data* - the
     * actual wires - not a cache: clearing it disconnects every node in the sector, and reassigning
     * {@code power.graph} without re-adding the node leaves it in a graph that no longer contains it.
     * Mindustry rebuilds graphs from the links itself once the world runs.</p>
     */
    private static int refreshPlacedBuildings(){
        try{
            int touched = 0;
            for(mindustry.gen.Building build : state.rules.defaultTeam.data().buildings){
                if(build == null || build.tile == null) continue;

                build.updateProximity();
                touched ++;
            }

            if(touched > 0){
                Log.info("[CampaignServer] " + I18n.get("campaign.log.buildings-refreshed", touched));
            }
            return touched;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.buildings-refresh-failed", Strings.getSimpleMessage(t)));
            return 0;
        }
    }

    /**
     * Places the uploaded core blueprint instead of the plain generated core.
     *
     * <p>{@code Schematics#placeLoadout} is the vanilla routine the launch dialog's choice ends up in:
     * it anchors the schematic's own core tile at the given position, clears whatever is in the way and
     * registers the new core. The spawn position is updated so the player lands on the new core.</p>
     */
    private static void applyCoreBlueprint(Sector sector, @Nullable Sector origin){
        try{
            mindustry.gen.Building coreBuild = state.rules.defaultTeam.core();
            CoreBlueprints.Entry entry = CoreBlueprints.selectedEntry();
            mindustry.game.Schematic schematic = entry == null ? null : entry.schematic;

            if(schematic != null){
                if(coreBuild == null || coreBuild.tile == null) return;

                int x = coreBuild.tile.x, y = coreBuild.tile.y;
                mindustry.game.Schematics.placeLoadout(schematic, x, y, state.rules.defaultTeam, true);

                sector.info.spawnPosition = arc.math.geom.Point2.pack(x, y);
                Log.info("[CampaignServer] " + I18n.get("campaign.core.applied", sector.name(), schematic.width, schematic.height));
                applyCargo(origin);
                return;
            }

            //no blueprint: honour the chosen single core block when it is an upgrade
            mindustry.world.blocks.storage.CoreBlock chosen = CoreBlueprints.selectedCore();
            if(chosen == null || coreBuild == null || coreBuild.tile == null) return;
            if(coreBuild.block == chosen) return;
            if(!chosen.unlocked()) return;

            int x = coreBuild.tile.x, y = coreBuild.tile.y;
            coreBuild.tile.setBlock(chosen, state.rules.defaultTeam);
            if(coreBuild.tile.build instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild cb){
                state.teams.registerCore(cb);
            }

            sector.info.spawnPosition = arc.math.geom.Point2.pack(x, y);
            Log.info("[CampaignServer] " + I18n.get("campaign.core.applied-core", sector.name(), chosen.localizedName));
            applyCargo(origin);
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.apply-failed", Strings.getSimpleMessage(t)));
        }
    }
    /** Adds the configured launch cargo to the core that was just placed. */
    private static void applyCargo(@Nullable Sector origin){
        try{
            mindustry.gen.Building core = state.rules.defaultTeam.core();
            if(core == null) return;

            int capacity = core.block instanceof mindustry.world.blocks.storage.CoreBlock cb ? cb.itemCapacity : 0;
            LaunchCargo.apply(core.items, capacity, origin);
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.cargo.apply-failed", Strings.getSimpleMessage(t)));
        }
    }

    private static boolean loadSectorSave(Sector sector){        SaveSlot slot = sector.save;
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
            //backups, lost copies and the preserve copies share the prefix but are not the slot
            if(file.name().contains("backup") || file.name().contains(".lost-")) continue;

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
