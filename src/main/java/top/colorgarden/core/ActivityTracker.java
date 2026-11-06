// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.config.CampaignConfig;

import arc.math.geom.Vec2;
import arc.struct.ObjectFloatMap;
import arc.struct.ObjectMap;
import arc.util.Nullable;
import arc.util.Time;
import mindustry.gen.Groups;
import mindustry.gen.Player;

/**
 * Tracks which players are idle.
 *
 * <p>A player counts as AFK once they have not moved a single tile for {@code afkSeconds}. Idle players
 * are skipped when the vote quorum is computed and are not sent the ballot, so one person standing in
 * a corner cannot make a vote impossible to pass.</p>
 */
public class ActivityTracker{
    private static final ObjectFloatMap<Player> idle = new ObjectFloatMap<>();
    private static final ObjectMap<Player, Vec2> last = new ObjectMap<>();

    /** Called from the tick hook. */
    public static void update(){
        if(Groups.player == null) return;

        for(Player player : Groups.player){
            boolean wasAfk = isAfk(player);
            Vec2 previous = last.get(player);

            if(previous == null || previous.dst(player.x, player.y) > 1f){
                if(previous == null) previous = new Vec2();
                previous.set(player.x, player.y);
                last.put(player, previous);
                idle.put(player, 0f);
            }else{
                idle.put(player, idle.get(player, 0f) + Time.delta);
            }

            //announce the transition so everybody knows why that player is skipped
            if(!wasAfk && isAfk(player)){
                mindustry.gen.Call.sendMessage(top.colorgarden.util.I18n.get("campaign.afk.announced", player.plainName()));
            }
        }

        //drop players that left
        arc.struct.Seq<Player> stale = new arc.struct.Seq<>();
        for(Player player : last.keys()){
            if(player == null || !player.isAdded()) stale.add(player);
        }
        for(Player player : stale){
            last.remove(player);
            idle.remove(player, 0f);
        }
    }

    /** @return whether this player has been idle long enough to be ignored for votes. */
    public static boolean isAfk(@Nullable Player player){
        if(player == null || !CampaignConfig.data().afkEnabled) return false;
        return idle.get(player, 0f) >= CampaignConfig.data().afkSeconds * 60f;
    }

    /** @return how many online players are not AFK (at least 1 while anyone is online). */
    public static int activeCount(){
        if(!CampaignConfig.data().afkEnabled) return Groups.player.size();

        int count = 0;
        for(Player player : Groups.player){
            if(!isAfk(player)) count ++;
        }

        //never report 0 while the server is not empty, otherwise a vote could pass unopposed
        return Math.max(1, count);
    }

    /** Forgets a player, e.g. when they leave. */
    public static void forget(@Nullable Player player){
        if(player == null) return;
        last.remove(player);
        idle.remove(player, 0f);
    }
}
