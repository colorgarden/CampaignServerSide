package top.colorgarden.command;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.game.SectorInfo;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;
import mindustry.ui.Menus;
import top.colorgarden.core.SectorService;
import top.colorgarden.util.I18n;
import top.colorgarden.vote.CampaignVote;

/**
 * Sector browser: pick a sector, then read its stored items, production and exports.
 *
 * <p>The list mirrors the vote sector picker exactly - one entry per row, six per page, the same
 * "show numbered" filter, the same "only available / show all" switch and the same button order - so
 * the two panels are interchangeable. The numbers come from {@link SectorInfo}, the fields the vanilla
 * planet dialog reads; a sector that is not loaded shows the statistics of its last visit.</p>
 */
public class SectorPanel{
    /** Same page size as the vote picker: six rows. */
    private static final int perPage = 6;

    private static int listId = -1, detailId = -1, searchId = -1;
    private static boolean available;

    /** Sector currently browsed per player, so the detail page knows what to render. */
    private static final ObjectMap<Player, Sector> viewing = new ObjectMap<>();
    /** Per player list state: page, filters and the entries of the current page. */
    private static final ObjectMap<Player, ListState> listStates = new ObjectMap<>();
    /** Per player: whether numbered sectors are listed, same as the vote picker. */
    private static final ObjectMap<Player, Boolean> numberedView = new ObjectMap<>();

    private static class ListState{
        int page;
        boolean showAll, numbered;
        String query = "";
        Seq<Sector> entries = new Seq<>();
    }

    public static void init(){
        try{
            listId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onList(p, o)));
            detailId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onDetail(p, o)));
            searchId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onSearch(p, s)));
            available = true;
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static void openMain(Player player){
        openList(player, "", 0, false);
    }

    // ---------------------------------------------------------------- list

    /**
     * @return the candidate sectors in the vote picker's order: named campaign presets first, then the
     *         numbered/community slots, filtered by the same two switches.
     */
    private static Seq<Sector> candidates(String query, boolean showAll, boolean numbered){
        Seq<Sector> out = new Seq<>();
        Planet planet = SectorService.planet();
        if(planet == null) return out;

        String needle = query == null ? "" : query.trim().toLowerCase();

        for(SectorPreset preset : SectorService.presets(planet)){
            Sector sector = preset.sector;
            if(sector == null || SectorService.isNumbered(sector)) continue;
            if(accepts(sector, preset.name, needle, showAll)) out.add(sector);
        }

        if(numbered) for(Sector sector : planet.sectors){
            if(sector == null) continue;
            if(sector.preset != null && !SectorService.isNumbered(sector)) continue;
            if(accepts(sector, "", needle, showAll)) out.add(sector);
        }

        return out;
    }

    private static boolean accepts(Sector sector, String presetName, String needle, boolean showAll){
        if(!showAll && top.colorgarden.config.CampaignConfig.data().enforceSectorUnlock
            && !SectorService.canEnter(sector)) return false;

        if(needle.isEmpty()) return true;

        String name = sector.preset == null ? "" : sector.preset.localizedName;
        return String.valueOf(sector.id).contains(needle)
            || (name != null && name.toLowerCase().contains(needle))
            || (presetName != null && presetName.toLowerCase().contains(needle));
    }

    private static void openList(Player player, String query, int page, boolean showAll){
        if(!available || player == null || player.con == null) return;

        //a followUpMenu does not replace the previous one on the client, it stacks, so the page we
        //came from has to be closed explicitly
        Call.hideFollowUpMenu(player.con, detailId);

        boolean numbered = numberedView.get(player, false);
        Seq<Sector> found = candidates(query, showAll, numbered);

        ListState state = new ListState();
        state.query = query == null ? "" : query;
        state.showAll = showAll;
        state.numbered = numbered;
        state.entries = found;

        int pages = Math.max(1, (found.size + perPage - 1) / perPage);
        state.page = Math.max(0, Math.min(page, pages - 1));
        listStates.put(player, state);

        int start = state.page * perPage;
        int end = Math.min(found.size, start + perPage);

        Seq<String[]> rows = new Seq<>();

        //one entry per row, exactly like the vote sector picker
        for(int i = start; i < end; i++){
            rows.add(new String[]{label(found.get(i))});
        }

        rows.add(new String[]{I18n.get(numbered ? "campaign.menu.hide-numbered" : "campaign.menu.show-numbered")});
        rows.add(new String[]{I18n.get("campaign.menu.prev"), I18n.get("campaign.menu.next")});
        rows.add(new String[]{I18n.get("campaign.menu.search"),
            I18n.get(showAll ? "campaign.menu.show-ready" : "campaign.menu.show-all")});
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        String message = I18n.get("campaign.menu.list-message", state.page + 1, pages,
            I18n.get("campaign.vote.kind.sector"),
            I18n.get(showAll ? "campaign.menu.mode-all" : "campaign.menu.mode-ready"));

        Call.followUpMenu(player.con, listId, I18n.get("campaign.menu.list-title"), message,
            rows.toArray(String[].class));
    }

    private static void onList(Player player, int option){
        if(player == null || player.con == null || option < 0) return;

        ListState state = listStates.get(player);
        if(state == null) return;

        int start = state.page * perPage;
        int shown = Math.max(0, Math.min(state.entries.size, start + perPage) - start);

        if(option < shown){
            showDetail(player, state.entries.get(start + option));
            return;
        }

        int base = shown;
        if(option == base){
            numberedView.put(player, !state.numbered);
            openList(player, state.query, 0, state.showAll);
        }else if(option == base + 1){
            openList(player, state.query, state.page - 1, state.showAll);
        }else if(option == base + 2){
            openList(player, state.query, state.page + 1, state.showAll);
        }else if(option == base + 3){
            Call.textInput(player.con, searchId, I18n.get("campaign.menu.search-title"),
                I18n.get("campaign.menu.search-message"), 32, state.query, false);
        }else if(option == base + 4){
            openList(player, state.query, 0, !state.showAll);
        }else if(option == base + 5){
            MainMenu.openMain(player);
        }else{
            Call.hideFollowUpMenu(player.con, listId);
        }
    }

    private static void onSearch(Player player, String text){
        if(player == null || player.con == null) return;

        ListState state = listStates.get(player);
        openList(player, text == null ? "" : text, 0, state != null && state.showAll);
    }

    // ---------------------------------------------------------------- detail

    private static void showDetail(Player player, Sector sector){
        if(sector == null || player == null || player.con == null) return;

        //see openList: the list must be closed, otherwise both pages render on top of each other
        Call.hideFollowUpMenu(player.con, listId);

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
            ListState state = listStates.get(player);
            openList(player, state == null ? "" : state.query, state == null ? 0 : state.page,
                state != null && state.showAll);
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
            //the glyph has to stay outside any colour tag: a tag in front of it tints the icon itself
            out.append("[white]  ").append(top.colorgarden.util.Icons.item(stack.item))
                .append("[white]").append(stack.item.localizedName).append(" x").append(stack.amount);
        }
        return out.toString();
    }

    private static String formatStats(ObjectMap<Item, SectorInfo.ExportStat> stats){
        if(stats == null || stats.size == 0) return I18n.get("campaign.sector.detail-none");

        //arc's ObjectMap iterator hands out one reused Entry, so sorting those entries afterwards would
        //leave every row pointing at the last item. Copy the pair out first.
        Seq<Stat> entries = new Seq<>();
        for(ObjectMap.Entry<Item, SectorInfo.ExportStat> entry : stats){
            if(entry.value == null || entry.value.mean < 0.05f) continue;
            entries.add(new Stat(entry.key, entry.value.mean));
        }
        if(entries.isEmpty()) return I18n.get("campaign.sector.detail-none");

        entries.sort(s -> -s.mean);

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for(Stat stat : entries){
            if(shown ++ >= 6) break;
            if(out.length() > 0) out.append('\n');
            //icon first, colour tag after it, so the glyph keeps its own tint
            out.append("[white]  ").append(top.colorgarden.util.Icons.item(stat.item))
                .append("[white]").append(stat.item.localizedName).append(" +")
                .append(Strings.fixed(stat.mean, 1))
                .append(I18n.get("campaign.sector.per-minute"));
        }
        return out.toString();
    }

    /** One item/rate pair, detached from the map entry it came from. */
    private static class Stat{
        final Item item;
        final float mean;

        Stat(Item item, float mean){
            this.item = item;
            this.mean = mean;
        }
    }

    /**
     * Mirrors the vote list verbatim: the preset name (or {@code #id} for numbered/community slots),
     * the "this sector" marker, the state, then the reason a locked sector cannot be entered yet.
     */
    private static String label(Sector sector){
        String name = sector.preset != null && !SectorService.isNumbered(sector)
            ? I18n.content(sector.preset)
            : "#" + sector.id;

        String current = sector == SectorService.current()
            ? " " + I18n.get("campaign.sector.current")
            : "";

        return name + current + " " + CampaignVote.sectorStatus(sector) + lockSuffix(sector);
    }

    /** Same wording the vote list uses for sectors that cannot be entered yet. */
    private static String lockSuffix(Sector sector){
        if(SectorService.canEnter(sector)) return "";

        String hint = SectorService.launchHint(sector);
        String reason = hint == null || hint.isEmpty()
            ? I18n.get("campaign.sector.locked-short")
            : hint;
        return " [scarlet]— " + reason + "[]";
    }
}
