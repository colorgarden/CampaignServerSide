// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.util.I18n;

import arc.Core;
import arc.Events;
import arc.struct.ObjectSet;
import arc.util.Log;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.UnlockEvent;
import mindustry.gen.Call;
import mindustry.type.Planet;
import mindustry.type.Sector;

/**
 * Announces newly unlocked research and newly reachable sectors in chat.
 *
 * <p>Sectors have no persisted "unlocked" flag in Mindustry - reachability is derived from the
 * surrounding bases - so the reachable set is snapshotted and diffed instead. The snapshot is stored
 * in settings per planet, which also keeps a restart from re-announcing everything.
 */
public class AnnounceService{
    /** Platforms already announced per planet, stored as a comma separated id list. */
    private static final ObjectSet<Integer> known = new ObjectSet<>();
    private static Planet knownPlanet;
    /** Suppresses announcements during bulk operations such as "research all". */
    private static int suppressed;

    public static void init(){
        Events.on(UnlockEvent.class, event -> {
            announceResearch(event.content);
        });
    }

    /** Runs {@code runnable} without sending unlock announcements. */
    public static void suppress(Runnable runnable){
        suppressed ++;
        try{
            runnable.run();
        }finally{
            suppressed --;
        }
    }

    private static void announceResearch(UnlockableContent content){
        if(suppressed > 0 || !CampaignConfig.data().announceUnlocks || content == null) return;
        if(content.techNode == null) return;

        String message = I18n.get("campaign.announce.research", content.localizedName);
        Call.sendMessage(message);
        Log.info("[CampaignServer] " + message);
    }

    /** Diffs the reachable sector set of the given planet and announces the additions. */
    public static void scan(Planet planet){
        if(planet == null || !CampaignConfig.data().announceUnlocks) return;

        if(planet != knownPlanet){
            knownPlanet = planet;
            known.clear();
            known.addAll(load(planet));
        }

        ObjectSet<Integer> current = new ObjectSet<>();
        for(Sector sector : planet.sectors){
            if(sector != null && SectorService.canEnter(sector) && !sector.hasBase()){
                current.add(sector.id);
            }
        }

        //first run for this planet: remember silently, do not spam the chat
        if(known.isEmpty()){
            known.addAll(current);
            save(planet);
            return;
        }

        boolean changed = false;
        for(Sector sector : planet.sectors){
            if(sector == null || !current.contains(sector.id) || known.contains(sector.id)) continue;

            known.add(sector.id);
            changed = true;

            if(suppressed > 0) continue;

            String message = I18n.get("campaign.announce.sector", sector.name(), planet.localizedName);
            Call.sendMessage(message);
            Log.info("[CampaignServer] " + message);
        }

        if(changed) save(planet);
    }

    private static ObjectSet<Integer> load(Planet planet){
        ObjectSet<Integer> out = new ObjectSet<>();
        try{
            String stored = Core.settings.getString("campaign-unlocked-" + planet.name, null);
            if(stored == null || stored.isEmpty()) return out;

            for(String part : stored.split(",")){
                String trimmed = part.trim();
                if(trimmed.isEmpty()) continue;
                try{
                    out.add(Integer.parseInt(trimmed));
                }catch(NumberFormatException ignored){
                    //skip malformed entries
                }
            }
        }catch(Throwable ignored){
            //settings are best effort
        }
        return out;
    }

    private static void save(Planet planet){
        try{
            StringBuilder builder = new StringBuilder();
            for(Integer id : known){
                if(builder.length() > 0) builder.append(',');
                builder.append(id);
            }
            Core.settings.put("campaign-unlocked-" + planet.name, builder.toString());
            Core.settings.manualSave();
        }catch(Throwable ignored){
            //settings are best effort
        }
    }
}
