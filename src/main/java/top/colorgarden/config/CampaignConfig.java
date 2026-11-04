// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.config;

import top.colorgarden.CampaignServerMod;
import top.colorgarden.util.I18n;




import arc.files.Fi;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.serialization.Json;
import arc.util.serialization.JsonWriter;
import mindustry.io.JsonIO;

import static mindustry.Vars.dataDirectory;

/**
 * Persistent plugin configuration, stored as {@code <server>/config/mods/campaign-server-side/config.json}.
 * Missing or corrupt files fall back to the defaults.
 */
public class CampaignConfig{
    private static CampaignConfig data = new CampaignConfig();
    private static @Nullable Fi file;

    /** Writes every field (unlike {@link JsonIO}, which omits values that equal the defaults). */
    private static final Json writer = new Json();
    static{
        writer.setUsePrototypes(false);
        writer.setOutputType(JsonWriter.OutputType.json);
    }

    // --- campaign ---

    /** Internal name of the planet used by the campaign, e.g. "serpulo" or "erekir". */
    public String planet = "serpulo";
    /** Sector id that was played last. -1 means "use the planet's last/start sector". */
    public int sector = -1;
    /** Message language: "auto" (JVM locale), "en" or a bundle locale such as "zh_CN". */
    public String language = "auto";
    /** Automatically resume the last sector when the server starts. */
    public boolean autoStart = false;

    // --- saving ---

    /** Save the played sector periodically. */
    public boolean autosave = true;
    /** Seconds between autosaves. */
    public float autosaveSeconds = 180f;
    /** Save the played sector when the server shuts down. */
    public boolean saveOnExit = true;
    /** Delete the sector save when the sector is lost (matches the client's "clear sector on loss" rule). */
    public boolean clearSectorOnLose = false;

    // --- permissions ---

    /** Master switch for sector switching (commands and API). */
    public boolean allowSectorSwitch = true;
    /** Master switch for research unlocking (commands and API). */
    public boolean allowResearch = true;
    /** Only admins may switch the sector; when false every player may use /sector. */
    public boolean sectorSwitchAdminOnly = true;
    /** Only sectors that are unlocked in the campaign may be entered (same rules as the vanilla planet view). */
    public boolean enforceSectorUnlock = true;
    /** Server admins may enter locked sectors anyway (console commands always may). */
    public boolean adminBypassLock = true;

    /**
     * Require and consume items for research, like the vanilla campaign: the cost is paid from the
     * storage of every sector with a base on the same planet, current sector last.
     */
    public boolean researchCosts = true;

    /**
     * Strict tech tree. When true (default) research unlocks <b>only</b> the requested content: if one
     * of its parents in the tech tree is still locked the request is refused and the missing parent is
     * named. When false the whole ancestor chain is unlocked along with it.
     */
    public boolean researchStrict = true;

    /** Allow switching planets at all. */
    public boolean allowPlanetSwitch = true;
    /** Only admins may switch planets; when false every player may use /planet. */
    public boolean planetSwitchAdminOnly = true;
    /** Allow "switch planet" votes. Independent of {@link #planetSwitchAdminOnly}. */
    public boolean votePlanet = true;

    /** Only admins may unlock research; when false every player may use /research. */
    public boolean researchAdminOnly = true;

    // --- voting ---

    /** Allow players to start votes at all. */
    public boolean voting = true;
    /** Allow votes that switch the campaign sector. */
    public boolean voteSector = true;
    /** Allow votes that unlock a research entry. */
    public boolean voteResearch = true;
    /** Allow votes that point the current sector's launch pads at another sector (same planet). */
    public boolean voteDestination = true;
    /** Allow votes that redirect the exports of every sector on the planet to the current one. */
    public boolean voteRedirect = true;
    /** Allow votes that pause or resume the simulation. */
    public boolean votePause = true;

    // --- campaign rules (the same dialog the vanilla campaign offers) ---

    /** Campaign difficulty: casual / easy / normal / hard / eradication. */
    public String difficulty = "normal";
    /** Enemy sectors invade the ones you own. */
    public boolean ruleSectorInvasion = false;
    /** Fog of war. */
    public boolean ruleFog = false;
    /** Hide enemy spawn points. */
    public boolean ruleHideSpawns = false;
    /** Unpredictable AI waves. */
    public boolean ruleRandomWaveAI = false;
    /** RTS attack AI for the enemy team. */
    public boolean ruleRtsAI = false;
    /** Disallow pausing. */
    public boolean rulePauseDisabled = false;
    /** Share of online players that must vote yes for a vote to pass (0.1 - 1). */
    public float votePercent = 0.6f;
    /** Minimum online players needed to start a vote. */
    public int voteMinPlayers = 2;
    /** How long a vote stays open, in seconds. */
    public float voteDuration = 15f;
    /**
     * How a vote is decided.
     * <ul>
     *   <li>{@code majority} - decided once everyone online has voted, or on timeout: more yes than no.
     *       This is the default: a minority cannot block the vote.</li>
     *   <li>{@code threshold} - pass at {@code ceil(online * votePercent)} yes votes, fail at the same
     *       number of no votes.</li>
     *   <li>{@code veto} - a single no vote fails the vote immediately; yes still needs the threshold.</li>
     * </ul>
     */
    public String voteDecision = "majority";
    /** Seconds without a new vote before the vote resolves; <= 0 disables the idle timeout. */
    public float voteTimeout = 10f;

    /** Cooldown before the same player can start another vote, in seconds (measured from the result). */
    public float voteCooldown = 2f;
    /** Players who did not move for {@link #afkSeconds} are ignored for vote quorums. */
    public boolean afkEnabled = true;
    /** How long a player may stand still before being treated as AFK, in seconds. */
    public float afkSeconds = 60f;

    // --- misc ---

    /**
     * Keep world (privileged) processors running. Vanilla {@code Logic#sectorCapture} sets
     * {@code rules.disableWorldProcessors = true}, and Rules is stored in the sector save as JSON,
     * so after any capture that sector would never execute logic again - even when it is loaded in a
     * later session. A dedicated campaign server keeps playing those sectors, so the flag is cleared.
     */
    public boolean worldProcessors = true;

    /** Pause the simulation while no player is online, and resume when someone joins. */
    public boolean pauseWhenEmpty = true;

    /** Send a short notice with the command hints to every player that joins. */
    public boolean joinInfo = true;

    /** Announce newly unlocked research and newly reachable sectors in chat. */
    public boolean announceUnlocks = true;

    /** Announce sector changes to every player. */
    public boolean announce = true;
    /** Link launch pads to the sector a new sector was started from, like a vanilla sector launch does. */
    public boolean launchPadLinking = true;

    /** Re-sync connected clients (send the new world) after a sector change instead of kicking them. */
    public boolean resyncClients = true;

    public static CampaignConfig data(){
        return data;
    }

    public static void load(){
        Fi source = configFile();
        if(source == null) return;

        if(source.exists()){
            try{
                CampaignConfig loaded = JsonIO.read(CampaignConfig.class, source.readString());
                if(loaded != null){
                    data = loaded;
                }
                Log.info("[CampaignServer] " + I18n.get("campaign.log.config-loaded", source.path()));
            }catch(Throwable t){
                Log.warn("[CampaignServer] " + I18n.get("campaign.log.config-broken", source.path(), Strings.getSimpleMessage(t)));
            }
        }else{
            save();
        }

        if(data.voteTimeout < 0f) data.voteTimeout = 0f;
        if(!"veto".equalsIgnoreCase(data.voteDecision) && !"majority".equalsIgnoreCase(data.voteDecision)){
            data.voteDecision = "threshold";
        }else{
            data.voteDecision = data.voteDecision.toLowerCase();
        }

        if(data.autosaveSeconds < 10f){
            data.autosaveSeconds = 10f;
        }
    }

    /**
     * Writes the config file with every option spelled out (so it can be edited without guessing names).
     *
     * <p>The plugin only changes {@code planet}/{@code sector} while it runs; everything else is owned by the
     * file. Those two values are therefore merged into whatever is on disk, so a config edited while the
     * server is running is never clobbered by a sector switch or a shutdown.</p>
     */
    public static void save(){
        Fi target = configFile();
        if(target == null) return;

        try{
            target.parent().mkdirs();
            target.writeString(writer.prettyPrint(data));
        }catch(Throwable t){
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.config-write-failed", target.path()));
        }
    }

    /** Folder for plugin data (extra sector maps, exports). Never null once the game is initialized. */
    public static @Nullable Fi dataFolder(){
        Fi target = configFile();
        if(target == null) return null;
        Fi folder = target.parent().child("data");
        folder.mkdirs();
        return folder;
    }

    /** @return the config file location, or null before the game is initialized. */
    public static @Nullable Fi location(){
        return configFile();
    }

    private static @Nullable Fi configFile(){
        if(file != null) return file;

        try{
            CampaignServerMod mod = CampaignServerMod.instance;
            if(mod != null){
                file = mod.getConfig();
                return file;
            }
        }catch(Throwable ignored){
            //mod is not registered - fall through to a stable data directory path
        }

        if(dataDirectory == null) return null;

        Fi folder = dataDirectory.child("campaign-server");
        folder.mkdirs();
        file = folder.child("config.json");
        return file;
    }
}
