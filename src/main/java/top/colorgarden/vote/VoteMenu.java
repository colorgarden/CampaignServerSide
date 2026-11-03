// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.vote;

import top.colorgarden.util.I18n;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
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

    private static int mainId = -1, listId = -1, confirmId = -1, voteId = -1, searchId = -1;
    private static boolean available;

    private static final ObjectMap<Player, ListState> listStates = new ObjectMap<>();
    private static final ObjectMap<Player, Pending> pending = new ObjectMap<>();

    /** Which list a player is looking at, and which page. */
    private static class ListState{
        CampaignVote.Kind kind;
        String query = "";
        int page;
        Seq<String> entries = new Seq<>();
    }

    /** A target the player already picked and now has to confirm. */
    private static class Pending{
        CampaignVote.Kind kind;
        String target;
        String label;
    }

    public static void init(){
        try{
            mainId = Menus.registerMenu(VoteMenu::onMain);
            listId = Menus.registerMenu(VoteMenu::onList);
            confirmId = Menus.registerMenu(VoteMenu::onConfirm);
            voteId = Menus.registerMenu(VoteMenu::onVote);
            searchId = Menus.registerTextInput(VoteMenu::onSearch);
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

        String[][] options = {
            {I18n.get("campaign.menu.sector"), I18n.get("campaign.menu.research")},
            {I18n.get("campaign.menu.planet"), I18n.get("campaign.menu.destination")},
            {I18n.get("campaign.menu.redirect"), I18n.get("campaign.menu.info")},
            {I18n.get("campaign.menu.close")}
        };

        Call.followUpMenu(player.con, mainId, I18n.get("campaign.menu.title"), message, options);
    }

    /** Pushes the running-vote ballot to everyone online. */
    public static void pushVote(){
        CampaignVote.Vote vote = CampaignVote.active();
        if(!available || vote == null) return;

        for(Player player : Groups.player){
            pushVoteTo(player);
        }
    }

    /** Pushes the ballot to one player (join, or vote start). */
    public static void pushVoteTo(Player player){
        CampaignVote.Vote vote = CampaignVote.active();
        if(!available || player == null || player.con == null || vote == null) return;

        String message = I18n.get("campaign.menu.vote-message", CampaignVote.describe(vote),
            vote.yes.size, CampaignVote.requiredVotes(), vote.no.size, vote.secondsLeft());

        String[][] options = {
            {I18n.get("campaign.menu.vote-yes"), I18n.get("campaign.menu.vote-no")},
            {I18n.get("campaign.menu.close")}
        };

        Call.followUpMenu(player.con, voteId, I18n.get("campaign.menu.vote-title"), message, options);
    }

    /** Closes the ballot on every client once the vote is over. */
    public static void hideVote(){
        if(!available) return;

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

        switch(option){
            case 0 -> showList(player, CampaignVote.Kind.sector, "", 0);
            case 1 -> showList(player, CampaignVote.Kind.research, "", 0);
            case 2 -> showList(player, CampaignVote.Kind.planet, "", 0);
            case 3 -> showList(player, CampaignVote.Kind.destination, "", 0);
            case 4 -> askConfirm(player, CampaignVote.Kind.redirect, "", I18n.get("campaign.vote.kind.redirect"));
            case 5 -> {
                CampaignVote.Vote vote = CampaignVote.active();
                if(vote == null){
                    player.sendMessage(I18n.get("campaign.vote.info-none"));
                }else{
                    pushVoteTo(player);
                }
            }
            default -> hide(player, mainId);
        }
    }

    private static void showList(Player player, CampaignVote.Kind kind, String query, int page){
        if(!available || player == null || player.con == null) return;

        //the category picker stays open underneath otherwise
        hide(player, mainId);

        Seq<String> found = CampaignVote.search(kind, query, 200);
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
        state.entries = found;
        listStates.put(player, state);

        int start = current * perPage;
        int end = Math.min(found.size, start + perPage);

        Seq<String[]> rows = new Seq<>();
        for(int i = start; i < end; i++){
            rows.add(new String[]{found.get(i)});
        }
        rows.add(new String[]{I18n.get("campaign.menu.prev"), I18n.get("campaign.menu.next")});
        rows.add(new String[]{I18n.get("campaign.menu.search"), I18n.get("campaign.menu.close")});

        String message = I18n.get("campaign.menu.list-message", current + 1, pages,
            I18n.get("campaign.vote.kind." + kind.name()));

        Call.followUpMenu(player.con, listId, I18n.get("campaign.menu.list-title"), message,
            rows.toArray(String[].class));
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
            askConfirm(player, state.kind, target, label);
            return;
        }

        if(option == shown){
            showList(player, state.kind, state.query, state.page - 1);
        }else if(option == shown + 1){
            showList(player, state.kind, state.query, state.page + 1);
        }else if(option == shown + 2){
            Call.textInput(player.con, searchId, I18n.get("campaign.menu.search-title"),
                I18n.get("campaign.menu.search-message"), 32, state.query, false);
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
        pending.put(player, entry);

        String message = I18n.get("campaign.menu.confirm-message", label,
            CampaignVote.requiredVotes(), Groups.player.size());

        String[][] options = {
            {I18n.get("campaign.menu.confirm"), I18n.get("campaign.menu.cancel")}
        };

        Call.followUpMenu(player.con, confirmId, I18n.get("campaign.menu.confirm-title"), message, options);
    }

    private static void onConfirm(Player player, int option){
        if(option < 0) return; //closed with back/ESC

        Pending entry = pending.remove(player);
        hide(player, confirmId);

        if(entry == null || option != 0) return;

        String problem = CampaignVote.start(player, entry.kind, entry.target);
        if(problem != null) player.sendMessage("[scarlet]" + problem);
    }

    private static void onVote(Player player, int option){
        if(option < 0) return; //closed with back/ESC

        if(option == 0){
            String problem = CampaignVote.cast(player, "y");
            if(problem != null) player.sendMessage("[scarlet]" + problem);
        }else if(option == 1){
            String problem = CampaignVote.cast(player, "n");
            if(problem != null) player.sendMessage("[scarlet]" + problem);
        }else{
            hide(player, voteId);
        }
    }

    private static void onSearch(Player player, String text){
        ListState state = listStates.get(player);
        if(state == null) return;

        showList(player, state.kind, text == null ? "" : text.trim(), 0);
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
