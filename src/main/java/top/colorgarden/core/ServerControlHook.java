// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;




import arc.func.Cons;
import arc.util.Log;
import mindustry.game.EventType.GameOverEvent;

import java.lang.reflect.Field;

import static mindustry.Vars.state;

/**
 * {@code mindustry.server.ServerControl} only exists in the {@code server} artifact, which mods do
 * not compile against, so the vanilla game-over behaviour is replaced through reflection.
 *
 * <p>Without this, a campaign server destroys its own world when the last core falls: the vanilla
 * listener announces a game over, picks the "next map" and loads it, which is not what a campaign
 * should do.</p>
 */
public class ServerControlHook{
    public static boolean installed;

    /**
     * Replaces {@code ServerControl#gameOverListener} with a handler that delegates back to the
     * vanilla listener whenever the server is not playing a campaign sector.
     */
    @SuppressWarnings("unchecked")
    public static boolean install(Cons<GameOverEvent> campaignHandler){
        try{
            Class<?> type = Class.forName("mindustry.server.ServerControl");
            Object instance = type.getField("instance").get(null);
            if(instance == null){
                Log.warn("[CampaignServer] ServerControl is not available; the game-over hook was not installed.");
                return false;
            }

            Field listenerField = type.getField("gameOverListener");
            Cons<GameOverEvent> original = (Cons<GameOverEvent>)listenerField.get(instance);

            listenerField.set(instance, (Cons<GameOverEvent>)event -> {
                if(state.rules != null && state.rules.sector != null){
                    campaignHandler.get(event);
                }else if(original != null){
                    original.get(event);
                }
            });

            installed = true;
            Log.info("[CampaignServer] Game-over hook installed.");
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] Could not install the game-over hook; a campaign game over will rotate to another map.", t);
            return false;
        }
    }
}
