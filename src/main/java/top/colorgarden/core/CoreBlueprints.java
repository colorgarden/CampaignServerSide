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
}
