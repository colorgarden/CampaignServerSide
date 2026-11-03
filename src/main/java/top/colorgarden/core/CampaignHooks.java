// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.SectorService;
import top.colorgarden.core.ServerControlHook;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.util.I18n;
import top.colorgarden.vote.CampaignVote;




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

    /** True while the simulation is paused only because no player is online. */
    private static boolean pausedForEmpty;

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
        CampaignVote.init();
        AnnounceService.init();

        Events.on(ServerLoadEvent.class, event -> onServerLoad());
        Events.on(PlayerJoin.class, event -> scheduleJoinInfo(event.player));
        Events.on(PlayerLeave.class, event -> {
            if(event.player != null) pendingJoinInfo.remove(event.player);
        });
        Events.on(WorldLoadEvent.class, event -> applyLogicRules());
        Events.on(RulesLoadEvent.class, event -> applyLogicRules());
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

    /** @return true while the simulation is paused only because the server is empty. */
    public static boolean isPausedForEmpty(){
        return pausedForEmpty;
    }

    /** Resumes the simulation if it was stopped by the empty-server pause. */
    public static void resumeIfEmptyPaused(){
        if(pausedForEmpty){
            pausedForEmpty = false;
            if(state.isPaused()) state.set(State.playing);
            Log.info("[CampaignServer] " + I18n.get("campaign.log.resumed"));
        }
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

    /** Pauses the simulation while nobody is online, and resumes as soon as a player joins. */
    private static void handleEmptyPause(){
        if(!enabled || !CampaignConfig.data().pauseWhenEmpty || !state.isCampaign()) return;

        if(Groups.player.size() > 0){
            if(pausedForEmpty){
                pausedForEmpty = false;
                if(state.isPaused()) state.set(State.playing);
                Log.info("[CampaignServer] " + I18n.get("campaign.log.resumed"));
            }
            return;
        }

        if(state.isPlaying() && !state.isPaused()){
            state.set(State.paused);
            pausedForEmpty = true;
            Log.info("[CampaignServer] " + I18n.get("campaign.log.paused-empty"));
        }
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

        boolean clear = CampaignConfig.data().clearSectorOnLose;
        if(sector != null){
            clear |= sector.planet.clearSectorOnLose || sector.planet.campaignRules.clearSectorOnLose;
        }

        if(clear && sector != null){
            SectorService.deleteSaveData(sector);
            Log.info("[CampaignServer] " + I18n.get("campaign.log.sector-data-deleted"));
        }else{
            SectorService.saveCurrent();
        }

        //stop the simulation; the campaign expects a decision about what happens next
        state.set(State.paused);

        CampaignNetwork.pushInfoToAll();
    }

    /** @return the lines shown to a player after joining (also used by 'campaign-joininfo'). */
    public static Seq<String> joinInfoLines(boolean admin){
        Seq<String> lines = new Seq<>();
        lines.add(I18n.get("campaign.join.title"));

        Sector sector = SectorService.current();
        if(sector == null){
            lines.add(I18n.get("campaign.join.no-campaign"));
            if(admin) lines.add(I18n.get("campaign.join.admin-start"));
            return lines;
        }

        lines.add(I18n.get("campaign.join.current", sector.planet.localizedName, sector.name(), sector.id,
            state.wave, ResearchService.lockedCount(sector.planet)));
        lines.add(I18n.get("campaign.join.commands"));

        //the research hint differs per audience: vote, or admin-only
        boolean voteResearch = CampaignVote.votingEnabled(CampaignVote.Kind.research);
        lines.add(I18n.get(voteResearch ? "campaign.join.vote" : "campaign.join.vote-disabled"));

        if(admin){
            lines.add(I18n.get("campaign.join.admin"));
            if(CampaignConfig.data().allowPlanetSwitch){
                lines.add(I18n.get("campaign.join.admin-planet"));
            }
        }else{
            lines.add(I18n.get(voteResearch ? "campaign.join.player-research-vote" : "campaign.join.player-research-adminonly"));
            if(CampaignVote.votingEnabled(CampaignVote.Kind.planet)){
                lines.add(I18n.get("campaign.join.player-planet-vote"));
            }
        }
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

    private static void update(){
        flushJoinInfo();

        campaignUnlockTimer += Time.delta;
        if(campaignUnlockTimer >= 180f){
            campaignUnlockTimer = 0f;
            Sector currentSector = SectorService.current();
            AnnounceService.scan(currentSector == null ? null : currentSector.planet);
        }
        updateLogicProbe();
        handleEmptyPause();

        CampaignConfig config = CampaignConfig.data();
        if(!config.autosave || !state.isCampaign() || state.isPaused()){
            autosaveTime = 0f;
            return;
        }

        autosaveTime += Time.delta;
        if(autosaveTime < config.autosaveSeconds * 60f) return;

        autosaveTime = 0f;
        if(SectorService.saveCurrent()){
            Sector sector = SectorService.current();
            Log.info("[CampaignServer] " + I18n.get("campaign.log.autosaved", sector == null ? "-" : sector.name()));
        }
    }

    private static void onDispose(){
        try{
            if(CampaignConfig.data().saveOnExit && SectorService.playing()){
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
