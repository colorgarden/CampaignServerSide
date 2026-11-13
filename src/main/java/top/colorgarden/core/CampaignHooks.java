// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.SectorService;
import top.colorgarden.core.ServerControlHook;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.util.I18n;
import top.colorgarden.vote.CampaignVote;
import top.colorgarden.vote.VoteMenu;




import arc.ApplicationListener;
import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
import mindustry.core.GameState.State;
import mindustry.game.EventType.*;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.Sector;
import mindustry.world.blocks.logic.LogicBlock;

import static mindustry.Vars.*;

/** Wires the campaign into the server's event flow. */
public class CampaignHooks{
    /** False when the plugin runs on a graphical client; all campaign handling stays off then. */
    public static boolean enabled;
    private static float autosaveTime;
    /** Players that still need the join notice, mapped to the earliest time it may be sent. */
    private static final ObjectMap<Player, Long> pendingJoinInfo = new ObjectMap<>();
    /** Delay before the notice is sent, so the client finished loading its chat. */
    private static final long joinInfoDelay = 3000L;
    /** Seconds since the last reachable-sector scan. */
    private static float campaignUnlockTimer;
    private static float autoUnlockTimer;
    private static float coreUnlockTimer;
    private static float physicsTimer;


    /** True while the simulation is paused only because no player is online. */

    /** Live execution probe state. */
    private static boolean logicProbeActive;
    private static int logicProbeFrame, logicProbeIndex;
    private static final int logicProbeInterval = 12;
    private static final float[] logicProbeSamples = new float[6];

    public static void install(){
        if(!headless){
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.client-mode"));
            return;
        }

        enabled = true;

        ServerControlHook.install(CampaignHooks::onGameOver);
        installUsageHandler();
        installChatBallot();
        CampaignVote.init();
        top.colorgarden.command.AdminPanel.init();
        top.colorgarden.command.SectorPanel.init();
        top.colorgarden.command.MainMenu.init();
        top.colorgarden.command.LaunchPanel.init();
        AnnounceService.init();

        Events.on(ServerLoadEvent.class, event -> onServerLoad());
        Events.on(PlayerJoin.class, event -> {
            scheduleJoinInfo(event.player);
            //a vote may already be running; give the newcomer the ballot too
            VoteMenu.pushVoteTo(event.player);
        });
        Events.on(PlayerLeave.class, event -> {
            if(event.player != null){
                pendingJoinInfo.remove(event.player);
                VoteMenu.forget(event.player);
                ActivityTracker.forget(event.player);
                top.colorgarden.command.AdminPanel.forget(event.player);
            }
        });
        top.colorgarden.core.EnvironmentService.init();
        Events.on(WorldLoadEvent.class, event -> {
            applyLogicRules();
            //a new sector must not inherit the physics bodies of the previous one
            SectorService.resetPhysics();
            top.colorgarden.core.EnvironmentService.onWorldLoad();
        });
        Events.on(RulesLoadEvent.class, event -> applyLogicRules());
        //opt-in trace: a placement that never arrives here was rejected client-side, which is how the
        //"cannot build" reports were tracked down. It must stay at debug level - at info it filled the
        //log with one line per placed tile
        Events.on(mindustry.game.EventType.BlockBuildEndEvent.class, event -> {
            if(event.unit == null || event.unit.getPlayer() == null || event.tile == null) return;
            mindustry.gen.Player actor = event.unit.getPlayer();
            Log.debug("[CampaignServer] build @ by @: breaking=@ tile=@,@ team=@ hasCore=@",
                event.tile.block() == null ? "?" : event.tile.block().name, actor.name, event.breaking,
                event.tile.x, event.tile.y,
                event.team == null ? "?" : event.team.name, actor.core() != null);
        });
        Events.on(SectorCaptureEvent.class, event -> onSectorCapture(event.sector));
        Events.on(SaveWriteEvent.class, event -> onSaveWrite());
        Events.run(Trigger.update, CampaignHooks::update);

        Core.app.addListener(new ApplicationListener(){
            @Override
            public void dispose(){
                onDispose();
            }
        });

        Log.info("[CampaignServer] " + I18n.get("campaign.log.hooks-installed"));
    }

    /**
     * Accepts a plain chat line {@code 1} or {@code 0} as a ballot while a vote is running, so nobody
     * has to type a command. The match is strict: the whole message must be exactly {@code 1} or
     * {@code 0} (surrounding whitespace ignored), anything else stays a normal chat message. Outside a
     * running vote the digits are left alone.
     */
    private static void installChatBallot(){
        netServer.admins.addChatFilter((player, message) -> {
            if(player == null || message == null) return message;
            if(CampaignVote.active() == null) return message;

            //"1" / "0" cast the only running vote; "1 2" / "0 2" pick one when several are running.
            //y / n and yes / no work exactly the same way, so nobody has to remember the digits.
            String trimmed = message.trim();
            String[] parts = trimmed.split("\\s+");
            if(parts.length == 0 || parts.length > 2) return message;

            String first = parts[0].toLowerCase(java.util.Locale.ROOT);
            String choice;
            if(first.equals("1") || first.equals("y") || first.equals("yes")){
                choice = "y";
            }else if(first.equals("0") || first.equals("n") || first.equals("no")){
                choice = "n";
            }else if(first.equals("2") || first.equals("a") || first.equals("abstain")){
                choice = "a";
            }else{
                return message;
            }

            if(parts.length == 2 && !parts[1].matches("\\d+")) return message;

            String problem = CampaignVote.cast(player, choice, parts.length == 2 ? parts[1] : null);
            player.sendMessage(problem == null
                ? I18n.get("campaign.vote.ballot-counted", I18n.get(
                    "a".equals(choice) ? "campaign.vote.abstain"
                        : "y".equals(choice) ? "campaign.vote.yes" : "campaign.vote.no"))
                : "[scarlet]" + problem);
            if(problem == null) VoteMenu.hideVote(player);

            //swallow the chat line: a ballot is not something to broadcast
            return null;
        });
    }

    /**
     * Vanilla {@code NetServer#invalidHandler} replies to wrong argument counts with English text that
     * uses gray; it is a public field explicitly meant for customisation, so it is replaced here with
     * the same logic in the configured language (accent labels, no gray).
     */
    private static void installUsageHandler(){
        netServer.invalidHandler = (player, response) -> {
            if(response.type == CommandHandler.ResponseType.manyArguments){
                return I18n.get("campaign.usage.many", response.command.text, response.command.paramText);
            }

            if(response.type == CommandHandler.ResponseType.fewArguments){
                return I18n.get("campaign.usage.few", response.command.text, response.command.paramText);
            }

            //unknown command: keep the vanilla "did you mean" suggestion, but localized
            int closestDistance = 0;
            CommandHandler.Command closest = null;

            for(CommandHandler.Command command : netServer.clientCommands.getCommandList()){
                int distance = Strings.levenshtein(command.text, response.runCommand);
                if(distance < 3 && (closest == null || distance < closestDistance)){
                    closestDistance = distance;
                    closest = command;
                }
            }

            return closest == null ? I18n.get("campaign.usage.unknown")
                : I18n.get("campaign.usage.suggest", closest.text);
        };

        Log.info("[CampaignServer] " + I18n.get("campaign.log.usage-handler"));
    }

    private static void onServerLoad(){
        Log.info("[CampaignServer] " + I18n.get("campaign.log.ready"));

        //a pause requested before the restart is applied again as soon as the world is running
        pauseIntent = CampaignConfig.data().paused;

        if(!CampaignConfig.data().autoStart){
            return;
        }

        Core.app.post(() -> {
            try{
                if(state.isGame()){
                    Log.warn("[CampaignServer] " + I18n.get("campaign.log.auto-start-skipped"));
                    return;
                }

                Sector sector = SectorService.defaultSector();
                if(sector == null){
                    Log.err("[CampaignServer] " + I18n.get("campaign.log.auto-start-no-sector"));
                    return;
                }

                SectorService.enter(sector);
                Log.info("[CampaignServer] " + I18n.get("campaign.log.auto-started", sector.name()));
            }catch(Throwable t){
                Log.err("[CampaignServer] " + I18n.get("campaign.log.auto-start-failed"), t);
            }
        });
    }

    /**
     * Vanilla {@code Logic#sectorCapture()} sets {@code rules.disableWorldProcessors = true} and
     * {@code LogicBlock.updateTile} returns before running the executor when that flag is set. Since
     * Rules is serialized into the sector save, one capture disables every world processor in that
     * sector for good. This clears the flag again (config {@code worldProcessors}).
     *
     * <p>Note: this is about <b>execution</b> only. Whether world processors may be built or edited is
     * governed by {@code allowEditWorldProcessors}, which this plugin deliberately does not touch.
     */
    public static void applyLogicRules(){
        if(!enabled || state.rules == null || !state.isCampaign()) return;

        //the vanilla difficulty dialog writes into the planet's campaign rules; do the same from config
        SectorService.applyCampaignRules(state.getPlanet());

        if(!CampaignConfig.data().worldProcessors) return;

        //vanilla disables world processors once a sector is captured; keep that, but revive them for
        //a sector that is still being played (a save can carry the flag from an earlier capture)
        Sector sector = state.rules.sector;
        if(sector != null && sector.info.wasCaptured) return;

        if(state.rules.disableWorldProcessors){
            state.rules.disableWorldProcessors = false;
            Log.info("[CampaignServer] " + I18n.get("campaign.log.worldprocessors-on"));
        }
    }

    /** @return {total logic blocks, privileged ones, initialized executors, enabled and initialized}. */
    public static int[] logicCounts(){
        int total = 0, privileged = 0, initialized = 0, running = 0;

        for(Building build : Groups.build){
            if(!(build instanceof LogicBlock.LogicBuild lb)) continue;
            total ++;
            if(lb.block instanceof LogicBlock block && block.privileged) privileged ++;
            if(lb.executor != null && lb.executor.initialized()){
                initialized ++;
                if(lb.enabled) running ++;
            }
        }

        return new int[]{total, privileged, initialized, running};
    }

    /**
     * Sum of the logic accumulators of every enabled logic build. This is a live signal, not a
     * configuration check: {@code LogicBlock#updateTile} advances the accumulator only when the
     * executor is actually allowed to run, so a sum that stays 0 across samples means the blocks
     * are not executing (for example while the game is paused).
     */
    public static float logicAccumulatorSum(){
        float sum = 0f;
        for(Building build : Groups.build){
            if(build instanceof LogicBlock.LogicBuild lb && lb.enabled) sum += lb.accumulator;
        }
        return sum;
    }

    
    /** Starts sampling the logic accumulators; sampled from the tick hook so pausing cannot stall it. */
    public static void startLogicProbe(){
        logicProbeActive = true;
        logicProbeFrame = 0;
        logicProbeIndex = 0;
        java.util.Arrays.fill(logicProbeSamples, 0f);
    }

    /** Advances the probe; called from the tick hook, which also runs while the game is paused. */
    private static void updateLogicProbe(){
        if(!logicProbeActive) return;

        if(++logicProbeFrame < logicProbeInterval) return;
        logicProbeFrame = 0;
        logicProbeSamples[logicProbeIndex ++] = logicAccumulatorSum();

        if(logicProbeIndex < logicProbeSamples.length) return;

        logicProbeActive = false;

        StringBuilder builder = new StringBuilder();
        float max = 0f;
        for(float value : logicProbeSamples){
            if(value > max) max = value;
            if(builder.length() > 0) builder.append(' ');
            builder.append(Strings.fixed(value, 3));
        }

        Log.info("[CampaignServer] " + I18n.get("campaign.logic.probe.result", builder.toString(), max, state.isPaused()));
    }

    private static void onSectorCapture(Sector sector){
        if(net.client() || sector == null) return;

        //vanilla only saves the sector when control != null, which is never the case on a server
        boolean saved = SectorService.saveSector(sector);

        if(CampaignConfig.data().announce){
            Call.sendMessage(I18n.get(saved ? "campaign.chat.sector-captured" : "campaign.chat.sector-captured-failed", sector.name()));
        }

        Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-captured", sector.name(), saved));


        //vanilla unlocks new tech-tree entries on capture from the client; do it here instead
        int unlocked = ResearchService.checkAutoUnlocks();
        if(unlocked > 0){
            Log.info("[CampaignServer] " + I18n.get("campaign.log.auto-unlocked", unlocked));
        }
        CampaignNetwork.pushInfoToAll();
    }

    private static void onSaveWrite(){
        if(net.client() || !state.isCampaign() || state.getPlanet() == null) return;

        try{
            //Control normally does this, but it does not exist on a headless server
            state.getPlanet().saveStats();
        }catch(Throwable ignored){
            //stats are cosmetic
        }
    }

    private static void onGameOver(GameOverEvent event){
        Sector sector = SectorService.current();
        Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-lost", sector == null ? "-" : sector.name()));

        state.gameOver = true;
        state.afterGameOver = true;

        try{
            Call.updateGameOver(event.winner);
            Call.infoMessage(I18n.get("campaign.chat.sector-lost", sector == null ? "-" : sector.name()));
        }catch(Throwable ignored){
            //messaging is best-effort
        }

        boolean clear = SectorService.lostClears(sector);

        //vanilla campaign rule: with clearSectorOnLose on the lost sector is not saved at all, so the
        //next entry generates it from scratch; with the rule off the ruined base is saved and the next
        //entry rebuilds it the vanilla way (damage, regenerate, buildings restored as derelicts)
        if(sector != null){
            sector.info.hasCore = false;
            sector.saveInfo();

            if(clear){
                SectorService.deleteSaveData(sector);
                Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-data-deleted"));
            }else{
                boolean saved = SectorService.saveCurrent();
                Log.info("[CampaignServer] " + I18n.get(saved ? "campaign.log.sector-lost-saved" : "campaign.log.sector-lost-save-failed"));
            }
        }

        //stop the simulation; the campaign expects a decision about what happens next
        state.set(State.paused);

        CampaignNetwork.pushInfoToAll();
    }

    /** @return the lines shown to a player after joining (also used by 'campaign-joininfo'). */
    public static Seq<String> joinInfoLines(boolean admin){
        Seq<String> lines = new Seq<>();
        //one line only: the version comes from mod.hjson, so a rebuilt jar always reports what it is
        lines.add(I18n.get("campaign.join.title", top.colorgarden.CampaignServerMod.modName(),
            top.colorgarden.CampaignServerMod.version()));

        Sector sector = SectorService.current();
        if(sector == null){
            lines.add(I18n.get("campaign.join.no-campaign"));
            if(admin) lines.add(I18n.get("campaign.join.admin-start"));
            return lines;
        }

        lines.add(I18n.get("campaign.join.current", sector.planet.localizedName, sector.name(), sector.id,
            state.wave, ResearchService.lockedCount(sector.planet)));
        lines.add(I18n.get("campaign.join.commands"));

        //a paused world cannot build anything, and until now nothing said so: players just saw every
        //placement fail. Name the state and the two ways out of it
        if(state.isPaused()){
            lines.add(I18n.get("campaign.join.paused"));
        }

        //everything else lives in /cv help, so the welcome stays short
        return lines;
    }

    private static void scheduleJoinInfo(Player player){
        if(player == null || !CampaignConfig.data().joinInfo) return;
        pendingJoinInfo.put(player, Time.millis() + joinInfoDelay);
    }

    /** Sends the queued join notices; called every tick. */
    private static void flushJoinInfo(){
        if(pendingJoinInfo.isEmpty()) return;

        long now = Time.millis();
        Seq<Player> ready = new Seq<>();

        for(ObjectMap.Entry<Player, Long> entry : pendingJoinInfo){
            if(entry.value <= now) ready.add(entry.key);
        }

        for(Player player : ready){
            pendingJoinInfo.remove(player);
            if(player == null || !player.isAdded()) continue;
            for(String line : joinInfoLines(player.admin)){
                player.sendMessage(line);
            }
        }
    }

    /** True while the pause was requested on purpose (vote, admin, game over), so it survives rejoins. */
    private static boolean pauseIntent;

    /** Records the intended pause state; called by the pause vote, the admin toggle and game over. */
    public static void setPauseIntent(boolean paused){
        pauseIntent = paused;
        //persisted so a restart does not silently resume a game that was paused on purpose
        if(CampaignConfig.data().paused != paused){
            CampaignConfig.data().paused = paused;
            CampaignConfig.save();
        }
    }

    public static boolean pauseIntent(){
        return pauseIntent;
    }
    private static void update(){
        flushJoinInfo();
        top.colorgarden.core.ActivityTracker.update();
        top.colorgarden.core.EnvironmentService.update();
        //menus are snapshots on the client; re-sending the same id is what keeps the numbers and the
        //vote countdown live
        top.colorgarden.vote.VoteMenu.tick();
        top.colorgarden.command.SectorPanel.tick();

        //vanilla runs Control#checkAutoUnlocks continuously on the client; a server has to poll it
        autoUnlockTimer += Time.delta;
        if(autoUnlockTimer >= 120f){
            autoUnlockTimer = 0f;
            if(state.isCampaign()) ResearchService.checkAutoUnlocks();
        }

        //vanilla Control#update unlocks every item that sits in the player core ("unlock core items").
        //Items like beryllium have a Produce(self) objective, which can only come true after this
        //unlock happened - without it the whole Erekir branch of the tech tree stays locked
        coreUnlockTimer += Time.delta;
        if(coreUnlockTimer >= 60f){
            coreUnlockTimer = 0f;
            ResearchService.unlockCoreItems();
        }

        //Mindustry never removes the physics body of a unit that despawns, so mass spawns (wave skipping,
        //big attacks) leave thousands of bodies in the collision quadtree and the server drops to a
        //fraction of its tick rate. Rebuilding is only safe when the bodies are stale, so this poll only
        //reports the leak: dropping the bodies of live units restarts their collision state and lets them
        //overlap, which is why the rebuild happens on sector load or on campaign-fixphysics instead
        physicsTimer += Time.delta;
        if(physicsTimer >= 300f){
            physicsTimer = 0f;
            SectorService.reportPhysicsLeak();
        }

        campaignUnlockTimer += Time.delta;
        if(campaignUnlockTimer >= 180f){
            campaignUnlockTimer = 0f;
            Sector currentSector = SectorService.current();
            AnnounceService.scan(currentSector == null ? null : currentSector.planet);
        }
        updateLogicProbe();

        //vanilla Saves#update: the running sector autosaves while the game is live, on the same
        //interval the game itself uses ("saveinterval", 60s by default). A paused game still saves;
        //only game over stops it.
        CampaignConfig config = CampaignConfig.data();
        if(!config.autosave || !state.isGame() || state.gameOver){
            autosaveTime = 0f;
            return;
        }

        autosaveTime += Time.delta;
        float interval = config.autosaveSeconds > 0f
            ? config.autosaveSeconds
            : Core.settings.getInt("saveinterval", 60);
        if(autosaveTime < Math.max(1f, interval) * 60f) return;

        autosaveTime = 0f;
        if(SectorService.saveCurrent()){
            Sector sector = SectorService.current();
            Log.info("[CampaignServer] " + I18n.get("campaign.log.autosaved", sector == null ? "-" : sector.name()));
        }
    }

    private static void onDispose(){
        try{
            //a lost sector whose save was just deleted must not be written back on shutdown; every
            //other case still saves, including a lost sector that keeps its buildings. The rule is
            //per planet now, so the current planet decides
            boolean lostAndCleared = state.gameOver && state.rules != null && state.rules.sector != null
                && SectorService.lostClears(state.rules.sector);

            if(CampaignConfig.data().saveOnExit && SectorService.playing() && !lostAndCleared){
                SectorService.saveCurrent();
                Log.info("[CampaignServer] " + I18n.get("campaign.log.saved-on-exit"));
            }
            CampaignConfig.save();
            Core.settings.manualSave();
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.log.dispose-failed"), t);
        }
    }
}
