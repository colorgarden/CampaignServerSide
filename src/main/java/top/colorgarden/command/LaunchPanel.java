// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.command;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.CoreBlueprints;
import top.colorgarden.core.LaunchCargo;
import top.colorgarden.core.SectorService;
import top.colorgarden.util.I18n;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Sector;
import mindustry.ui.Menus;

/**
 * The launch configuration dialog: shown before a sector is generated for the first time.
 *
 * <p>Vanilla asks the launching player for a core type, a loadout schematic and the item cargo in
 * {@code LaunchLoadoutDialog}. A dedicated server has no {@code Control}, so the plugin asks here
 * instead and keeps the choice in {@link CoreBlueprints}/{@link LaunchCargo} (persisted on disk), then
 * generates the sector once the player confirms.</p>
 */
public class LaunchPanel{
    private static int mainId = -1, typeId = -1, blueprintId = -1, cargoId = -1;
    private static boolean available;

    /** Launches waiting for a confirmation. */
    private static final ObjectMap<Player, SectorService.PendingLaunch> pending = new ObjectMap<>();

    public static void init(){
        try{
            mainId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onMain(p, o)));
            typeId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onType(p, o)));
            blueprintId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onBlueprint(p, s)));
            cargoId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onCargo(p, s)));
            available = true;
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static boolean available(){
        return available;
    }

    /** Registers a pending launch and opens the dialog. */
    public static void show(Player player, SectorService.PendingLaunch launch){
        if(!available || player == null || player.con == null){
            //no dialog available: fall through to an immediate generation
            SectorService.enterNow(launch);
            return;
        }

        pending.put(player, launch);
        push(player);
    }

    public static void forget(@Nullable Player player){
        if(player != null) pending.remove(player);
    }

    private static void push(Player player){
        SectorService.PendingLaunch launch = pending.get(player);
        if(launch == null) return;

        String message = I18n.get("campaign.launch.message", launch.sector.name())
            + "\n" + CoreBlueprints.describe()
            + "\n" + CoreBlueprints.describeCore()
            + "\n" + I18n.get("campaign.launch.cargo", LaunchCargo.describe());

        String[][] options = {
            {I18n.get("campaign.launch.cores"), I18n.get("campaign.launch.blueprint")},
            {I18n.get("campaign.launch.cargo-edit"), I18n.get("campaign.launch.confirm")},
            {I18n.get("campaign.launch.cancel")}
        };

        Call.followUpMenu(player.con, mainId, I18n.get("campaign.launch.title"), message, options);
    }

    private static void hide(Player player, int id){
        if(player != null && player.con != null) Call.hideFollowUpMenu(player.con, id);
    }

    private static void onMain(Player player, int option){
        if(option < 0) return;

        switch(option){
            case 0 -> openTypes(player);
            case 1 -> Call.textInput(player.con, blueprintId, I18n.get("campaign.admin.core"),
                I18n.get("campaign.admin.core-hint"), 20000, "", false);
            case 2 -> Call.textInput(player.con, cargoId, I18n.get("campaign.admin.cargo"),
                I18n.get("campaign.admin.cargo-hint"), 256, "", false);
            case 3 -> {
                SectorService.PendingLaunch launch = pending.remove(player);
                hide(player, mainId);
                if(launch != null) SectorService.enterNow(launch);
            }
            default -> {
                pending.remove(player);
                hide(player, mainId);
                player.sendMessage(I18n.get("campaign.launch.cancelled"));
            }
        }
    }

    private static void openTypes(Player player){
        hide(player, mainId);

        var cores = CoreBlueprints.unlockedCores(SectorService.planet());
        Seq<String[]> rows = new Seq<>();
        for(var core : cores){
            rows.add(new String[]{core.localizedName + " (" + core.name + ")"});
        }
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, typeId, I18n.get("campaign.admin.coretype"),
            CoreBlueprints.describeCore(), rows.toArray(String[].class));
    }

    private static void onType(Player player, int option){
        if(option < 0) return;

        var cores = CoreBlueprints.unlockedCores(SectorService.planet());
        int count = cores.size;

        if(option < count){
            CoreBlueprints.setCore(cores.get(option).name);
            hide(player, typeId);
            push(player);
        }else if(option == count + 1){
            hide(player, typeId);
        }else{
            hide(player, typeId);
            push(player);
        }
    }

    private static void onBlueprint(Player player, String text){
        String problem = CoreBlueprints.check(text);
        if(problem != null){
            player.sendMessage("[scarlet]" + problem);
            return;
        }

        CoreBlueprints.save(text);
        player.sendMessage(I18n.get("campaign.core.saved-message", CoreBlueprints.describe()));
        push(player);
    }

    private static void onCargo(Player player, String text){
        String problem = LaunchCargo.set(text == null ? "" : text.trim());
        player.sendMessage(problem != null ? "[scarlet]" + problem
            : I18n.get("campaign.cargo.set", LaunchCargo.describe()));
        push(player);
    }
}
