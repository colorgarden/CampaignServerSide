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
        /** File name without extension for uploaded blueprints; null for the generated presets. */
        public final @Nullable String slug;
        /** Planet this blueprint belongs to; cores differ between planets, so the lists must too. */
        public final @Nullable Planet planet;

        public Entry(String name, Schematic schematic, boolean singleCore){
            this(name, schematic, singleCore, null);
        }

        public Entry(String name, Schematic schematic, boolean singleCore, @Nullable String slug){
            this(name, schematic, singleCore, slug, SectorService.planet());
        }

        public Entry(String name, Schematic schematic, boolean singleCore, @Nullable String slug,
                     @Nullable Planet planet){
            this.name = name;
            this.schematic = schematic;
            this.singleCore = singleCore;
            this.slug = slug;
            this.planet = planet;
        }

        /** @return true when this blueprint was uploaded, so it may be renamed or deleted. */
        public boolean editable(){
            return !singleCore && slug != null;
        }

        public String describe(){
            return name + " [" + schematic.width + "x" + schematic.height + "]";
        }
    }

    /** Tiles a loadout may extend past the core on each side ({@code Schematics#maxLoadoutSchematicPad}). */
    private static final int MAX_PAD = 5;

    /** @return the per-side pad used for the blueprint footprint limit. */
    public static int pad(){
        return MAX_PAD;
    }

    /** Cached blueprint list per planet name; blueprints are planet specific (cores differ). */
    private static final arc.struct.ObjectMap<String, Seq<Entry>> caches = new arc.struct.ObjectMap<>();
    /** Selected blueprint name per planet name. */
    private static final arc.struct.ObjectMap<String, String> selections = new arc.struct.ObjectMap<>();

    /** @return a stable directory name for the planet, falling back to {@code serpulo}. */
    private static String planetKey(@Nullable Planet planet){
        Planet target = planet != null ? planet : SectorService.planet();
        return target == null ? "serpulo" : target.name;
    }

    /**
     * @return the blueprint folder of one planet.
     *
     * <p>Erekir and Serpulo have different core blocks, so a single library let players launch into
     * the other planet with a blueprint that cannot exist there. Each planet now gets its own folder;
     * the old shared folder is copied over once so nothing is lost.</p>
     */
    private static Fi folderFor(@Nullable Planet planet){
        Fi dir = dataDirectory.child("campaign").child("blueprints").child(planetKey(planet));
        dir.mkdirs();

        try{
            Fi legacy = dataDirectory.child("campaign").child("blueprints");
            if(legacy.exists() && dir.list().length == 0){
                int copied = 0;
                for(Fi file : legacy.list()){
                    if(file.isDirectory() || !file.extension().equals("msch")) continue;
                    file.copyTo(dir);
                    Fi sidecar = file.parent().child(file.nameWithoutExtension() + ".name");
                    if(sidecar.exists()) sidecar.copyTo(dir);
                    copied ++;
                }
                if(copied > 0){
                    Log.info("[CampaignServer] migrated @ blueprint(s) into '@'", copied, planetKey(planet));
                }
            }
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.load-failed", String.valueOf(t)));
        }

        return dir;
    }

    /** @return the blueprint folder of the current planet. */
    private static Fi folder(){
        return folderFor(SectorService.planet());
    }

    private static Fi selectionFile(@Nullable Planet planet){
        Fi dir = dataDirectory.child("campaign");
        dir.mkdirs();

        Fi file = dir.child("selected-blueprint-" + planetKey(planet) + ".txt");
        if(file.exists()) return file;

        //one-time migration from the single global selection file
        try{
            Fi legacy = dir.child("selected-blueprint.txt");
            if(legacy.exists()){
                file.writeString(legacy.readString("UTF-8"), false, "UTF-8");
            }
        }catch(Throwable ignored){
            //a failed migration only costs the remembered choice
        }

        return file;
    }

    /** @return the readable name of a stored blueprint, falling back to the file name. */
    private static String displayName(Fi file){
        try{
            Fi sidecar = file.parent().child(file.nameWithoutExtension() + ".name");
            if(sidecar.exists()){
                String name = sidecar.readString("UTF-8").trim();
                if(!name.isEmpty()) return name;
            }
        }catch(Throwable ignored){
            //a missing sidecar only costs the pretty name
        }
        return file.nameWithoutExtension();
    }

    /** @return every selectable blueprint: generated single-core presets first, then uploaded ones. */
    public static Seq<Entry> entries(){
        String key = planetKey(null);
        if(caches.containsKey(key)) return caches.get(key);

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

                //the file name is ASCII-only; the human readable name lives in a sidecar file because
                //JVM file names go through sun.jnu.encoding and turn Chinese into "?"
                Schematic schematic = Schematics.readBase64(text);
                CoreBlock core = schematic.findCore();

                //a core from the other planet cannot be built here, so such a file is skipped instead
                //of being offered (that is what carried a Serpulo core into an Erekir launch)
                if(core != null && planet != null && !core.shownPlanets.isEmpty()
                    && !core.shownPlanets.contains(planet)){
                    continue;
                }

                out.add(new Entry(displayName(file), schematic, false, file.nameWithoutExtension()));
            }catch(Throwable t){
                Log.err("[CampaignServer] " + I18n.get("campaign.core.load-failed", file.name()));
            }
        }

        caches.put(key, out);
        return out;
    }

    /** Builds the 1x1 schematic that only contains the given core. */
    private static Schematic singleCore(CoreBlock core){
        Seq<Schematic.Stile> tiles = new Seq<>();
        tiles.add(new Schematic.Stile(core, 0, 0, null, (byte)0));
        return new Schematic(tiles, new StringMap(), 1, 1);
    }

    /**
     * Renames an uploaded blueprint. The generated single-core presets are fixed, so this refuses them.
     *
     * @return true when the new name was stored
     */
    public static boolean rename(Entry entry, @Nullable String newName){
        if(entry == null || !entry.editable() || newName == null) return false;
        String name = newName.trim();
        if(name.isEmpty() || name.length() > 64) return false;

        try{
            folder().child(entry.slug + ".name").writeString(name, false, "UTF-8");
            if(entry.name.equals(selectedOf(entry.planet))) select(name);
            caches.remove(planetKey(entry.planet));
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.rename-failed", String.valueOf(t)));
            return false;
        }
    }

    /**
     * Deletes an uploaded blueprint and its sidecar name. The generated presets cannot be deleted.
     *
     * @return true when the files were removed
     */
    public static boolean remove(Entry entry){
        if(entry == null || !entry.editable()) return false;

        try{
            Fi schematic = folder().child(entry.slug + ".msch");
            Fi sidecar = folder().child(entry.slug + ".name");
            if(schematic.exists()) schematic.delete();
            if(sidecar.exists()) sidecar.delete();

            if(entry.name.equals(selectedOf(entry.planet))) select(null);
            caches.remove(planetKey(entry.planet));
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.remove-failed", String.valueOf(t)));
            return false;
        }
    }
    /** @return the selected blueprint, or null when none was chosen yet. */
    public static @Nullable Entry selectedEntry(){
        Seq<Entry> all = entries();
        if(all.isEmpty()) return null;

        String selected = selectedOf(SectorService.planet());
        if(selected.isEmpty()) return null;

        for(Entry entry : all){
            if(entry.name.equals(selected)) return entry;
        }

        return null;
    }

    /** @return the remembered blueprint name of a planet, loaded from disk once. */
    private static String selectedOf(@Nullable Planet planet){
        String key = planetKey(planet);
        String cached = selections.get(key);
        if(cached != null) return cached;

        String value = "";
        try{
            Fi file = selectionFile(planet);
            value = file.exists() ? file.readString("UTF-8").trim() : "";
        }catch(Throwable ignored){
            value = "";
        }

        selections.put(key, value);
        return value;
    }

    /** Remembers the selected blueprint by name. */
    public static void select(@Nullable String name){
        Planet planet = SectorService.planet();
        String value = name == null ? "" : name;
        selections.put(planetKey(planet), value);

        try{
            selectionFile(planet).writeString(value, false, "UTF-8");
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

            //file names must stay ASCII: sun.jnu.encoding on a POSIX-locale server turns any other
            //character into "?", which is exactly how a Chinese blueprint name became "??"
            String slug = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
            if(slug.replace("_", "").isEmpty()) slug = "blueprint";
            String base = slug;
            for(int i = 2; folder().child(base + ".msch").exists(); i++){
                base = slug + "-" + i;
            }

            Fi file = folder().child(base + ".msch");
            file.writeString(base64.trim(), false, "UTF-8");
            //the readable name is stored next to it, in UTF-8
            folder().child(base + ".name").writeString(name.trim(), false, "UTF-8");

            caches.remove(planetKey(null));
            return name.trim();
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.save-failed", String.valueOf(t)));
            return null;
        }
    }

    /** Deletes a stored blueprint; generated single-core presets cannot be removed. */
    public static void remove(String name){
        try{
            folder().child(name + ".msch").delete();
            caches.remove(planetKey(null));
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
