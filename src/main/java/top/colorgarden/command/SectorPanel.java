package top.colorgarden.command;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.SectorInfo;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Sector;
import top.colorgarden.core.SectorService;
import top.colorgarden.util.I18n;
import top.colorgarden.vote.CampaignVote;

/**
 * Sector browser: pick a sector, then read its stored items, production and exports.
 *
 * <p>The numbers come straight from {@link SectorInfo}, the same fields the vanilla planet dialog
 * reads, so a sector that has been played keeps the statistics it accumulated while it was live.
 * Statistics of a sector that is not loaded are as old as its last visit - that is vanilla behaviour,
 * not a bug, and the detail page says so.</p>
 */
public class SectorPanel{
    private static final int perPage = 8;

    private static int listId = -1, detailId = -1;
    private static boolean available;

    /** Sector currently browsed per player, so the detail page knows what to render. */
    private static final ObjectMap<Player, Sector> viewing = new ObjectMap<>();
    /** Page the player is on, so the option index maps back to the right row. */
    private static final ObjectMap<Player, Integer> listPage = new ObjectMap<>();

    public static void init(){
        try{
            listId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onList(p, o)));
            detailId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onDetail(p, o)));
            available = true;
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static void openMain(Player player){
        openList(player, 0);
    }

    // ---------------------------------------------------------------- list

    /** @return every sector that has something worth showing, by id. */
    private static Seq<Sector> sectors(){
        Seq<Sector> out = new Seq<>();
        if(!Vars.state.isGame() || Vars.state.getPlanet() == null) return out;

        for(Sector sector : Vars.state.getPlanet().sectors){
            if(sector == null) continue;

            SectorInfo info = sector.info;
            boolean seen = sector.save != null || info.hasCore || info.wasCaptured
                || info.storageCapacity > 0 || info.items.total > 0 || info.wave > 1;
            if(seen) out.add(sector);
        }
        out.sort(s -> s.id);
        return out;
    }

    private static void openList(Player player, int page){
        if(!available || player == null || player.con == null) return;

        Seq<Sector> all = sectors();
        int pages = Math.max(1, (all.size + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, pages - 1));
        int from = current * perPage;
        int to = Math.min(all.size, from + perPage);

        Seq<String[]> rows = new Seq<>();
        for(int i = from; i < to; i += 2){
            String first = label(all.get(i));
            String second = i + 1 < to ? label(all.get(i + 1)) : "";
            rows.add(new String[]{first, second});
        }
        rows.add(new String[]{I18n.get("campaign.sector.prev"), I18n.get("campaign.sector.next")});
        rows.add(new String[]{I18n.get("campaign.sector.back"), I18n.get("campaign.menu.close")});

        String message = all.isEmpty()
            ? I18n.get("campaign.sector.list-empty")
            : I18n.get("campaign.sector.list-message", current + 1, pages, all.size);

        listPage.put(player, current);
        Call.followUpMenu(player.con, listId, I18n.get("campaign.sector.title"), message,
            rows.toArray(String[].class));
    }

    private static void onList(Player player, int option){
        if(player == null || player.con == null || option < 0) return;

        Seq<Sector> all = sectors();
        int page = listPage.get(player, 0);
        int start = page * perPage;
        int visible = Math.max(0, Math.min(perPage, all.size - start));
        int entrySlots = (visible + 1) / 2 * 2;

        if(option < entrySlots && start + option < all.size){
            showDetail(player, all.get(start + option));
            return;
        }

        int rest = option - entrySlots;
        if(rest == 0){
            openList(player, page - 1);
        }else if(rest == 1){
            openList(player, page + 1);
        }else if(rest == 2){
            MainMenu.openMain(player);
        }else{
            Call.hideFollowUpMenu(player.con, listId);
        }
    }

    // ---------------------------------------------------------------- detail

    private static void showDetail(Player player, Sector sector){
        if(sector == null || player == null || player.con == null) return;

        viewing.put(player, sector);
        String[][] options = {
            {I18n.get("campaign.sector.refresh")},
            {I18n.get("campaign.sector.back"), I18n.get("campaign.menu.close")}
        };

        Call.followUpMenu(player.con, detailId, I18n.get("campaign.sector.detail-title", sector.name()),
            detail(sector), options);
    }

    private static void onDetail(Player player, int option){
        if(player == null || player.con == null) return;

        Sector sector = viewing.get(player);
        if(option == 0 && sector != null){
            showDetail(player, sector);
        }else if(option == 1){
            openList(player, 0);
        }else{
            Call.hideFollowUpMenu(player.con, detailId);
        }
    }

    /** @return the formatted detail text for one sector. */
    static String detail(Sector sector){
        SectorInfo info = sector.info;
        StringBuilder out = new StringBuilder();

        out.append(I18n.get("campaign.sector.detail-header", sector.planet.localizedName, sector.id,
            SectorService.statusOf(sector))).append('\n');

        out.append(I18n.get("campaign.sector.detail-wave", info.wave,
            info.waves ? I18n.get("campaign.sector.detail-waves-on") : I18n.get("campaign.sector.detail-waves-off")))
            .append('\n');

        //storage: what the core holds right now
        out.append('\n').append(I18n.get("campaign.sector.detail-storage")).append('\n')
            .append(formatItems(itemsOf(info))).append('\n');

        //production and exports are windowed means in units per minute, exactly like the planet dialog
        out.append('\n').append(I18n.get("campaign.sector.detail-production")).append('\n')
            .append(formatStats(info.production)).append('\n');
        out.append('\n').append(I18n.get("campaign.sector.detail-export")).append('\n')
            .append(formatStats(info.export)).append('\n');

        String destination = info.destination == null
            ? I18n.get("campaign.sector.detail-none") : info.destination.name();
        String origin = info.origin == null
            ? I18n.get("campaign.sector.detail-none") : info.origin.name();
        out.append('\n').append(I18n.get("campaign.sector.detail-route", origin, destination)).append('\n');

        if(info.imports != null && info.imports.size > 0){
            out.append(I18n.get("campaign.sector.detail-import")).append('\n')
                .append(formatStats(info.imports)).append('\n');
        }

        if(sector != SectorService.current()){
            out.append('\n').append(I18n.get("campaign.sector.detail-stale"));
        }

        return out.toString();
    }

    // ---------------------------------------------------------------- formatting

    private static Seq<ItemStack> itemsOf(SectorInfo info){
        Seq<ItemStack> out = new Seq<>();
        if(info.items == null) return out;

        for(ItemStack stack : info.items){
            if(stack == null || stack.item == null || stack.amount <= 0) continue;
            out.add(stack);
        }
        out.sort(s -> -s.amount);
        return out;
    }

    private static String formatItems(Seq<ItemStack> stacks){
        if(stacks.isEmpty()) return I18n.get("campaign.sector.detail-none");

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for(ItemStack stack : stacks){
            if(shown ++ >= 6) break;
            if(out.length() > 0) out.append('\n');
            out.append("  ").append(top.colorgarden.util.Icons.item(stack.item))
                .append(stack.item.localizedName).append(" x").append(stack.amount);
        }
        return out.toString();
    }

    private static String formatStats(ObjectMap<Item, SectorInfo.ExportStat> stats){
        if(stats == null || stats.size == 0) return I18n.get("campaign.sector.detail-none");

        Seq<ObjectMap.Entry<Item, SectorInfo.ExportStat>> entries = new Seq<>();
        for(ObjectMap.Entry<Item, SectorInfo.ExportStat> entry : stats){
            if(entry.value == null || entry.value.mean <= 0.01f) continue;
            entries.add(entry);
        }
        if(entries.isEmpty()) return I18n.get("campaign.sector.detail-none");

        entries.sort(e -> -e.value.mean);

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for(ObjectMap.Entry<Item, SectorInfo.ExportStat> entry : entries){
            if(shown ++ >= 6) break;
            if(out.length() > 0) out.append('\n');
            out.append("  ").append(top.colorgarden.util.Icons.item(entry.key))
                .append(entry.key.localizedName).append(" +")
                .append(Strings.fixed(entry.value.mean, 1))
                .append(I18n.get("campaign.sector.per-minute"));
        }
        return out.toString();
    }

    private static String label(Sector sector){
        return "#" + sector.id + " " + sector.name() + " " + CampaignVote.sectorStatus(sector);
    }
}
