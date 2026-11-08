package top.colorgarden.command;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.Time;
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

    /**
     * Re-sends the open detail page so its numbers follow the simulation.
     *
     * <p>The client replaces a dialog when the same menu id arrives again, so a periodic re-send is a
     * clean in-place refresh; without it the page would freeze on the values it was opened with.</p>
     */
    public static void tick(){
        long now = Time.millis();
        if(now - lastRefresh < refreshMs) return;
        lastRefresh = now;

        if(viewing.isEmpty()) return;

        Seq<Player> players = new Seq<>();
        Seq<Sector> sectors = new Seq<>();
        for(ObjectMap.Entry<Player, Sector> entry : viewing){
            if(entry.key == null || entry.value == null) continue;
            players.add(entry.key);
            sectors.add(entry.value);
        }

        for(int i = 0; i < players.size; i++){
            Player player = players.get(i);
            if(player.con == null) continue;
            showDetail(player, sectors.get(i));
        }
    }

    /** Refresh interval of the detail page, in milliseconds. */
    private static final long refreshMs = 1500L;
    private static long lastRefresh;

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

        //closed with back/ESC: stop refreshing this page
        if(option < 0){
            viewing.remove(player);
            return;
        }

        Sector sector = viewing.get(player);
        if(option == 0 && sector != null){
            showDetail(player, sector);
        }else if(option == 1){
            viewing.remove(player);
            ListState state = listStates.get(player);
            openList(player, state == null ? "" : state.query, state == null ? 0 : state.page,
                state != null && state.showAll);
        }else{
            viewing.remove(player);
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

        //a launch pad with no destination throws its payload away without recording anything, so the
        //empty export list above would look like a broken panel instead of a missing setting
        if(info.destination == null){
            out.append(I18n.get("campaign.sector.detail-no-destination")).append('\n');
        }

        String destination = info.destination == null
            ? I18n.get("campaign.sector.detail-none") : info.destination.name();

        out.append('\n').append(I18n.get("campaign.sector.detail-route", importSources(sector), destination))
            .append('\n');

        //imports: info.imports only holds what this sector recorded while it was loaded, while
        //importStats() derives what the other sectors' exports add up to - the latter is the complete
        //picture and matches the planet dialog
        ObjectMap<Item, SectorInfo.ExportStat> imports = sector.planet == null
            ? info.imports : info.importStats(sector.planet);
        if(imports != null && imports.size > 0){
            out.append(I18n.get("campaign.sector.detail-import")).append('\n')
                .append(formatStats(imports)).append('\n');
        }

        if(sector != SectorService.current()){
            out.append('\n').append(I18n.get("campaign.sector.detail-stale"));
        }

        return out.toString();
    }

    /**
     * @return who ships into this sector.
     *
     * <p>{@code SectorInfo#origin} only remembers the sector a launch came from and stays null for
     * everything entered by a vote or by the console - that is why it always read as "none". The
     * vanilla planet dialog instead asks which sectors have this one as their destination, which is
     * what {@code eachImport} walks; the launch origin is only a fallback.</p>
     */
    private static String importSources(Sector sector){
        Seq<String> names = new Seq<>();
        mindustry.type.Planet planet = sector.planet;

        if(planet != null){
            sector.info.eachImport(planet, source -> {
                if(source != null && source != sector) names.add(source.name());
            });
        }

        if(names.isEmpty() && sector.info.origin != null){
            names.add(sector.info.origin.name());
        }

        if(names.isEmpty()) return I18n.get("campaign.sector.detail-none");

        return names.toString(", ");
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

        Seq<String> cells = new Seq<>();
        for(ItemStack stack : stacks){
            //the glyph has to stay outside any colour tag: a tag in front of it tints the icon itself
            cells.add(top.colorgarden.util.Icons.item(stack.item)
                + "[white]" + formatAmount(stack.amount));
        }
        return layout(cells);
    }

    /**
     * Lays cells out in columns, padding each to a fixed display width.
     *
     * <p>The dialog label is fixed at 400px and wraps, so the column count and width have to respect
     * that: CJK glyphs take roughly twice the width of a latin one, which is why padding by
     * {@code String#length} produced the ragged, overlapping lines. Two columns of 24 width units fit
     * the 400px label; more than that wraps.</p>
     */
    private static String layout(Seq<String> cells){
        final int perRow = 4;
        final int columnWidth = 12;

        StringBuilder out = new StringBuilder();
        for(int i = 0; i < cells.size; i++){
            String cell = cells.get(i);
            if(i % perRow == 0){
                if(i > 0) out.append('\n');
            }else{
                out.append("  ");
            }

            if(i % perRow == perRow - 1 || i == cells.size - 1){
                out.append(cell);
            }else{
                int width = displayWidth(cell);
                out.append(cell);
                for(int pad = width; pad < columnWidth; pad++) out.append(' ');
            }
        }
        return out.toString();
    }

    /** @return the rendered width of a markup string, counting CJK (and full-width) glyphs as two. */
    private static int displayWidth(String markup){
        String plain = arc.util.Strings.stripColors(markup);
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

    private static String formatStats(ObjectMap<Item, SectorInfo.ExportStat> stats){
        if(stats == null || stats.size == 0) return I18n.get("campaign.sector.detail-none");

        //arc's ObjectMap iterator hands out one reused Entry, so sorting those entries afterwards would
        //leave every row pointing at the last item. Copy the pair out first.
        Seq<Stat> entries = new Seq<>();
        for(ObjectMap.Entry<Item, SectorInfo.ExportStat> entry : stats){
            if(entry.value == null) continue;
            //the mean is per second; vanilla PlanetDialog shows it per minute as (int)(mean * 60)
            long perMinute = (long)(entry.value.mean * 60f);
            if(perMinute <= 1) continue;
            entries.add(new Stat(entry.key, perMinute));
        }
        if(entries.isEmpty()) return I18n.get("campaign.sector.detail-none");

        entries.sort(s -> -s.perMinute);

        Seq<String> cells = new Seq<>();
        for(Stat stat : entries){
            //icon first, colour tag after it, so the glyph keeps its own tint
            cells.add(top.colorgarden.util.Icons.item(stat.item)
                + "[white]" + formatAmount(stat.perMinute) + I18n.get("campaign.sector.per-minute"));
        }
        return layout(cells);
    }

    /** One item/rate pair, detached from the map entry it came from. */
    private static class Stat{
        final Item item;
        final long perMinute;

        Stat(Item item, long perMinute){
            this.item = item;
            this.perMinute = perMinute;
        }
    }

    /** Same thresholds as vanilla {@code UI#formatAmount}: 196000 reads as "196k". */
    private static String formatAmount(long number){
        long mag = Math.abs(number);

        if(mag >= 1_000_000_000L) return Strings.fixed(mag / 1_000_000_000f, 1) + "G";
        if(mag >= 1_000_000L) return Strings.fixed(mag / 1_000_000f, 1) + "M";
        if(mag >= 10_000L) return (number / 1000) + "k";
        if(mag >= 1000L) return Strings.fixed(mag / 1000f, 1) + "k";
        return Long.toString(number);
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
