// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.core;

import top.colorgarden.util.I18n;

import arc.files.Fi;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.dataDirectory;

/**
 * The core blueprint that new sectors start with.
 *
 * <p>Vanilla lets the launching player pick one of their own loadouts in {@code LaunchLoadoutDialog};
 * that list only exists on a client, so the server accepts the same thing the client can export: the
 * base64 text of a schematic. The legality rules are the ones the vanilla dialog applies - the
 * schematic must contain exactly one core and it must fit inside the core block it is used with.</p>
 */
public class CoreBlueprints{
    private static @Nullable Schematic cached;
    private static boolean loaded;

    private static Fi file(){
        Fi folder = dataDirectory.child("campaign");
        folder.mkdirs();
        return folder.child("core-schematic.txt");
    }

    /** @return the stored blueprint, or null when none was uploaded. */
    public static @Nullable Schematic current(){
        if(!loaded){
            loaded = true;
            cached = load();
        }
        return cached;
    }

    private static @Nullable Schematic load(){
        try{
            Fi file = file();
            if(!file.exists()) return null;

            String text = file.readString("UTF-8").trim();
            return text.isEmpty() ? null : Schematics.readBase64(text);
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.load-failed", String.valueOf(t)));
            return null;
        }
    }

    /** @return null when the text is a legal core blueprint, otherwise the reason it is not. */
    public static @Nullable String check(String base64){
        if(base64 == null || base64.trim().isEmpty()) return I18n.get("campaign.core.empty");

        Schematic schematic;
        try{
            schematic = Schematics.readBase64(base64.trim());
        }catch(Throwable t){
            //vanilla throws a RuntimeException from readBase64 for a corrupt or foreign string
            return I18n.get("campaign.core.invalid", String.valueOf(t.getMessage()));
        }

        if(schematic.tiles.isEmpty()) return I18n.get("campaign.core.empty-schematic");

        //same rule as LaunchLoadoutDialog: the loadout must contain a core and fit inside it
        CoreBlock core = schematic.findCore();
        if(core == null) return I18n.get("campaign.core.no-core");

        if(schematic.width > core.size || schematic.height > core.size){
            return I18n.get("campaign.core.too-big", schematic.width, schematic.height, core.size);
        }

        //every block has to be unlocked already, otherwise the sector would start with content the
        //campaign has not reached yet
        for(Schematic.Stile tile : schematic.tiles){
            if(tile.block == null || tile.block == mindustry.content.Blocks.air) continue;
            if(!tile.block.unlocked()){
                return I18n.get("campaign.core.locked", tile.block.localizedName);
            }
        }

        return null;
    }

    /** Stores the blueprint. The caller must have run {@link #check(String)} first. */
    public static void save(String base64){
        try{
            Fi file = file();
            file.writeString(base64.trim(), false, "UTF-8");

            cached = Schematics.readBase64(base64.trim());
            loaded = true;

            Log.info("[CampaignServer] " + I18n.get("campaign.core.saved", cached.width, cached.height));
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.save-failed", String.valueOf(t)));
        }
    }

    /** Removes the stored blueprint. */
    public static void clear(){
        try{
            Fi file = file();
            if(file.exists()) file.delete();
        }catch(Throwable ignored){
            //best effort
        }

        cached = null;
        loaded = true;
    }

    /** @return a short description of the stored blueprint for messages. */
    public static String describe(){
        Schematic schematic = current();
        if(schematic == null) return I18n.get("campaign.core.none");

        CoreBlock core = schematic.findCore();
        return I18n.get("campaign.core.current", schematic.width, schematic.height,
            core == null ? "-" : core.localizedName);
    }

    /** File that remembers which single core block a launched sector should use. */
    private static Fi coreFile(){
        Fi folder = dataDirectory.child("campaign");
        folder.mkdirs();
        return folder.child("core-block.txt");
    }

    /** @return the core block chosen for new sectors, or null when the planet default is used. */
    public static @Nullable CoreBlock selectedCore(){
        try{
            Fi file = coreFile();
            if(!file.exists()) return null;

            String name = file.readString("UTF-8").trim();
            if(name.isEmpty()) return null;

            var block = mindustry.Vars.content.block(name);
            if(block instanceof CoreBlock core) return core;
        }catch(Throwable ignored){
            //fall back to the planet default
        }
        return null;
    }

    /** Stores the chosen core block by name; an empty name clears the choice. */
    public static boolean setCore(@Nullable String name){
        try{
            Fi file = coreFile();
            if(name == null || name.isEmpty()){
                if(file.exists()) file.delete();
                return true;
            }

            var block = mindustry.Vars.content.block(name);
            if(!(block instanceof CoreBlock core) || !core.unlocked()) return false;

            file.writeString(core.name, false, "UTF-8");
            return true;
        }catch(Throwable t){
            Log.err("[CampaignServer] " + I18n.get("campaign.core.save-failed", String.valueOf(t)));
            return false;
        }
    }

    /** @return every core block that is unlocked and belongs to the planet, in tier order. */
    public static arc.struct.Seq<CoreBlock> unlockedCores(mindustry.type.Planet planet){
        arc.struct.Seq<CoreBlock> out = new arc.struct.Seq<>();

        for(var block : mindustry.Vars.content.blocks()){
            if(!(block instanceof CoreBlock core)) continue;
            if(!core.unlocked()) continue;
            if(planet != null && !core.shownPlanets.isEmpty() && !core.shownPlanets.contains(planet)) continue;
            out.add(core);
        }

        out.sortComparing(core -> core.itemCapacity);
        return out;
    }

    /** @return a short description of the chosen core for messages. */
    public static String describeCore(){
        CoreBlock core = selectedCore();
        return I18n.get("campaign.core.current-core", core == null ? "-" : core.localizedName);
    }}
