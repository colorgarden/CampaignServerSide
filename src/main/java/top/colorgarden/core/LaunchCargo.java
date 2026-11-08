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

    /**
     * @return how much of this item the origin sector can actually give right now; vanilla's max
     *         button is limited by what the launching sector stores, not only by the core capacity.
     */
    public static int available(Item item){
        try{
            mindustry.type.Sector origin = SectorService.current();
            if(origin == null || origin.info == null) return capacity();
            return Math.max(0, origin.info.items.get(item));
        }catch(Throwable ignored){
            return capacity();
        }
    }

    /** @return what the selected blueprint itself costs to build. */
    public static mindustry.type.ItemSeq requirements(){
        try{
            CoreBlueprints.Entry entry = CoreBlueprints.selectedEntry();
            return entry == null ? new mindustry.type.ItemSeq() : entry.schematic.requirements();
        }catch(Throwable ignored){
            return new mindustry.type.ItemSeq();
        }
    }

    /**
     * @return the real maximum for one item, mirroring vanilla's max button:
     *         {@code clamp(localStock - blueprintCost, 0, capacity)}.
     */
    public static int maxFor(Item item){
        int cap = capacity();
        int usable = Math.max(0, available(item) - requirements().get(item));
        return cap > 0 ? Math.min(cap, usable) : usable;
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
            if(!item.unlocked()) continue;
            int max = maxFor(item);
            if(max > 0) next.add(new ItemStack(item, max));
        }

        persist(next);
    }
    /** Adds (or subtracts) an item amount and stores the result; 0 removes the entry. */
    public static void addAmount(Item item, int delta){
        Seq<ItemStack> existing = current();
        int amount = Math.max(0, Math.min(maxFor(item), amount(item) + delta));

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
     * @return the cargo as icon+amount cells, three per row.
     *
     * <p>A comma separated sentence wrapped into a block of text in the 400px dialog, which is exactly
     * what the sector panel looked like before its grid. The dialog label wraps, so the padding has to
     * be computed in display columns (CJK counts double) instead of string length.</p>
     */
    public static String describeGrid(){
        Seq<ItemStack> cargo = current();
        if(cargo.isEmpty()) return I18n.get("campaign.cargo.none");

        final int perRow = 3, columnWidth = 10;
        StringBuilder out = new StringBuilder();

        for(int i = 0; i < cargo.size; i++){
            ItemStack stack = cargo.get(i);
            String value = "x" + stack.amount;
            String cell = "[white]" + top.colorgarden.util.Icons.item(stack.item) + "[white] x"
                + padLeft(value.substring(1), 5);

            if(i % perRow == 0){
                if(i > 0) out.append('\n');
            }else{
                out.append("  ");
            }

            out.append(cell);
            boolean lastInRow = i % perRow == perRow - 1 || i == cargo.size - 1;
            if(!lastInRow){
                for(int pad = displayWidth(cell); pad < columnWidth; pad++) out.append(' ');
            }
        }

        return out.toString();
    }

    /** Pads on the left with spaces until the text is at least {@code width} display columns wide. */
    private static String padLeft(String text, int width){
        int current = displayWidth(text);
        if(current >= width) return text;

        StringBuilder out = new StringBuilder();
        for(int i = current; i < width; i++) out.append(' ');
        return out.append(text).toString();
    }

    /** @return the rendered width of a markup string, counting CJK (and full-width) glyphs as two. */
    private static int displayWidth(String markup){
        String plain = Strings.stripColors(markup);
        int width = 0;

        for(int i = 0; i < plain.length(); i++){
            char c = plain.charAt(i);
            width += (c >= 0x1100 && (c <= 0x115F || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F) || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6))) ? 2 : 1;
        }

        return width;
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
            int take = Math.max(0, Math.min(stack.amount, capacity));
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
