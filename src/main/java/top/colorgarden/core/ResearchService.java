// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.util.I18n;




import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.Content;
import arc.Core;
import arc.struct.ObjectMap;
import mindustry.type.Item;
import mindustry.type.ItemSeq;
import mindustry.type.ItemStack;
import mindustry.type.Sector;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.type.Planet;
import mindustry.type.SectorPreset;

import java.util.Locale;

import static mindustry.Vars.content;
import static mindustry.Vars.net;
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
            //display names now follow the client language, so match those as well
            if(I18n.content(unlockable).equalsIgnoreCase(raw)) return unlockable;
        }

        for(SectorPreset preset : content.sectors()){
            if(preset.name.equalsIgnoreCase(raw)) return preset;
            if(preset.localizedName != null && preset.localizedName.equalsIgnoreCase(raw)) return preset;
        }

        //partial match, so "钻" or "drill" finds 机械钻头 as long as only one entry matches
        Seq<UnlockableContent> hits = findMatches(raw, 3);
        return hits.size == 1 ? hits.first() : null;
    }

    /**
     * Partial (substring) search over content names and localized names.
     *
     * @return up to {@code limit} matches, empty when the query is blank
     */
    public static Seq<UnlockableContent> findMatches(@Nullable String query, int limit){
        Seq<UnlockableContent> out = new Seq<>();
        if(query == null || query.trim().isEmpty()) return out;

        String needle = query.trim().toLowerCase(Locale.ROOT);

        for(TechNode node : mindustry.content.TechTree.all){
            UnlockableContent unlockable = node.content;
            if(unlockable == null) continue;
            if(!matches(unlockable, needle)) continue;
            if(!out.contains(unlockable)) out.add(unlockable);
            if(out.size >= limit) break;
        }

        for(SectorPreset preset : content.sectors()){
            if(!matches(preset, needle)) continue;
            if(!out.contains(preset)) out.add(preset);
            if(out.size >= limit) break;
        }

        return out;
    }

    private static boolean matches(UnlockableContent content, String needle){
        return (content.name != null && content.name.toLowerCase(Locale.ROOT).contains(needle))
            || (content.localizedName != null && content.localizedName.toLowerCase(Locale.ROOT).contains(needle));
    }

    /** @return whether this content belongs to the planet (vanilla hides off-planet entries). */
    public static boolean shownOnPlanet(@Nullable UnlockableContent content, @Nullable Planet planet){
        if(content == null) return false;
        if(planet == null) return true;
        return content.shownPlanets.isEmpty() || content.shownPlanets.contains(planet);
    }

    /** @return "item x5, lead x5" for messages. */    public static String formatStacks(Seq<ItemStack> stacks){
        if(stacks == null || stacks.isEmpty()) return "-";

        StringBuilder builder = new StringBuilder();
        for(ItemStack stack : stacks){
            if(builder.length() > 0) builder.append(", ");
            builder.append(stack.item.localizedName).append(" x").append(stack.amount);
        }
        return builder.toString();
    }

    /**
     * @return a very short reason a node is not researchable yet (for list entries), or "" when it is.
     */
    public static String shortBlocker(@Nullable UnlockableContent content){
        if(content == null || content.unlocked()) return "";

        TechNode node = content.techNode;
        if(node == null) return "";

        //name the sector that gates it when the unmet objective is a sector completion
        if(node.objectives != null){
            for(var objective : node.objectives){
                if(objective.complete()) continue;

                String text = String.valueOf(objective);
                if(text.startsWith("sectorComplete: ")){
                    String raw = text.substring("sectorComplete: ".length()).trim();
                    SectorPreset preset = mindustry.Vars.content.sector(raw);
                    return I18n.get("campaign.vote.blocked.sector", preset != null ? preset.localizedName : raw);
                }
            }
        }

        int pending = pendingObjectives(node);
        if(pending > 0) return I18n.get("campaign.vote.blocked.objectives", pending);

        if(CampaignConfig.data().researchStrict){
            UnlockableContent parent = lockedParent(content);
            if(parent != null) return I18n.get("campaign.vote.blocked.parent", parent.localizedName);
        }

        return "";
    }

    /**
     * @return a reason why this content cannot be researched right now, or null when it can.
     *         Mirrors what the vanilla client enforces before it lets a player pay.
     */
    public static @Nullable String researchBlocker(@Nullable UnlockableContent content){
        if(content == null) return I18n.get("campaign.research.unknown", "-");
        if(content.unlocked()) return null;

        TechNode node = content.techNode;
        if(node == null) return null;

        int pending = pendingObjectives(node);
        if(pending > 0) return I18n.get("campaign.research.objectives", content.localizedName, pending);

        if(CampaignConfig.data().researchStrict){
            UnlockableContent parent = lockedParent(content);
            if(parent != null) return I18n.get("campaign.research.parent-locked", parent.localizedName);
        }

        return null;
    }

    /** @return the nearest ancestor in the tech tree that is still locked, or null when the path is clear. */    public static @Nullable UnlockableContent lockedParent(@Nullable UnlockableContent content){
        TechNode node = content == null ? null : content.techNode;

        for(TechNode parent = node == null ? null : node.parent; parent != null; parent = parent.parent){
            if(parent.content != null && !parent.content.unlocked()) return parent.content;
        }
        return null;
    }

    /**
     * Unlocks one piece of content.
     *
     * @param withParents also unlock everything above it in the tech tree, so the tree stays consistent.
     *                    Ignored in strict mode ({@code researchStrict}), where callers must check
     *                    {@link #lockedParent} instead.
     * @return true if anything was unlocked by this call.
     */
    public static boolean research(UnlockableContent unlockable, boolean withParents){
        if(unlockable == null) return false;

        boolean changed = false;

        if(withParents && !CampaignConfig.data().researchStrict){
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
            if(CampaignConfig.data().researchCosts && state.isCampaign()){
                if(!payAndUnlock(unlockable)){
                    //partially paid; the caller reports what is still missing
                    return changed;
                }
            }else{
                unlock(unlockable, true);
            }
            changed = true;
        }

        return changed;
    }

    /**
     * Mirrors {@code Control#checkAutoUnlocks}, which never runs on a headless server: content whose
     * parent is unlocked, that costs nothing to research and whose objectives (sector completions,
     * previous research) are all done gets unlocked automatically.
     *
     * @return how many entries were unlocked.
     */
    public static int checkAutoUnlocks(){
        if(net.client()) return 0;

        int count = 0;
        for(TechNode node : mindustry.content.TechTree.all){
            if(!autoUnlockable(node)) continue;

            unlock(node.content, false);
            count ++;
        }
        return count;
    }

    /**
     * @return how many of this node's objectives are still unmet. Vanilla gates research on these
     *         ({@code ResearchDialog} only allows a node when every objective is complete), so a
     *         server that ignores them lets players research content that requires a specific sector.
     */
    public static int pendingObjectives(@Nullable TechNode node){
        if(node == null || node.objectives == null) return 0;
        return node.objectives.count(objective -> !objective.complete());
    }

    /**
     * @return true when vanilla would unlock this node by itself: parent unlocked, no item cost and
     *         every objective already complete. Such content must never be "researched" by hand.
     */
    public static boolean autoUnlockable(@Nullable TechNode node){
        if(node == null || node.content == null) return false;
        if(node.content.unlocked()) return false;
        if(node.parent != null && !node.parent.content.unlocked()) return false;
        if(node.requirements != null && node.requirements.length > 0) return false;
        return pendingObjectives(node) == 0;
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

    /** @return the item cost of this content in the tech tree, empty when it has none. */
    public static ItemStack[] requirements(@Nullable UnlockableContent content){
        TechNode node = content == null ? null : content.techNode;
        return node == null || node.requirements == null ? new ItemStack[0] : node.requirements;
    }

    /** @return the part of the cost that was already paid (persisted in settings by the tech node). */    public static ItemStack[] progress(@Nullable UnlockableContent content){
        TechNode node = content == null ? null : content.techNode;
        return node == null || node.finishedRequirements == null ? new ItemStack[0] : node.finishedRequirements;
    }

    /** @return total items stored in every sector with a base on this planet. */
    public static ItemSeq planetItems(Planet planet){
        ItemSeq total = new ItemSeq();
        if(planet == null) return total;

        for(Sector sector : planet.sectors){
            if(sector == null || !sector.hasBase() || sector.isFrozen()) continue;
            total.add(sector.items());
        }
        return total;
    }

    /** @return per-minute production summed over every sector with a base on this planet. */
    public static ObjectMap<Item, Float> planetProduction(Planet planet){
        ObjectMap<Item, Float> rates = new ObjectMap<>();
        if(planet == null) return rates;

        for(Sector sector : planet.sectors){
            if(sector == null || !sector.hasBase() || sector.isFrozen()) continue;
            sector.info.production.each((item, stat) -> rates.put(item, rates.get(item, 0f) + stat.mean * 60f));
        }
        return rates;
    }

    /** @return per-minute exports summed over every sector with a base on this planet. */
    public static ObjectMap<Item, Float> planetExports(Planet planet){
        ObjectMap<Item, Float> rates = new ObjectMap<>();
        if(planet == null) return rates;

        for(Sector sector : planet.sectors){
            if(sector == null || !sector.hasBase() || sector.isFrozen()) continue;
            sector.info.export.each((item, stat) -> rates.put(item, rates.get(item, 0f) + stat.mean * 60f));
        }
        return rates;
    }

    /** @return the still unpaid part of the cost, comparing requirements with progress. */
    public static Seq<ItemStack> missing(@Nullable UnlockableContent content){
        Seq<ItemStack> out = new Seq<>();
        ItemStack[] requirements = requirements(content);
        ItemStack[] progress = progress(content);

        for(int i = 0; i < requirements.length; i++){
            int paid = i < progress.length ? progress[i].amount : 0;
            int remaining = requirements[i].amount - paid;
            if(remaining > 0) out.add(new ItemStack(requirements[i].item, remaining));
        }
        return out;
    }

    /** @return whether the planet's combined storage covers every still-missing item. */
    public static boolean affordable(@Nullable UnlockableContent content){
        Planet planet = state.getPlanet();
        if(planet == null) return false;

        ItemSeq stock = planetItems(planet);
        for(ItemStack stack : missing(content)){
            if(stock.get(stack.item) < stack.amount) return false;
        }
        return true;
    }

    /**
     * Removes items from the planet's combined storage, mirroring {@code ResearchDialog}'s removal
     * logic: every sector except the current one gives a proportional share, the current sector pays
     * only what the others could not cover.
     *
     * @return how many items were actually removed.
     */
    public static int takeFromPlanet(Planet planet, Item item, int amount){
        if(planet == null || item == null || amount <= 0) return 0;

        Sector currentSector = SectorService.current();
        if(currentSector != null && currentSector.planet != planet) currentSector = null;

        int available = 0;
        for(Sector sector : planet.sectors){
            if(sector == null || !sector.hasBase() || sector.isFrozen()) continue;
            available += Math.max(0, sector.items().get(item));
        }

        int take = Math.min(amount, available);
        if(take <= 0) return 0;

        int currentAmount = currentSector == null ? 0 : Math.max(0, currentSector.items().get(item));
        double percentage = (double)take / Math.max(available - currentAmount, 1);
        int[] counter = {take};

        for(Sector sector : planet.sectors){
            if(counter[0] == 0) break;
            if(sector == null || sector == currentSector || !sector.hasBase() || sector.isFrozen()) continue;

            int stored = Math.max(0, sector.items().get(item));
            if(stored <= 0) continue;

            int remove = Math.min(counter[0], Math.min((int)Math.ceil(percentage * stored), stored));
            sector.removeItem(item, remove);
            sector.saveInfo();
            counter[0] -= remove;
        }

        if(counter[0] > 0 && currentSector != null){
            int remove = Math.min(Math.max(0, currentSector.items().get(item)), counter[0]);
            currentSector.removeItem(item, remove);
            counter[0] -= remove;
        }

        return take - counter[0];
    }

    /**
     * Pays as much of the cost as the planet can afford and records the progress on the tech node,
     * exactly like {@code ResearchDialog#spend}.
     *
     * @return true when every requirement is now fulfilled and the content was unlocked.
     */
    public static boolean payAndUnlock(UnlockableContent content){
        TechNode node = content.techNode;
        if(node == null || node.requirements == null || node.requirements.length == 0){
            unlock(content, true);
            return true;
        }

        Planet planet = SectorService.planet();
        boolean complete = true;

        for(int i = 0; i < node.requirements.length; i++){
            ItemStack requirement = node.requirements[i];
            ItemStack done = node.finishedRequirements[i];
            int needed = requirement.amount - done.amount;
            if(needed <= 0) continue;

            int used = takeFromPlanet(planet, requirement.item, needed);
            done.amount += used;

            try{
                Core.settings.put("req-" + content.name + "-" + requirement.item.name, done.amount);
            }catch(Throwable ignored){
                //progress bookkeeping only
            }

            if(done.amount < requirement.amount) complete = false;
        }

        try{
            node.save();
        }catch(Throwable ignored){
            //progress bookkeeping only
        }

        if(complete){
            unlock(content, true);
        }
        return complete;
    }

    /** Locks content again. Connected clients keep seeing it until they reconnect. */
    public static void relock(UnlockableContent unlockable){
        if(unlockable == null) return;

        unlockable.clearUnlock();
        if(state.rules != null){
            state.rules.researched.remove(unlockable);
        }
    }

    /**
     * Locks every research entry again and clears the partial payments, then re-runs the vanilla
     * auto-unlock pass so the campaign's free starting content comes back. Connected clients keep
     * seeing the old tree until they reconnect.
     *
     * @return how many entries were relocked.
     */
    public static int relockAll(){
        int[] counter = {0};

        AnnounceService.suppress(() -> {
            for(TechNode node : TechTree.all){
                UnlockableContent target = node.content;
                if(target == null) continue;

                if(target.unlocked()) counter[0] ++;

                if(node.requirements != null && node.finishedRequirements != null){
                    for(int i = 0; i < node.requirements.length && i < node.finishedRequirements.length; i++){
                        ItemStack requirement = node.requirements[i];
                        if(requirement == null || node.finishedRequirements[i] == null) continue;

                        node.finishedRequirements[i].amount = 0;
                        Core.settings.remove("req-" + target.name + "-" + requirement.item.name);
                    }
                    try{
                        node.save();
                    }catch(Throwable ignored){
                        //progress bookkeeping only
                    }
                }

                relock(target);
            }

            //restore everything the campaign gives away for free (no requirements, objectives done)
            checkAutoUnlocks();
        });

        Log.info("[CampaignServer] " + I18n.get("campaign.log.relocked-all", counter[0]));
        return counter[0];
    }

    /** @return how many pieces of content were unlocked. */
    public static int researchAll(@Nullable Planet planet){
        //bulk unlock: do not flood the chat with one message per entry
        int[] counter = {0};
        AnnounceService.suppress(() -> counter[0] = researchAllQuiet(planet));
        return counter[0];
    }

    private static int researchAllQuiet(@Nullable Planet planet){
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

    /** @return every content entry that is unlocked right now, in tech tree order. */
    public static Seq<UnlockableContent> unlockedNow(){
        Seq<UnlockableContent> out = new Seq<>();
        for(TechNode node : TechTree.all){
            UnlockableContent target = node.content;
            if(target != null && target.unlocked()) out.add(target);
        }
        return out;
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
