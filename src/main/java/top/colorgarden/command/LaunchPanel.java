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
    private static final int PER_PAGE = 24;

    private static int mainId = -1, listId = -1, cargoId = -1, uploadId = -1;
    private static int manageId = -1, actionId = -1, confirmId = -1, renameId = -1;
    private static boolean available;

    private static final ObjectMap<Player, SectorService.PendingLaunch> pending = new ObjectMap<>();
    /** Set when the dialog was opened to prepare a vote instead of an immediate launch. */
    private static final ObjectMap<Player, Runnable> voteAction = new ObjectMap<>();
    private static final ObjectMap<Player, Integer> listPage = new ObjectMap<>();
    private static final ObjectMap<Player, Integer> cargoPage = new ObjectMap<>();
    private static final ObjectMap<Player, Integer> managePage = new ObjectMap<>();
    /** The uploaded blueprint the manage pages act on. */
    private static final ObjectMap<Player, CoreBlueprints.Entry> manageTarget = new ObjectMap<>();

    public static void init(){
        try{
            mainId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onMain(p, o)));
            listId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onList(p, o)));
            cargoId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onCargo(p, o)));
            uploadId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onUpload(p, s)));
            manageId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onManage(p, o)));
            actionId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onAction(p, o)));
            confirmId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onConfirm(p, o)));
            renameId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onRename(p, s)));
            available = true;
            Log.info("[CampaignServer] launch panel registered.");
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static boolean available(){
        return available;
    }

    public static void show(Player player, SectorService.PendingLaunch launch){
        Log.info("[CampaignServer] launch dialog: player=@ available=@ con=@ sector=@", player == null ? "-" : player.plainName(), available, player != null && player.con != null, launch == null ? "-" : launch.sector.name());

        if(!available || player == null || player.con == null){
            Log.warn("[CampaignServer] launch dialog skipped, generating immediately.");
            SectorService.enterNow(launch);
            return;
        }

        pending.put(player, launch);
        listPage.put(player, 0);
        cargoPage.put(player, 0);
        //the dialog is built in the player's own language, not the server default
        I18n.withLocale(I18n.localeOf(player), () -> openMain(player));
    }

    /**
     * Opens the setup dialog before a vote is started, so the city is configured up front instead of
     * after the vote already passed.
     */
    public static void showForVote(Player player, SectorService.PendingLaunch launch, Runnable onConfirm){
        if(!available || player == null || player.con == null){
            onConfirm.run();
            return;
        }

        pending.put(player, launch);
        voteAction.put(player, onConfirm);
        listPage.put(player, 0);
        cargoPage.put(player, 0);
        I18n.withLocale(I18n.localeOf(player), () -> openMain(player));
    }

    /** @return true while this player has an open launch setup dialog. */
    public static boolean isWaiting(@Nullable Player player){
        return player != null && pending.containsKey(player);
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
            + "\n" + I18n.get("campaign.launch.cargo", "\n" + LaunchCargo.describeGrid());

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
                Runnable action = voteAction.remove(player);
                hide(player, mainId);

                if(action != null){
                    action.run();
                }else if(launch != null){
                    SectorService.enterNow(launch);
                }
            }
            default -> {
                pending.remove(player);
                voteAction.remove(player);
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

        rows.add(new String[]{I18n.get("campaign.launch.upload"), I18n.get("campaign.core.manage"),
            I18n.get("campaign.launch.back")});
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
            case 1 -> openManage(player, 0);
            case 2 -> {
                hide(player, listId);
                openMain(player);
            }
            case 3 -> openList(player, page - 1);
            case 4 -> openList(player, page + 1);
            default -> hide(player, listId);
        }
    }

    // ---------------------------------------------------------------- rename / delete

    /** Lists the uploaded blueprints, which are the only ones that can be renamed or deleted. */
    private static void openManage(Player player, int page){
        if(!available || player == null || player.con == null) return;

        Seq<CoreBlueprints.Entry> custom = new Seq<>();
        for(CoreBlueprints.Entry entry : CoreBlueprints.entries()){
            if(entry.editable()) custom.add(entry);
        }

        hide(player, listId);

        int pages = Math.max(1, (custom.size + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(0, Math.min(pages - 1, page));
        managePage.put(player, current);

        Seq<String[]> rows = new Seq<>();
        if(custom.isEmpty()){
            rows.add(new String[]{I18n.get("campaign.admin.empty")});
        }else{
            for(int i = current * PER_PAGE; i < Math.min(custom.size, (current + 1) * PER_PAGE); i++){
                rows.add(new String[]{custom.get(i).name});
            }
        }
        rows.add(new String[]{I18n.get("campaign.menu.prev"), I18n.get("campaign.menu.next")});
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, manageId, I18n.get("campaign.core.manage"),
            I18n.get("campaign.core.manage-hint"), rows.toArray(String[].class));
    }

    private static void onManage(Player player, int option){
        if(option < 0) return;

        Seq<CoreBlueprints.Entry> custom = new Seq<>();
        for(CoreBlueprints.Entry entry : CoreBlueprints.entries()){
            if(entry.editable()) custom.add(entry);
        }

        int page = managePage.get(player, 0);
        int from = page * PER_PAGE;
        int visible = Math.max(0, Math.min(custom.size, from + PER_PAGE) - from);

        if(option < visible){
            manageTarget.put(player, custom.get(from + option));
            openAction(player);
            return;
        }

        int rest = option - visible;
        switch(rest){
            case 0 -> openManage(player, page - 1);
            case 1 -> openManage(player, page + 1);
            case 2 -> {
                hide(player, manageId);
                openList(player, 0);
            }
            default -> hide(player, manageId);
        }
    }

    private static void openAction(Player player){
        CoreBlueprints.Entry entry = manageTarget.get(player);
        if(entry == null) return;

        hide(player, manageId);

        Seq<String[]> rows = new Seq<>();
        if(entry.editable()){
            rows.add(new String[]{I18n.get("campaign.core.rename"), I18n.get("campaign.core.delete")});
        }else{
            //generated presets are fixed; the buttons stay visible but say why they do nothing
            rows.add(new String[]{I18n.get("campaign.core.not-editable")});
        }
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, actionId, entry.describe(), "", rows.toArray(String[].class));
    }

    private static void onAction(Player player, int option){
        if(option < 0) return;

        CoreBlueprints.Entry entry = manageTarget.get(player);
        if(entry == null) return;

        if(!entry.editable()){
            if(option == 1){
                hide(player, actionId);
                openManage(player, managePage.get(player, 0));
            }else if(option > 1){
                hide(player, actionId);
            }
            return;
        }

        switch(option){
            case 0 -> Call.textInput(player.con, renameId, I18n.get("campaign.core.rename"),
                I18n.get("campaign.core.rename-hint"), 64, entry.name, false);
            case 1 -> openConfirm(player);
            case 2 -> {
                hide(player, actionId);
                openManage(player, managePage.get(player, 0));
            }
            default -> hide(player, actionId);
        }
    }

    private static void openConfirm(Player player){
        CoreBlueprints.Entry entry = manageTarget.get(player);
        if(entry == null) return;

        hide(player, actionId);

        String[][] rows = {
            {I18n.get("campaign.core.delete"), I18n.get("campaign.menu.cancel")}
        };

        Call.followUpMenu(player.con, confirmId, I18n.get("campaign.core.delete"),
            I18n.get("campaign.core.delete-confirm", entry.name), rows);
    }

    private static void onConfirm(Player player, int option){
        if(option < 0) return;

        CoreBlueprints.Entry entry = manageTarget.get(player);
        hide(player, confirmId);

        if(entry == null) return;

        if(option != 0){
            openAction(player);
            return;
        }

        boolean removed = CoreBlueprints.remove(entry);
        player.sendMessage(I18n.get(removed ? "campaign.core.removed" : "campaign.core.remove-failed",
            entry.name, ""));
        manageTarget.remove(player);
        openManage(player, managePage.get(player, 0));
    }

    private static void onRename(Player player, String text){
        CoreBlueprints.Entry entry = manageTarget.get(player);
        hide(player, renameId);

        if(entry == null) return;

        if(text == null || text.trim().isEmpty()){
            player.sendMessage("[scarlet]" + I18n.get("campaign.core.rename-failed", ""));
            openAction(player);
            return;
        }

        boolean renamed = CoreBlueprints.rename(entry, text);
        player.sendMessage(renamed
            ? I18n.get("campaign.core.renamed", text.trim())
            : I18n.get("campaign.core.rename-failed", entry.name));
        manageTarget.remove(player);
        openManage(player, managePage.get(player, 0));
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