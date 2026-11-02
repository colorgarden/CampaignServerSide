// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.util.I18n;




import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.type.Planet;
import mindustry.type.SectorPreset;

import java.util.Locale;

import static mindustry.Vars.content;
import static mindustry.Vars.state;

/**
 * Unlocking research on the server.
 *
 * <p>In the campaign, unlocked content is tracked globally in {@code Core.settings}
 * ({@code <name>-unlocked}); multiplayer clients instead read {@code rules.researched}.
 * Vanilla keeps both in sync: {@code UnlockableContent#unlock()} fires an {@code UnlockEvent},
 * which {@code Logic} turns into {@code Call.researched} for every client. This class only has to
 * call {@code unlock()} on the server and keep {@code rules.researched} populated.</p>
 */
public class ResearchService{
    public static void init(){
        //nothing to do - research is stored by the game itself
    }

    /** Finds content by internal name, localized name or tech tree name. */
    public static @Nullable UnlockableContent find(@Nullable String query){
        if(query == null || query.trim().isEmpty()) return null;

        String raw = query.trim();
        String lower = raw.toLowerCase(Locale.ROOT);

        for(ContentType type : ContentType.all){
            Object found;
            try{
                found = content.getByName(type, lower);
            }catch(Throwable ignored){
                continue;
            }
            if(found instanceof UnlockableContent unlockable){
                return unlockable;
            }
        }

        for(TechNode node : mindustry.content.TechTree.all){
            UnlockableContent unlockable = node.content;
            if(unlockable == null) continue;
            if(unlockable.name.equalsIgnoreCase(raw)) return unlockable;
            if(unlockable.localizedName != null && unlockable.localizedName.equalsIgnoreCase(raw)) return unlockable;
        }

        for(SectorPreset preset : content.sectors()){
            if(preset.name.equalsIgnoreCase(raw)) return preset;
            if(preset.localizedName != null && preset.localizedName.equalsIgnoreCase(raw)) return preset;
        }

        return null;
    }

    /**
     * Unlocks one piece of content.
     *
     * @param withParents also unlock everything above it in the tech tree, so the tree stays consistent.
     * @return true if anything was unlocked by this call.
     */
    public static boolean research(UnlockableContent unlockable, boolean withParents){
        if(unlockable == null) return false;

        boolean changed = false;

        if(withParents){
            TechNode node = unlockable.techNode;
            Seq<TechNode> parents = new Seq<>();
            for(TechNode parent = node == null ? null : node.parent; parent != null; parent = parent.parent){
                parents.add(parent);
            }
            //unlock from the root down, so children of a node are always processed after it
            parents.reverse();
            for(TechNode parent : parents){
                if(parent.content != null && !parent.content.unlocked()){
                    unlock(parent.content, true);
                    changed = true;
                }
            }
        }

        if(!unlockable.unlocked()){
            unlock(unlockable, true);
            changed = true;
        }

        return changed;
    }

    /** Unlocks a single piece of content without touching its parents. */
    public static void unlock(UnlockableContent unlockable, boolean completeNode){
        TechNode node = unlockable.techNode;
        if(completeNode && node != null && node.requirements != null && node.finishedRequirements != null){
            for(int i = 0; i < node.requirements.length && i < node.finishedRequirements.length; i++){
                if(node.finishedRequirements[i] != null && node.requirements[i] != null){
                    node.finishedRequirements[i].amount = node.requirements[i].amount;
                }
            }
            try{
                node.save();
            }catch(Throwable ignored){
                //progress bookkeeping only
            }
        }

        //fires UnlockEvent; Logic sends Call.researched to all clients when hosting
        unlockable.unlock();

        if(state.rules != null){
            state.rules.researched.add(unlockable);
        }
    }

    /** Locks content again. Connected clients keep seeing it until they reconnect. */
    public static void relock(UnlockableContent unlockable){
        if(unlockable == null) return;

        unlockable.clearUnlock();
        if(state.rules != null){
            state.rules.researched.remove(unlockable);
        }
    }

    /** @return how many pieces of content were unlocked. */
    public static int researchAll(@Nullable Planet planet){
        int count = 0;

        for(ContentType type : ContentType.all){
            if(type == ContentType.planet) continue;

            Seq<Content> all = content.getBy(type);
            if(all == null) continue;

            for(Content c : all){
                if(!(c instanceof UnlockableContent unlockable)) continue;
                if(planet != null && !unlockable.shownPlanets.isEmpty() && !unlockable.shownPlanets.contains(planet)) continue;
                if(unlockable.techNode == null) continue;
                if(unlockable.unlocked()) continue;

                unlock(unlockable, true);
                count ++;
            }
        }

        Log.info("[CampaignServer] " + I18n.get("campaign.log.research-all", count));
        return count;
    }

    /** @return how much content relevant to this planet is still locked. */
    public static int lockedCount(@Nullable Planet planet){
        int count = 0;

        for(ContentType type : ContentType.all){
            if(type == ContentType.planet) continue;

            Seq<Content> all = content.getBy(type);
            if(all == null) continue;

            for(Content c : all){
                if(!(c instanceof UnlockableContent unlockable)) continue;
                if(unlockable.techNode == null) continue;
                if(planet != null && !unlockable.shownPlanets.isEmpty() && !unlockable.shownPlanets.contains(planet)) continue;
                if(!unlockable.unlocked()) count ++;
            }
        }

        return count;
    }
}
