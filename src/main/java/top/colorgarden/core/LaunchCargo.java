// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.util.I18n;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import mindustry.type.Item;
import mindustry.type.ItemSeq;
import mindustry.type.ItemStack;

import static mindustry.Vars.dataDirectory;
import static mindustry.Vars.state;

/**
 * The item cargo a new sector starts with.
 *
 * <p>Vanilla fills this from {@code Rules#loadout}, which the client builds in the launch dialog from
 * the origin sector's storage. A server has no such dialog, so the cargo is configured once and applied
 * when a sector is generated: the items go into the core (capped by its storage) and into
 * {@code state.rules.loadout} so vanilla's own code sees the same thing.</p>
 */
public class LaunchCargo{
    private static @Nullable Seq<ItemStack> cached;

    private static Fi file(){
        Fi folder = dataDirectory.child("campaign");
        folder.mkdirs();
        return folder.child("cargo.txt");
    }

    /** @return the configured cargo, empty when none was set. */
    public static Seq<ItemStack> current(){
        if(cached != null) return cached;

        Seq<ItemStack> out = new Seq<>();
        try{
            Fi file = file();
            if(file.exists()){
                for(String line : file.readString("UTF-8").split("\\R")){
                    String[] parts = line.trim().split("\\s+");
                    if(parts.length < 2) continue;

                    Item item = mindustry.Vars.content.item(parts[0]);
                    if(item == null || !Strings.canParsePositiveInt(parts[1])) continue;
                    out.add(new ItemStack(item, Strings.parseInt(parts[1])));
                }
            }
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.cargo.load-failed", String.valueOf(t)));
        }

        cached = out;
        return out;
    }

    /**
     * Parses and stores a cargo spec such as {@code copper 400 lead 200}.
     *
     * @return null on success, otherwise a player-readable reason.
     */
    public static @Nullable String set(String spec){
        Seq<ItemStack> parsed = new Seq<>();
        if(spec == null) spec = "";

        String cleaned = spec.replace(",", " ").trim();
        if(!cleaned.isEmpty()){
            String[] parts = cleaned.split("\\s+");
            if(parts.length % 2 != 0) return I18n.get("campaign.cargo.bad-format");

            for(int i = 0; i < parts.length; i += 2){
                Item item = mindustry.Vars.content.item(parts[i]);
                if(item == null) return I18n.get("campaign.cargo.unknown-item", parts[i]);
                if(!item.unlocked()) return I18n.get("campaign.cargo.locked-item", item.localizedName);
                if(!Strings.canParsePositiveInt(parts[i + 1])) return I18n.get("campaign.cargo.bad-amount", parts[i + 1]);

                parsed.add(new ItemStack(item, Math.min(clamp(Strings.parseInt(parts[i + 1])), 99999)));
            }
        }

        try{
            Fi file = file();
            StringBuilder builder = new StringBuilder();
            for(ItemStack stack : parsed){
                builder.append(stack.item.name).append(' ').append(stack.amount).append('\n');
            }
            file.writeString(builder.toString(), false, "UTF-8");
            cached = parsed;
            return null;
        }catch(Throwable t){
            return I18n.get("campaign.cargo.save-failed", String.valueOf(t));
        }
    }

    /**
     * @return how much one resource may carry, mirroring the vanilla launch dialog:
     *         {@code planet.launchCapacityMultiplier * core.itemCapacity}.
     */
    public static int capacity(){
        try{
            mindustry.type.Planet planet = SectorService.planet();
            if(planet == null) return 0;

            mindustry.world.blocks.storage.CoreBlock core = CoreBlueprints.selectedCore();
            if(core == null && planet.defaultCore instanceof mindustry.world.blocks.storage.CoreBlock dc) core = dc;

            return Math.max(0, (int)(planet.launchCapacityMultiplier * core.itemCapacity));
        }catch(Throwable ignored){
            return 0;
        }
    }

    /** @return the amount clamped to the launch capacity. */
    public static int clamp(int amount){
        int cap = capacity();
        int max = cap > 0 ? cap : 99999;
        return Math.max(0, Math.min(max, amount));
    }
    /** @return the configured amount of one item. */
    public static int amount(Item item){
        for(ItemStack stack : current()){
            if(stack.item == item) return stack.amount;
        }
        return 0;
    }

    /** Fills every unlocked item to the launch capacity. */
    public static void maxAll(){
        Seq<ItemStack> next = new Seq<>();
        int cap = capacity();
        if(cap <= 0) return;

        for(Item item : mindustry.Vars.content.items()){
            if(item.unlocked()) next.add(new ItemStack(item, cap));
        }

        persist(next);
    }
    /** Adds (or subtracts) an item amount and stores the result; 0 removes the entry. */
    public static void addAmount(Item item, int delta){
        Seq<ItemStack> existing = current();
        int amount = clamp(amount(item) + delta);

        Seq<ItemStack> next = new Seq<>();
        for(ItemStack stack : existing){
            if(stack.item != item) next.add(stack);
        }
        if(amount > 0) next.add(new ItemStack(item, amount));

        persist(next);
    }

    /** Writes the given cargo to disk and makes it current. */
    private static void persist(Seq<ItemStack> cargo){
        try{
            StringBuilder builder = new StringBuilder();
            for(ItemStack stack : cargo){
                builder.append(stack.item.name).append(' ').append(stack.amount).append('\n');
            }
            file().writeString(builder.toString(), false, "UTF-8");
            cached = cargo;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.cargo.save-failed", String.valueOf(t)));
        }
    }
    /** @return a short description for messages. */
    public static String describe(){
        Seq<ItemStack> cargo = current();
        if(cargo.isEmpty()) return I18n.get("campaign.cargo.none");

        StringBuilder builder = new StringBuilder();
        for(ItemStack stack : cargo){
            if(builder.length() > 0) builder.append(", ");
            builder.append(stack.item.localizedName).append(" x").append(stack.amount);
        }
        return builder.toString();
    }

    /**
     * Writes the cargo into {@code rules.loadout} and into the core that was just placed.
     *
     * @param capacity storage of the core, so the sum can be capped like vanilla does
     */
    /**
     * Takes the configured cargo out of the origin sector's stored items, like vanilla's launch does.
     *
     * @return the stacks that were actually carried (item, amount)
     */
    public static Seq<ItemStack> takeFrom(@Nullable mindustry.type.Sector origin, int capacity){
        Seq<ItemStack> carried = new Seq<>();
        if(origin == null) return carried;

        for(ItemStack stack : current()){
            int have = origin.info == null ? 0 : origin.info.items.get(stack.item);
            int take = Math.max(0, Math.min(stack.amount, Math.min(have, capacity)));
            if(take <= 0) continue;

            origin.info.items.remove(stack.item, take);
            carried.add(new ItemStack(stack.item, take));
        }

        return carried;
    }
    public static void apply(@Nullable mindustry.world.modules.ItemModule coreItems, int capacity, @Nullable mindustry.type.Sector origin){
        Seq<ItemStack> cargo = current();
        if(cargo.isEmpty()) return;

        //the launch carries what the origin sector already stores, never more
        Seq<ItemStack> carried = takeFrom(origin, capacity);
        if(carried.isEmpty() && origin != null){
            Log.info("[CampaignServer] " + I18n.get("campaign.cargo.empty-origin", origin.name()));
            return;
        }
        if(carried.isEmpty()) carried.addAll(cargo);

        if(state.rules != null){
            Seq<ItemStack> loadout = new Seq<>();
            loadout.addAll(carried);
            state.rules.loadout = loadout;
        }

        if(coreItems == null) return;

        for(ItemStack stack : carried){
            coreItems.add(stack.item, Math.min(stack.amount, Math.max(0, capacity - coreItems.get(stack.item))));
        }
    }
}
