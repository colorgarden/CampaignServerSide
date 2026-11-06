// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.vote;

import top.colorgarden.core.ResearchService;
import top.colorgarden.util.I18n;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.ItemStack;
import mindustry.ui.Menus;

/**
 * Native vote dialogs, sent with {@code Call#followUpMenu} so a vanilla client shows them without a
 * client mod.
 *
 * <p>The server registers a handful of menus once; every dialog instance is pushed with one of those
 * ids, and a click comes back as {@code Menus.menuChoose(player, menuId, option)}. Per-player state
 * (which kind and page the list shows, which target awaits confirmation) lives in maps here.</p>
 *
 * <p>The chat commands and the {@code 1}/{@code 0} ballots keep working; these dialogs are an
 * additional, optional front end.</p>
 */
public class VoteMenu{
    /** Entries per list page: the dialog gets tall fast. */
    private static final int perPage = 6;

    /** Which vote each player is currently answering. */
    private static final arc.struct.ObjectMap<Player, CampaignVote.Vote> viewing = new arc.struct.ObjectMap<>();
    /** Per player: whether numbered sectors are listed in the sector picker. */
    private static final arc.struct.ObjectMap<Player, Boolean> numberedView = new arc.struct.ObjectMap<>();

    private static int votesId = -1;
    private static int mainId = -1, listId = -1, confirmId = -1, voteId = -1, searchId = -1;
    private static int difficultyId = -1, rulesId = -1;
    private static boolean available;

    private static final ObjectMap<Player, ListState> listStates = new ObjectMap<>();
    private static final ObjectMap<Player, Pending> pending = new ObjectMap<>();

    /** Which list a player is looking at, and which page. */
    private static class ListState{
        CampaignVote.Kind kind;
        String query = "";
        int page;
        boolean showAll;
        /** Sector picker only: list numbered sectors as well. */
        boolean numbered;
        Seq<String> entries = new Seq<>();
    }

    /** A target the player already picked and now has to confirm. */
    private static class Pending{
        CampaignVote.Kind kind;
        String target;
        String label;
        /** True when the confirm was opened from a target list, so it can return there. */
        boolean fromList;
        String query = "";
        int page;
        boolean showAll;
    }

    /**
     * @return the cost / progress block for a research dialog, or "" for the other kinds.
     */
    private static String costBlock(CampaignVote.Kind kind, String target){
        if(kind != CampaignVote.Kind.research) return "";

        UnlockableContent content = ResearchService.find(target);
        if(content == null) return "";

        Seq<ItemStack> missing = ResearchService.missing(content);

        StringBuilder builder = new StringBuilder();
        builder.append("\n").append(I18n.get("campaign.menu.cost",
            ResearchService.formatStacks(seqOf(ResearchService.requirements(content)))));

        Seq<ItemStack> paid = seqOf(ResearchService.progress(content));
        paid.removeAll(stack -> stack == null || stack.amount <= 0);
        if(!paid.isEmpty()){
            builder.append("\n").append(I18n.get("campaign.menu.paid", ResearchService.formatStacks(paid)));
        }

        if(missing.isEmpty()){
            builder.append("\n").append(I18n.get("campaign.menu.ready"));
        }else{
            builder.append("\n").append(I18n.get("campaign.menu.missing", ResearchService.formatStacks(missing)));
            builder.append("\n").append(I18n.get(ResearchService.affordable(content)
                ? "campaign.menu.affordable" : "campaign.menu.not-affordable"));
        }

        return builder.toString();
    }

    private static Seq<ItemStack> seqOf(ItemStack[] stacks){
        Seq<ItemStack> out = new Seq<>();
        if(stacks != null) out.addAll(stacks);
        return out;
    }

    public static void init(){
        try{
            mainId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onMain(p, o)));
            listId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onList(p, o)));
            confirmId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onConfirm(p, o)));
            voteId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onVote(p, o)));
            searchId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onSearch(p, s)));
            difficultyId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onDifficulty(p, o)));
            rulesId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onRules(p, o)));
            available = true;
            Log.info("[CampaignServer] " + I18n.get("campaign.log.menu-ready"));
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static boolean available(){
        return available;
    }

    // ---------------------------------------------------------------- dialogs

    /** Opens the category picker. */
    public static void openMain(Player player){
        if(!available || player == null || player.con == null) return;

        CampaignVote.Vote vote = CampaignVote.active();
        String message = I18n.get("campaign.menu.main-message",
            vote == null ? I18n.get("campaign.vote.info-none") : CampaignVote.describe(vote));

        //difficulty and the campaign rules live in /ca now, so the vote menu is the same for everyone
        String[][] options = {
            {I18n.get("campaign.menu.sector"), I18n.get("campaign.menu.research")},
            {I18n.get("campaign.menu.planet"), I18n.get("campaign.menu.wip-destination")},
            {I18n.get("campaign.menu.wip-redirect"), I18n.get("campaign.menu.pause")},
            {I18n.get("campaign.menu.wave"), I18n.get("campaign.menu.abandon")},
            {I18n.get("campaign.menu.info")},
            {I18n.get("campaign.menu.close")}
        };

        Call.followUpMenu(player.con, mainId, I18n.get("campaign.menu.title"), message, options);
    }

    /** Pushes the ballot of every running vote to the players that still have to cast one. */
    public static void pushVote(){
        for(CampaignVote.Vote vote : CampaignVote.activeVotes()){
            pushVote(vote);
        }
    }

    /** Pushes one vote's ballot to everyone who has not voted on it yet. */
    public static void pushVote(CampaignVote.Vote vote){
        if(!available || vote == null) return;

        //idle players are not asked; their vote cannot be required anyway
        for(Player player : Groups.player){
            if(!top.colorgarden.core.ActivityTracker.isAfk(player)) pushVoteTo(player, vote);
        }
    }

    /** Pushes the ballot to one player, unless they already voted on it. */
    public static void pushVoteTo(Player player){
        pushVoteTo(player, CampaignVote.active());
    }

    public static void pushVoteTo(Player player, @Nullable CampaignVote.Vote vote){
        if(player == null || vote == null) return;

        //the player answers whichever vote they were shown
        viewing.put(player, vote);
        //the ballot is built in the player's own language
        I18n.withLocale(I18n.localeOf(player), () -> pushVoteToLocalized(player, vote));
    }

    private static void pushVoteToLocalized(Player player, CampaignVote.Vote vote){
        if(!available || player == null || player.con == null || vote == null) return;

        if(!CampaignVote.activeVotes().contains(vote)) return;

        //the starter already counted a yes; re-popping the ballot for them is just noise
        if(vote.yes.contains(player.uuid()) || vote.no.contains(player.uuid())) return;

        String message = I18n.get("campaign.menu.vote-message", CampaignVote.describe(vote),
            vote.yes.size, vote.required(), vote.no.size, vote.secondsLeft())
            + costBlock(vote.kind, vote.arg);

        String[][] options = {
            {I18n.get("campaign.menu.vote-yes"), I18n.get("campaign.menu.vote-no")},
            {I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")}
        };

        Call.followUpMenu(player.con, voteId, I18n.get("campaign.menu.vote-title"), message, options);
    }

    /** Closes the ballot for one player, right after they voted. */
    public static void hideVote(Player player){
        if(player != null) viewing.remove(player);
        hide(player, voteId);
    }

    /** Closes the ballot on every client once the vote is over. */
    public static void hideVote(){
        if(!available) return;

        viewing.clear();

        for(Player player : Groups.player){
            if(player != null && player.con != null){
                Call.hideFollowUpMenu(player.con, voteId);
            }
        }
    }

    // ---------------------------------------------------------------- click handlers

    /** Closes one of our dialogs; in game the client never closes a follow-up menu by itself. */
    private static void hide(Player player, int menuId){
        if(player == null || player.con == null) return;
        Call.hideFollowUpMenu(player.con, menuId);
    }

    private static void onMain(Player player, int option){
        if(option < 0) return; //closed with back/ESC

        //the close button is the last cell; admins have one extra row in front of it
        int close = 9;
        if(option == close){
            hide(player, mainId);
            return;
        }

        switch(option){
            case 0 -> showList(player, CampaignVote.Kind.sector, "", 0);
            case 1 -> showList(player, CampaignVote.Kind.research, "", 0);
            case 2 -> showList(player, CampaignVote.Kind.planet, "", 0);
            case 3 -> player.sendMessage(I18n.get("campaign.menu.wip-message"));
            case 4 -> player.sendMessage(I18n.get("campaign.menu.wip-message"));
            case 5 -> {
                mindustry.core.GameState state = mindustry.Vars.state;
                String label = I18n.get(state.isPaused() ? "campaign.pause.resume" : "campaign.pause.pause");
                askConfirm(player, CampaignVote.Kind.pause, "", label);
            }
            case 6 -> {
                if(!mindustry.Vars.state.isGame()){
                    player.sendMessage(I18n.get("campaign.msg.not-playing"));
                }else{
                    askConfirm(player, CampaignVote.Kind.wave, "", I18n.get("campaign.wave.skip-label", 1));
                }
            }
            case 7 -> {
                mindustry.type.Sector current = top.colorgarden.core.SectorService.current();
                if(current == null){
                    player.sendMessage(I18n.get("campaign.msg.not-playing"));
                }else{
                    askConfirm(player, CampaignVote.Kind.abandon, "", I18n.get("campaign.vote.abandon-label", current.name()));
                }
            }
            case 8 -> openVotes(player);
            default -> hide(player, mainId);
        }
    }

    /** Admin only: the vanilla difficulty picker. */
    private static void openDifficulty(Player player){
        if(!available || player == null || player.con == null || !player.admin) return;

        hide(player, mainId);

        Seq<String[]> rows = new Seq<>();
        String[] labels = new String[mindustry.game.Difficulty.all.length];
        for(int i = 0; i < labels.length; i++){
            labels[i] = I18n.get("campaign.difficulty." + mindustry.game.Difficulty.all[i].name());
        }
        rows.add((String[])labels);
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        String message = I18n.get("campaign.difficulty.current",
            I18n.get("campaign.difficulty." + top.colorgarden.core.SectorService.difficulty().name()));

        Call.followUpMenu(player.con, difficultyId, I18n.get("campaign.menu.difficulty"), message,
            rows.toArray(String[].class));
    }

    private static void onDifficulty(Player player, int option){
        if(option < 0) return;

        int count = mindustry.game.Difficulty.all.length;
        if(option < count){
            mindustry.game.Difficulty value = top.colorgarden.core.SectorService.setDifficulty(mindustry.game.Difficulty.all[option].name());
            if(value != null){
                player.sendMessage(I18n.get("campaign.difficulty.set", I18n.get("campaign.difficulty." + value.name())));
            }
            openDifficulty(player);
        }else if(option == count){
            hide(player, difficultyId);
            openMain(player);
        }else{
            hide(player, difficultyId);
        }
    }

    /** Admin only: the vanilla campaign rule toggles. */
    private static void openRules(Player player){
        if(!available || player == null || player.con == null || !player.admin) return;

        hide(player, mainId);

        String[] rules = {"invasion", "fog", "hidespawns", "randomwaveai", "rtsai", "pausedisabled"};
        Seq<String[]> rows = new Seq<>();
        rows.add(new String[]{toggleLabel(rules[0]), toggleLabel(rules[1])});
        rows.add(new String[]{toggleLabel(rules[2]), toggleLabel(rules[3])});
        rows.add(new String[]{toggleLabel(rules[4]), toggleLabel(rules[5])});
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, rulesId, I18n.get("campaign.rule.header"), "",
            rows.toArray(String[].class));
    }

    private static String toggleLabel(String rule){
        String name = I18n.get("campaign.rule.name." + rule);
        return top.colorgarden.core.SectorService.currentRule(rule)
            ? "[green]" + name
            : "[scarlet]" + name;
    }

    private static void onRules(Player player, int option){
        if(option < 0) return;

        String[] rules = {"invasion", "fog", "hidespawns", "randomwaveai", "rtsai", "pausedisabled"};
        if(option < rules.length){
            top.colorgarden.core.SectorService.toggleRule(rules[option], null);
            openRules(player);
        }else if(option == rules.length){
            hide(player, rulesId);
            openMain(player);
        }else{
            hide(player, rulesId);
        }
    }

    private static void showList(Player player, CampaignVote.Kind kind, String query, int page, boolean showAll){
        if(!available || player == null || player.con == null) return;

        //the category picker stays open underneath otherwise
        hide(player, mainId);

        boolean numbered = kind == CampaignVote.Kind.sector && numberedView.get(player, false);
        Seq<String> found = CampaignVote.search(kind, query, 200, showAll, numbered);
        if(found.isEmpty()){
            player.sendMessage(I18n.get("campaign.menu.no-target", I18n.get("campaign.vote.kind." + kind.name())));
            return;
        }

        int pages = Math.max(1, (found.size + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, pages - 1));

        ListState state = new ListState();
        state.kind = kind;
        state.query = query;
        state.page = current;
        state.showAll = showAll;
        state.numbered = numbered;
        state.entries = found;
        listStates.put(player, state);

        int start = current * perPage;
        int end = Math.min(found.size, start + perPage);

        Seq<String[]> rows = new Seq<>();

        //research has hundreds of entries, so it is laid out two per row; the flat button order stays
        //the same (row-major), which is why onList can still index entries directly
        if(kind == CampaignVote.Kind.research){
            for(int i = start; i < end; i += 2){
                String left = found.get(i);
                String right = i + 1 < end ? found.get(i + 1) : "";
                rows.add(right.isEmpty() ? new String[]{left} : new String[]{left, right});
            }
        }else{
            for(int i = start; i < end; i++){
                rows.add(new String[]{found.get(i)});
            }
        }
        //sector picker only: a per-player filter for the numbered sectors, like the ready/all switch
        int extra = 0;
        if(kind == CampaignVote.Kind.sector){
            rows.add(new String[]{I18n.get(numbered ? "campaign.menu.hide-numbered" : "campaign.menu.show-numbered")});
            extra = 1;
        }

        rows.add(new String[]{I18n.get("campaign.menu.prev"), I18n.get("campaign.menu.next")});
        rows.add(new String[]{I18n.get("campaign.menu.search"),
            I18n.get(showAll ? "campaign.menu.show-ready" : "campaign.menu.show-all")});
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        String message = I18n.get("campaign.menu.list-message", current + 1, pages,
            I18n.get("campaign.vote.kind." + kind.name()),
            I18n.get(showAll ? "campaign.menu.mode-all" : "campaign.menu.mode-ready"));

        Call.followUpMenu(player.con, listId, I18n.get("campaign.menu.list-title"), message,
            rows.toArray(String[].class));
    }

    private static void showList(Player player, CampaignVote.Kind kind, String query, int page){
        showList(player, kind, query, page, false);
    }

    private static void onList(Player player, int option){
        if(option < 0) return; //closed with back/ESC

        ListState state = listStates.get(player);
        if(state == null) return;

        int start = state.page * perPage;
        int shown = Math.min(state.entries.size, start + perPage) - start;

        if(option < shown){
            String label = state.entries.get(start + option);
            //entries are "显示名 (id)"; the id inside the parentheses is what the resolver accepts
            String target = inside(label);
            hide(player, listId);
            //every pick starts the vote right away: the launch setup is the confirmation for
            //sectors, and the cost details are shown on the ballot and in the announcement
            {
                String problem = CampaignVote.start(player, state.kind, target);
                if(problem != null) player.sendMessage("[scarlet]" + problem);
                //the vote is running now; the picker that started it closes
            }

            return;
        }

        int extra = state.kind == CampaignVote.Kind.sector ? 1 : 0;

        if(extra > 0 && option == shown){
            numberedView.put(player, !state.numbered);
            showList(player, state.kind, state.query, 0, state.showAll);
        }else if(option == shown + extra){
            showList(player, state.kind, state.query, state.page - 1, state.showAll);
        }else if(option == shown + extra + 1){
            showList(player, state.kind, state.query, state.page + 1, state.showAll);
        }else if(option == shown + extra + 2){
            Call.textInput(player.con, searchId, I18n.get("campaign.menu.search-title"),
                I18n.get("campaign.menu.search-message"), 32, state.query, false);
        }else if(option == shown + extra + 3){
            showList(player, state.kind, state.query, 0, !state.showAll);
        }else if(option == shown + extra + 4){
            hide(player, listId);
            openMain(player);
        }else{
            hide(player, listId);
            listStates.remove(player);
        }
    }

    private static void askConfirm(Player player, CampaignVote.Kind kind, String target, String label){
        if(!available || player == null || player.con == null) return;

        hide(player, mainId);
        hide(player, listId);

        Pending entry = new Pending();
        entry.kind = kind;
        entry.target = target;
        entry.label = label;

        //remember where the player came from, so confirming or cancelling returns there
        ListState origin = listStates.get(player);
        if(origin != null){
            entry.fromList = origin.kind == kind;
            entry.query = origin.query;
            entry.page = origin.page;
            entry.showAll = origin.showAll;
        }

        pending.put(player, entry);

        String message = I18n.get("campaign.menu.confirm-message", label,
            Math.max(1, top.colorgarden.core.ActivityTracker.activeCount())) + costBlock(kind, target);

        String[][] options = {
            {I18n.get("campaign.menu.confirm"), I18n.get("campaign.menu.cancel")}
        };

        Call.followUpMenu(player.con, confirmId, I18n.get("campaign.menu.confirm-title"), message, options);
    }

    private static void onConfirm(Player player, int option){
        if(option < 0) return; //closed with back/ESC

        Pending entry = pending.remove(player);
        hide(player, confirmId);

        if(entry == null) return;

        if(option != 0){
            //cancel: back to where the player came from instead of leaving them with no dialog
        //starting a vote closes the picker; only cancelling returns to the previous level

        if(option == 0) return; //vote started: leave the menus closed
            reopen(player, entry);
            return;
        }

        String problem = CampaignVote.start(player, entry.kind, entry.target);
        if(problem != null) player.sendMessage("[scarlet]" + problem);

        //starting a vote closes the picker; only cancelling returns to the previous level

        if(option == 0) return; //vote started: leave the menus closed
        reopen(player, entry);
    }

    /** Returns the player to the list they picked from, or to the vote main menu. */
    private static void reopen(Player player, Pending entry){
        if(entry.fromList){
            showList(player, entry.kind, entry.query, entry.page, entry.showAll);
        }else{
            openMain(player);
        }
    }
    private static void onVote(Player player, int option){
        if(option < 0) return; //closed with back/ESC

        CampaignVote.Vote vote = viewing.get(player);

        //the vote may have been settled or cancelled while the ballot was on screen
        if(vote == null || !CampaignVote.activeVotes().contains(vote)){
            viewing.remove(player);
            hide(player, voteId);
            player.sendMessage(I18n.get("campaign.vote.no-vote"));
            return;
        }

        if(option == 0 || option == 1){
            String problem = CampaignVote.cast(player, option == 0 ? "y" : "n", vote);
            if(problem != null) player.sendMessage("[scarlet]" + problem);

            viewing.remove(player);
            hide(player, voteId);
            return;
        }

        if(option == 2){
            //back to the running-vote list
            viewing.remove(player);
            hide(player, voteId);
            openVotes(player);
            return;
        }

        viewing.remove(player);
        hide(player, voteId);
    }

    // ---------------------------------------------------------------- running votes

    /** Opens the list of running votes; the list is rebuilt on every click so settled ones vanish. */
    private static void openVotes(Player player){
        if(!available || player == null || player.con == null) return;

        Seq<CampaignVote.Vote> running = CampaignVote.activeVotes();
        if(running.isEmpty()){
            hide(player, mainId);
            player.sendMessage(I18n.get("campaign.vote.info-none"));
            return;
        }

        Seq<String[]> rows = new Seq<>();
        for(CampaignVote.Vote vote : running){
            rows.add(new String[]{I18n.get("campaign.menu.vote-entry", CampaignVote.describe(vote),
                vote.yes.size, vote.required(), vote.secondsLeft())});
        }
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        hide(player, mainId);
        Call.followUpMenu(player.con, votesId, I18n.get("campaign.menu.vote-list-title"),
            I18n.get("campaign.menu.vote-list-hint"), rows.toArray(String[].class));
    }

    private static void onVotes(Player player, int option){
        if(option < 0) return;

        Seq<CampaignVote.Vote> running = CampaignVote.activeVotes();
        if(option >= running.size){
            hide(player, votesId);
            if(option == running.size) openMain(player);
            return;
        }

        CampaignVote.Vote vote = running.get(option);

        //the list may be stale: the vote could have been settled or cancelled meanwhile
        if(!CampaignVote.activeVotes().contains(vote)){
            hide(player, votesId);
            openVotes(player);
            player.sendMessage(I18n.get("campaign.vote.no-vote"));
            return;
        }

        hide(player, votesId);
        pushVoteTo(player, vote);
    }
    private static void onSearch(Player player, String text){
        ListState state = listStates.get(player);
        if(state == null) return;

        showList(player, state.kind, text == null ? "" : text.trim(), 0, state.showAll);
    }

    /** Extracts the id from a "显示名 (id)" entry produced by {@link CampaignVote#search}. */
    private static String inside(String label){
        int open = label.lastIndexOf('(');
        int close = label.lastIndexOf(')');

        if(open < 0 || close <= open) return label;
        String inner = label.substring(open + 1, close).trim();

        //sector entries carry "planet:id"; the resolver accepts that form too
        return inner.isEmpty() ? label : inner;
    }

    /** Drops per-player state, e.g. when a player leaves. */
    public static void forget(@Nullable Player player){
        if(player == null) return;
        listStates.remove(player);
        pending.remove(player);
    }
}
