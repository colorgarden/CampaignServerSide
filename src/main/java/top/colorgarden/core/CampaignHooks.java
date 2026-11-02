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
import arc.util.Log;
import arc.util.Time;
import mindustry.core.GameState.State;
import mindustry.game.EventType.*;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Sector;

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

    public static void install(){
        if(!headless){
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.client-mode"));
            return;
        }

        enabled = true;

        ServerControlHook.install(CampaignHooks::onGameOver);
        CampaignVote.init();

        Events.on(ServerLoadEvent.class, event -> onServerLoad());
        Events.on(PlayerJoin.class, event -> scheduleJoinInfo(event.player));
        Events.on(PlayerLeave.class, event -> {
            if(event.player != null) pendingJoinInfo.remove(event.player);
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

        boolean voting = CampaignVote.votingEnabled(CampaignVote.Kind.sector) || CampaignVote.votingEnabled(CampaignVote.Kind.research);
        lines.add(I18n.get(voting ? "campaign.join.vote" : "campaign.join.vote-disabled"));

        if(admin) lines.add(I18n.get("campaign.join.admin"));
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
