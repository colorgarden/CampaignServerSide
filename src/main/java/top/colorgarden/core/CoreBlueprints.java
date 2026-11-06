// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.util.I18n;

import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.type.Planet;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.dataDirectory;

/**
 * The blueprint library a new sector can start from.
 *
 * <p>Vanilla picks a loadout from the client's own schematic list. A server cannot see that list, so
 * players paste the base64 text the game exports; the plugin stores every accepted blueprint and
 * remembers which one is the launch loadout. A blueprint that contains only a core is exactly what
 * vanilla uses as the "core type" choice, so the single-core presets are generated here instead of
 * being kept as a separate setting.</p>
 */
public class CoreBlueprints{
    /** One selectable blueprint. */
    public static class Entry{
        public final String name;
        public final Schematic schematic;
        /** True when this is a generated "core only" preset, not an uploaded blueprint. */
        public final boolean singleCore;

        public Entry(String name, Schematic schematic, boolean singleCore){
            this.name = name;
            this.schematic = schematic;
            this.singleCore = singleCore;
        }

        public String describe(){
            return name + " [" + schematic.width + "x" + schematic.height + "]";
        }
    }

    /** Tiles a loadout may extend past the core on each side ({@code Schematics#maxLoadoutSchematicPad}). */
    private static final int MAX_PAD = 10;

    private static @Nullable Seq<Entry> cache;
    private static @Nullable String selected;

    private static Fi folder(){
        Fi dir = dataDirectory.child("campaign").child("blueprints");
        dir.mkdirs();
        return dir;
    }

    private static Fi selectionFile(){
        Fi dir = dataDirectory.child("campaign");
        dir.mkdirs();
        return dir.child("selected-blueprint.txt");
    }

    /** @return every selectable blueprint: generated single-core presets first, then uploaded ones. */
    public static Seq<Entry> entries(){
        if(cache != null) return cache;

        Seq<Entry> out = new Seq<>();

        Planet planet = SectorService.planet();
        for(CoreBlock core : unlockedCores(planet)){
            out.add(new Entry(I18n.get("campaign.core.single", core.localizedName), singleCore(core), true));
        }

        for(Fi file : folder().list()){
            if(!file.extension().equals("msch")) continue;

            try{
                String text = file.readString("UTF-8").trim();
                if(text.isEmpty()) continue;

                out.add(new Entry(file.nameWithoutExtension(), Schematics.readBase64(text), false));
            }catch(Throwable t){
                Log.err("[CampaignServer] " + I18n.get("campaign.core.load-failed", file.name()));
            }
        }

        cache = out;
        return out;
    }

    /** Builds the 1x1 schematic that only contains the given core. */
    private static Schematic singleCore(CoreBlock core){
        Seq<Schematic.Stile> tiles = new Seq<>();
        tiles.add(new Schematic.Stile(core, 0, 0, null, (byte)0));
        return new Schematic(tiles, new StringMap(), 1, 1);
    }

    /** @return the selected blueprint, or null when none was chosen yet. */
    public static @Nullable Entry selectedEntry(){
        Seq<Entry> all = entries();
        if(all.isEmpty()) return null;

        if(selected == null){
            try{
                Fi file = selectionFile();
                selected = file.exists() ? file.readString("UTF-8").trim() : "";
            }catch(Throwable ignored){
                selected = "";
            }
        }

        for(Entry entry : all){
            if(entry.name.equals(selected)) return entry;
        }

        return null;
    }

    /** Remembers the selected blueprint by name. */
    public static void select(@Nullable String name){
        selected = name == null ? "" : name;

        try{
            selectionFile().writeString(selected, false, "UTF-8");
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.save-failed", String.valueOf(t)));
        }
    }

    /** @return the core block of the selected blueprint, or null when nothing is selected. */
    public static @Nullable CoreBlock selectedCore(){
        Entry entry = selectedEntry();
        return entry == null ? null : entry.schematic.findCore();
    }

    /** @return every core block that is unlocked and belongs to the planet, in tier order. */
    public static Seq<CoreBlock> unlockedCores(@Nullable Planet planet){
        Seq<CoreBlock> out = new Seq<>();

        for(var block : Vars.content.blocks()){
            if(!(block instanceof CoreBlock core)) continue;
            if(!core.unlocked()) continue;
            if(planet != null && !core.shownPlanets.isEmpty() && !core.shownPlanets.contains(planet)) continue;
            out.add(core);
        }

        out.sortComparing(core -> core.itemCapacity);
        return out;
    }

    /** @return null when the text is a legal blueprint, otherwise the reason it is not. */
    public static @Nullable String check(String base64){
        if(base64 == null || base64.trim().isEmpty()) return I18n.get("campaign.core.empty");

        Schematic schematic;
        try{
            schematic = Schematics.readBase64(base64.trim());
        }catch(Throwable t){
            return I18n.get("campaign.core.invalid", String.valueOf(t.getMessage()));
        }

        if(schematic.tiles.isEmpty()) return I18n.get("campaign.core.empty-schematic");

        CoreBlock core = schematic.findCore();
        if(core == null) return I18n.get("campaign.core.no-core");

        //vanilla allows a loadout to reach maxLoadoutSchematicPad tiles past the core on each side
        int maxSize = core.size + MAX_PAD * 2;
        if(schematic.width > maxSize || schematic.height > maxSize){
            return I18n.get("campaign.core.too-big", schematic.width, schematic.height, maxSize);
        }

        for(Schematic.Stile tile : schematic.tiles){
            if(tile.block == null || tile.block == mindustry.content.Blocks.air) continue;
            if(!tile.block.unlocked()){
                return I18n.get("campaign.core.locked", tile.block.localizedName);
            }
        }

        return null;
    }

    /**
     * Stores a blueprint in the library.
     *
     * @return the stored name, or null when the text is not a legal blueprint.
     */
    public static @Nullable String add(String base64){
        if(check(base64) != null) return null;

        try{
            Schematic schematic = Schematics.readBase64(base64.trim());
            String name = schematic.tags.get("name");
            if(name == null || name.trim().isEmpty()){
                name = I18n.get("campaign.core.default-name", folder().list().length + 1);
            }

            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            folder().child(safe + ".msch").writeString(base64.trim(), false, "UTF-8");

            cache = null;
            return safe;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.save-failed", String.valueOf(t)));
            return null;
        }
    }

    /** Deletes a stored blueprint; generated single-core presets cannot be removed. */
    public static void remove(String name){
        try{
            folder().child(name + ".msch").delete();
            cache = null;
        }catch(Throwable ignored){
            //best effort
        }
    }

    /** @return a short description of the selected blueprint for messages. */
    public static String describe(){
        Entry entry = selectedEntry();
        return entry == null ? I18n.get("campaign.core.none") : I18n.get("campaign.core.current", entry.describe());
    }
}