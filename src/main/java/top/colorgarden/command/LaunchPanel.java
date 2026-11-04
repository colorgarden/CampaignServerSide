// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.command;

import top.colorgarden.core.CoreBlueprints;
import top.colorgarden.core.LaunchCargo;
import top.colorgarden.core.SectorService;
import top.colorgarden.util.I18n;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.type.Sector;
import mindustry.ui.Menus;

/**
 * The launch setup dialog: shown before a sector is created for the first time.
 *
 * <p>Vanilla asks the launching player for a core loadout and the item cargo in
 * {@code LaunchLoadoutDialog}. A dedicated server has no {@code Control}, so the plugin asks here: the
 * blueprint list works like the vanilla loadout picker (a single-core blueprint is the "core type"
 * choice), and the cargo page replaces the item grid with -/+ buttons per item.</p>
 */
public class LaunchPanel{
    private static final int PER_PAGE = 6;

    private static int mainId = -1, listId = -1, cargoId = -1, uploadId = -1;
    private static boolean available;

    private static final ObjectMap<Player, SectorService.PendingLaunch> pending = new ObjectMap<>();
    private static final ObjectMap<Player, Integer> listPage = new ObjectMap<>();
    private static final ObjectMap<Player, Integer> cargoPage = new ObjectMap<>();

    public static void init(){
        try{
            mainId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onMain(p, o)));
            listId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onList(p, o)));
            cargoId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onCargo(p, o)));
            uploadId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onUpload(p, s)));
            available = true;
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static boolean available(){
        return available;
    }

    public static void show(Player player, SectorService.PendingLaunch launch){
        if(!available || player == null || player.con == null){
            SectorService.enterNow(launch);
            return;
        }

        pending.put(player, launch);
        listPage.put(player, 0);
        cargoPage.put(player, 0);
        openMain(player);
    }

    public static void forget(@Nullable Player player){
        if(player == null) return;
        pending.remove(player);
        listPage.remove(player);
        cargoPage.remove(player);
    }

    private static void hide(Player player, int id){
        if(player != null && player.con != null) Call.hideFollowUpMenu(player.con, id);
    }

    // ---------------------------------------------------------------- main page

    private static void openMain(Player player){
        SectorService.PendingLaunch launch = pending.get(player);
        if(launch == null) return;

        String message = I18n.get("campaign.launch.message", launch.sector.name())
            + "\n" + CoreBlueprints.describe()
            + "\n" + I18n.get("campaign.launch.cargo", LaunchCargo.describe());

        String[][] options = {
            {I18n.get("campaign.launch.blueprints"), I18n.get("campaign.launch.cargo-edit")},
            {I18n.get("campaign.launch.confirm"), I18n.get("campaign.launch.cancel")}
        };

        Call.followUpMenu(player.con, mainId, I18n.get("campaign.launch.title"), message, options);
    }

    private static void onMain(Player player, int option){
        if(option < 0) return;

        switch(option){
            case 0 -> openList(player, 0);
            case 1 -> openCargo(player, 0);
            case 2 -> {
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

    // ---------------------------------------------------------------- blueprint list

    private static void openList(Player player, int page){
        Seq<CoreBlueprints.Entry> entries = CoreBlueprints.entries();
        int pages = Math.max(1, (entries.size + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(pages - 1, page));
        listPage.put(player, current);

        hide(player, mainId);

        Seq<String[]> rows = new Seq<>();
        for(int i = current * PER_PAGE; i < Math.min(entries.size, (current + 1) * PER_PAGE); i++){
            CoreBlueprints.Entry entry = entries.get(i);
            String mark = entry == CoreBlueprints.selectedEntry() ? " [green]>[]" : "";
            rows.add(new String[]{entry.name + mark});
        }

        rows.add(new String[]{I18n.get("campaign.launch.upload"), I18n.get("campaign.launch.back")});
        rows.add(new String[]{I18n.get("campaign.menu.prev"), I18n.get("campaign.menu.next"),
            I18n.get("campaign.menu.close")});

        String message = I18n.get("campaign.launch.blueprint-hint", current + 1, pages);

        Call.followUpMenu(player.con, listId, I18n.get("campaign.launch.blueprints"), message, rows.toArray(String[].class));
    }

    private static void onList(Player player, int option){
        if(option < 0) return;

        Seq<CoreBlueprints.Entry> entries = CoreBlueprints.entries();
        int page = listPage.get(player, 0);
        int from = page * PER_PAGE;
        int visible = Math.min(entries.size, from + PER_PAGE) - from;

        if(option < visible){
            CoreBlueprints.Entry entry = entries.get(from + option);
            CoreBlueprints.select(entry.name);
            hide(player, listId);
            openMain(player);
            return;
        }

        int rest = option - visible;
        switch(rest){
            case 0 -> Call.textInput(player.con, uploadId, I18n.get("campaign.launch.upload"),
                I18n.get("campaign.admin.core-hint"), 20000, "", false);
            case 1 -> {
                hide(player, listId);
                openMain(player);
            }
            case 2 -> openList(player, page - 1);
            case 3 -> openList(player, page + 1);
            default -> hide(player, listId);
        }
    }

    private static void onUpload(Player player, String text){
        String problem = CoreBlueprints.check(text);
        if(problem != null){
            player.sendMessage("[scarlet]" + problem);
            return;
        }

        String name = CoreBlueprints.add(text);
        if(name != null) CoreBlueprints.select(name);
        player.sendMessage(I18n.get("campaign.core.saved-message", CoreBlueprints.describe()));
        openList(player, Math.max(0, CoreBlueprints.entries().size / PER_PAGE));
    }

    // ---------------------------------------------------------------- cargo

    private static Seq<Item> cargoItems(){
        Seq<Item> out = new Seq<>();
        for(Item item : Vars.content.items()){
            if(item.unlocked()) out.add(item);
        }
        return out;
    }

    private static void openCargo(Player player, int page){
        Seq<Item> items = cargoItems();
        int pages = Math.max(1, (items.size + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(pages - 1, page));
        cargoPage.put(player, current);

        hide(player, mainId);

        Seq<String[]> rows = new Seq<>();
        for(int i = current * PER_PAGE; i < Math.min(items.size, (current + 1) * PER_PAGE); i++){
            Item item = items.get(i);
            int amount = LaunchCargo.amount(item);
            rows.add(new String[]{item.localizedName + ": " + amount, "-50", "+50", I18n.get("campaign.launch.max")});
        }

        rows.add(new String[]{I18n.get("campaign.launch.max-all"), I18n.get("campaign.launch.clear-cargo"), I18n.get("campaign.launch.back")});
        rows.add(new String[]{I18n.get("campaign.menu.prev"), I18n.get("campaign.menu.next"),
            I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, cargoId, I18n.get("campaign.launch.cargo-title"),
            I18n.get("campaign.launch.cargo-hint", current + 1, pages, LaunchCargo.capacity()), rows.toArray(String[].class));
    }

    private static void onCargo(Player player, int option){
        if(option < 0) return;

        Seq<Item> items = cargoItems();
        int page = cargoPage.get(player, 0);
        int from = page * PER_PAGE;
        int visible = Math.min(items.size, from + PER_PAGE) - from;

        if(option < visible * 4){
            Item item = items.get(from + option / 4);
            int cell = option % 4;

            if(cell == 1) LaunchCargo.addAmount(item, -50);
            if(cell == 2) LaunchCargo.addAmount(item, 50);
            if(cell == 3) LaunchCargo.addAmount(item, LaunchCargo.capacity() - LaunchCargo.amount(item));

            openCargo(player, page);
            return;
        }

        int rest = option - visible * 4;
        switch(rest){
            case 0 -> {
                LaunchCargo.maxAll();
                openCargo(player, page);
            }
            case 1 -> {
                LaunchCargo.set("");
                openCargo(player, page);
            }
            case 2 -> {
                hide(player, cargoId);
                openMain(player);
            }
            case 3 -> openCargo(player, page - 1);
            case 4 -> openCargo(player, page + 1);
            default -> hide(player, cargoId);
        }
    }
}