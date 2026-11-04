// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.command;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.ActivityTracker;
import top.colorgarden.core.ResearchService;
import top.colorgarden.core.SectorService;
import top.colorgarden.util.I18n;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.core.GameState;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.ui.Menus;

/**
 * Admin panel: the same operations the console offers, as native dialogs.
 *
 * <p>Research, sector/planet switching, launch pad wiring, pause, difficulty, campaign rules and the
 * vote settings are all reachable from here without typing commands. Everything is admin only; the
 * menu is only pushed after an admin check.</p>
 */
public class AdminPanel{
    private static final int perPage = 6;

    private static int mainId = -1, listId = -1, settingsId = -1, difficultyId = -1, rulesId = -1, searchId = -1, coreId = -1, coreTypeId = -1, abandonId = -1;
    private static boolean available;

    private enum Purpose{ research, sector, planet, pad }

    private static class ListState{
        Purpose purpose;
        int page;
        Seq<String> entries = new Seq<>();
    }

    private static final ObjectMap<Player, ListState> states = new ObjectMap<>();

    public static void init(){
        try{
            mainId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onMain(p, o)));
            listId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onList(p, o)));
            settingsId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onSettings(p, o)));
            difficultyId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onDifficulty(p, o)));
            rulesId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onRules(p, o)));
            searchId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onSearch(p, s)));
            coreId = Menus.registerTextInput((p, s) -> I18n.withLocale(I18n.localeOf(p), () -> onCoreInput(p, s)));
            coreTypeId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onCoreType(p, o)));
            abandonId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onAbandon(p, o)));
            available = true;
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static boolean available(){
        return available;
    }

    // ---------------------------------------------------------------- main

    public static void openMain(Player player){
        if(!available || player == null || player.con == null) return;

        String message = I18n.get("campaign.admin.message",
            I18n.get("campaign.decision." + CampaignConfig.data().voteDecision), (int)CampaignConfig.data().voteDuration,
            ActivityTracker.activeCount(), mindustry.gen.Groups.player.size());

        String[][] options = {
            {I18n.get("campaign.admin.research"), I18n.get("campaign.admin.sector")},
            {I18n.get("campaign.admin.planet"), I18n.get("campaign.menu.wip-destination")},
            {I18n.get("campaign.menu.wip-redirect"), I18n.get("campaign.admin.pause")},
            {I18n.get("campaign.admin.skipwave"), I18n.get("campaign.admin.vote")},
            {I18n.get("campaign.admin.difficulty"), I18n.get("campaign.admin.rules"), I18n.get("campaign.admin.abandon")},
            {I18n.get("campaign.menu.close")}
        };

        Call.followUpMenu(player.con, mainId, I18n.get("campaign.admin.title"), message, options);
    }

    private static void onMain(Player player, int option){
        if(option < 0) return;
        if(!player.admin) return;

        switch(option){
            case 0 -> Call.textInput(player.con, searchId, I18n.get("campaign.admin.research"),
                I18n.get("campaign.admin.research-hint"), 32, "", false);
            case 1 -> showList(player, Purpose.sector, 0);
            case 2 -> showList(player, Purpose.planet, 0);
            case 3 -> player.sendMessage(I18n.get("campaign.menu.wip-message"));
            case 4 -> player.sendMessage(I18n.get("campaign.menu.wip-message"));
            case 5 -> {
                boolean pause = !mindustry.Vars.state.isPaused();
                mindustry.Vars.state.set(pause ? GameState.State.paused : GameState.State.playing);
                player.sendMessage(I18n.get(pause ? "campaign.sim.paused" : "campaign.sim.resumed"));
            }
            case 6 -> {
                String problem = CampaignCommands.skipOneWave(player);
                player.sendMessage(problem != null
                    ? "[scarlet]" + problem
                    : I18n.get("campaign.wave.skipped", 1, mindustry.Vars.state.wave));
            }
            case 7 -> openSettings(player);
            case 8 -> openDifficulty(player);
            case 9 -> openRules(player);
            case 10 -> askAbandon(player);
            default -> hide(player, mainId);
        }
    }

    /** Text input for the base64 core blueprint. */
    private static void onCoreInput(Player player, String text){
        if(player == null || !player.admin) return;

        String problem = top.colorgarden.core.CoreBlueprints.check(text);
        if(problem != null){
            player.sendMessage("[scarlet]" + problem);
            return;
        }

        top.colorgarden.core.CoreBlueprints.add(text);
        player.sendMessage(I18n.get("campaign.core.saved-message", top.colorgarden.core.CoreBlueprints.describe()));
    }

    // ---------------------------------------------------------------- lists

    private static void showList(Player player, Purpose purpose, int page){
        showEntries(player, purpose, entries(purpose), page);
    }

    private static void showEntries(Player player, Purpose purpose, Seq<String> entries, int page){
        if(!available || player == null || player.con == null) return;

        hide(player, mainId);

        if(entries.isEmpty()){
            player.sendMessage(I18n.get("campaign.admin.empty"));
            return;
        }

        int pages = Math.max(1, (entries.size + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, pages - 1));

        ListState state = new ListState();
        state.purpose = purpose;
        state.page = current;
        state.entries = entries;
        states.put(player, state);

        int start = current * perPage;
        int end = Math.min(entries.size, start + perPage);

        Seq<String[]> rows = new Seq<>();
        for(int i = start; i < end; i++){
            rows.add(new String[]{entries.get(i)});
        }
        rows.add(new String[]{I18n.get("campaign.menu.prev"), I18n.get("campaign.menu.next")});
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, listId, I18n.get("campaign.admin.title"),
            I18n.get("campaign.menu.list-message", current + 1, pages, I18n.get("campaign.admin." + purpose.name())),
            rows.toArray(String[].class));
    }

    private static Seq<String> entries(Purpose purpose){
        Seq<String> out = new Seq<>();

        switch(purpose){
            case research -> {
                for(String entry : top.colorgarden.vote.CampaignVote.search(
                    top.colorgarden.vote.CampaignVote.Kind.research, "", 60, false)){
                    out.add(entry);
                }
            }
            case sector -> {
                Planet planet = SectorService.planet();
                if(planet == null) return out;

                Sector playing = SectorService.current();
                for(mindustry.type.SectorPreset preset : SectorService.presets(planet)){
                    Sector sector = preset.sector;
                    if(sector == null || sector == playing) continue;
                    out.add(I18n.content(preset) + " (" + planet.name + ":" + sector.id + ")");
                }
            }
            case planet -> {
                for(Planet planet : SectorService.planets()){
                    if(planet == SectorService.planet()) continue;
                    if(SectorService.targetFor(planet) == null) continue;
                    out.add(planet.localizedName + " (" + planet.name + ")");
                }
            }
            case pad -> {
                Sector current = SectorService.current();
                if(current == null) return out;

                for(Sector sector : current.planet.sectors){
                    if(sector == null || sector == current || !sector.hasBase()) continue;
                    out.add(sector.name() + " (" + current.planet.name + ":" + sector.id + ")");
                }
            }
        }

        return out;
    }

    private static void onList(Player player, int option){
        if(option < 0) return;

        ListState state = states.get(player);
        if(state == null || !player.admin) return;

        int start = state.page * perPage;
        int shown = Math.min(state.entries.size, start + perPage) - start;

        if(option < shown){
            act(player, state.purpose, inside(state.entries.get(start + option)));
        }else if(option == shown){
            showList(player, state.purpose, state.page - 1);
        }else if(option == shown + 1){
            showList(player, state.purpose, state.page + 1);
        }else if(option == shown + 2){
            hide(player, listId);
            openMain(player);
        }else{
            hide(player, listId);
        }
    }

    private static void act(Player player, Purpose purpose, String id){
        try{
            switch(purpose){
                case research -> {
                    UnlockableContent content = ResearchService.find(id);
                    if(content == null){
                        player.sendMessage(I18n.get("campaign.research.unknown", id));
                        return;
                    }
                    boolean changed = ResearchService.research(content, false);
                    player.sendMessage(I18n.get(changed
                        ? "campaign.client.research.ok" : "campaign.client.research.already", I18n.content(content)));
                }
                case sector -> {
                    Sector sector = SectorService.resolve(id);
                    if(sector == null){
                        player.sendMessage(I18n.get("campaign.error.no-such-sector", id));
                        return;
                    }
                    SectorService.enter(sector, false, true);
                }
                case planet -> {
                    Planet planet = SectorService.planetByName(id);
                    Sector target = planet == null ? null : SectorService.targetFor(planet);
                    if(target == null){
                        player.sendMessage(I18n.get("campaign.planet.empty", id));
                        return;
                    }
                    SectorService.enter(target, false, true);
                }
                case pad -> {
                    Sector current = SectorService.current();
                    Sector destination = SectorService.resolve(id);
                    if(current == null || destination == null){
                        player.sendMessage(I18n.get("campaign.error.no-such-sector", id));
                        return;
                    }
                    SectorService.setDestination(current, destination);
                    player.sendMessage(I18n.get("campaign.destination.set", current.name(), destination.name()));
                }
            }
        }catch(Throwable t){
            Log.err("[CampaignServer] Admin action failed.", t);
            player.sendMessage(I18n.get("campaign.error.unexpected", arc.util.Strings.getSimpleMessage(t)));
        }
    }

    private static void onSearch(Player player, String text){
        if(player == null || !player.admin) return;

        Seq<String> entries = top.colorgarden.vote.CampaignVote.search(
            top.colorgarden.vote.CampaignVote.Kind.research, text == null ? "" : text.trim(), 40, false);

        showEntries(player, Purpose.research, entries, 0);
    }

    // ---------------------------------------------------------------- vote settings

    private static void openSettings(Player player){
        if(!available || player == null || player.con == null || !player.admin) return;
        hide(player, mainId);

        CampaignConfig config = CampaignConfig.data();

        String[][] options = {
            {I18n.get("campaign.admin.window", (int)config.voteDuration), "-5", "+5"},
            {I18n.get("campaign.admin.push", (int)config.votePushSeconds), "-1", "+1"},
            {I18n.get("campaign.admin.timeout", (int)config.voteTimeout), "-1", "+1"},
            {I18n.get("campaign.admin.cooldown", (int)config.voteCooldown), "-1", "+1"},
            {I18n.get("campaign.admin.percent", (int)(config.votePercent * 100)), "-10", "+10"},
            {I18n.get("campaign.admin.minplayers", config.voteMinPlayers), "-1", "+1"},
            {I18n.get("campaign.admin.skipcd", (int)config.skipWaveCooldown), "-5", "+5"},
            {I18n.get("campaign.admin.afk", (int)config.afkSeconds), "-10", "+10"},
            {I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")}
        };

        Call.followUpMenu(player.con, settingsId, I18n.get("campaign.admin.vote"),
            I18n.get("campaign.admin.vote-hint"), options);
    }

    private static void onSettings(Player player, int option){
        if(option < 0 || !player.admin) return;

        CampaignConfig config = CampaignConfig.data();
        boolean changed = true;

        switch(option){
            //the label cells are dead keys; re-pushing keeps the dialog open
            case 0, 3, 6, 9, 12, 15, 18, 21 -> changed = false;
            case 1 -> config.voteDuration = clamp(config.voteDuration - 5f, 5f, 300f);
            case 2 -> config.voteDuration = clamp(config.voteDuration + 5f, 5f, 300f);
            case 4 -> config.votePushSeconds = clamp(config.votePushSeconds - 1f, 0f, Math.max(0f, config.voteDuration - 1f));
            case 5 -> config.votePushSeconds = clamp(config.votePushSeconds + 1f, 0f, Math.max(0f, config.voteDuration - 1f));
            case 7 -> config.voteTimeout = clamp(config.voteTimeout - 1f, 0f, maxTimeout());
            case 8 -> config.voteTimeout = clamp(config.voteTimeout + 1f, 0f, maxTimeout());
            case 10 -> config.voteCooldown = clamp(config.voteCooldown - 1f, 0f, 300f);
            case 11 -> config.voteCooldown = clamp(config.voteCooldown + 1f, 0f, 300f);
            case 13 -> config.votePercent = clamp(config.votePercent - 0.1f, 0.1f, 1f);
            case 14 -> config.votePercent = clamp(config.votePercent + 0.1f, 0.1f, 1f);
            case 16 -> config.voteMinPlayers = Math.max(1, config.voteMinPlayers - 1);
            case 17 -> config.voteMinPlayers = Math.min(100, config.voteMinPlayers + 1);
            case 19 -> config.skipWaveCooldown = clamp(config.skipWaveCooldown - 5f, 0f, 600f);
            case 20 -> config.skipWaveCooldown = clamp(config.skipWaveCooldown + 5f, 0f, 600f);
            case 22 -> config.afkSeconds = clamp(config.afkSeconds - 10f, 10f, 600f);
            case 23 -> config.afkSeconds = clamp(config.afkSeconds + 10f, 10f, 600f);
            case 24 -> changed = false;
            case 25 -> changed = false;
            default -> changed = false;
        }

        if(changed) CampaignConfig.save();

        if(option == 24){
            hide(player, settingsId);
            openMain(player);
        }else if(option == 25){
            hide(player, settingsId);
        }else{
            openSettings(player);
        }
    }

    /**
     * @return the longest idle timeout that can still take effect: the ballot is pushed at
     *         {@code voteDuration - votePushSeconds}, so a longer timeout would always be cut off by
     *         the end of the window and the setting would look dead.
     */
    private static float maxTimeout(){
        CampaignConfig config = CampaignConfig.data();
        return Math.max(0f, config.voteDuration - Math.max(0f, config.votePushSeconds));
    }
    private static float clamp(float value, float min, float max){
        return Math.max(min, Math.min(max, value));
    }

    // ---------------------------------------------------------------- difficulty / rules

    private static void openDifficulty(Player player){
        if(!available || player == null || player.con == null || !player.admin) return;
        hide(player, mainId);

        String[] labels = new String[mindustry.game.Difficulty.all.length];
        for(int i = 0; i < labels.length; i++){
            labels[i] = I18n.get("campaign.difficulty." + mindustry.game.Difficulty.all[i].name());
        }

        Seq<String[]> rows = new Seq<>();
        rows.add(labels);
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, difficultyId, I18n.get("campaign.menu.difficulty"),
            I18n.get("campaign.difficulty.current", I18n.get("campaign.difficulty." + SectorService.difficulty().name())),
            rows.toArray(String[].class));
    }

    private static void onDifficulty(Player player, int option){
        if(option < 0 || !player.admin) return;

        int count = mindustry.game.Difficulty.all.length;
        if(option < count){
            mindustry.game.Difficulty value = SectorService.setDifficulty(mindustry.game.Difficulty.all[option].name());
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

    private static final String[] ruleNames = {"invasion", "fog", "hidespawns", "randomwaveai", "rtsai", "pausedisabled"};

    private static void openRules(Player player){
        if(!available || player == null || player.con == null || !player.admin) return;
        hide(player, mainId);

        Seq<String[]> rows = new Seq<>();
        for(int i = 0; i < ruleNames.length; i += 2){
            String left = toggle(ruleNames[i]);
            String right = i + 1 < ruleNames.length ? toggle(ruleNames[i + 1]) : "";
            rows.add(new String[]{left, right});
        }
        rows.add(new String[]{I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, rulesId, I18n.get("campaign.rule.header"), "",
            rows.toArray(String[].class));
    }

    private static String toggle(String rule){
        String name = I18n.get("campaign.rule.name." + rule);
        return (SectorService.currentRule(rule) ? "[green]" : "[scarlet]") + name;
    }

    private static void onRules(Player player, int option){
        if(option < 0 || !player.admin) return;

        if(option < ruleNames.length){
            SectorService.toggleRule(ruleNames[option], null);
            CampaignConfig.save();
            openRules(player);
        }else if(option == ruleNames.length){
            hide(player, rulesId);
            openMain(player);
        }else{
            hide(player, rulesId);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void hide(Player player, int menuId){
        if(player == null || player.con == null) return;
        Call.hideFollowUpMenu(player.con, menuId);
    }

    private static String inside(String label){
        int open = label.lastIndexOf('(');
        int close = label.lastIndexOf(')');
        if(open < 0 || close <= open) return label;

        String inner = label.substring(open + 1, close).trim();
        return inner.isEmpty() ? label : inner;
    }

    /** Drops per-player state. */
    public static void forget(@Nullable Player player){
        if(player != null) states.remove(player);
    }

    // ---------------------------------------------------------------- core type

    private static void openCoreTypes(Player player){
        if(!available || player == null || player.con == null || !player.admin) return;
        hide(player, mainId);

        var cores = top.colorgarden.core.CoreBlueprints.unlockedCores(SectorService.planet());

        Seq<String[]> rows = new Seq<>();
        for(var core : cores){
            rows.add(new String[]{core.localizedName + " (" + core.name + ")"});
        }
        rows.add(new String[]{I18n.get("campaign.core.type-reset"), I18n.get("campaign.menu.back"), I18n.get("campaign.menu.close")});

        Call.followUpMenu(player.con, coreTypeId, I18n.get("campaign.admin.coretype"),
            top.colorgarden.core.CoreBlueprints.describe(), rows.toArray(String[].class));
    }

    private static void onCoreType(Player player, int option){
        if(option < 0 || !player.admin) return;

        var cores = top.colorgarden.core.CoreBlueprints.unlockedCores(SectorService.planet());
        int count = cores.size;

        if(option < count){
            top.colorgarden.core.CoreBlueprints.select(cores.get(option).name);
            player.sendMessage(I18n.get("campaign.core.type-set", top.colorgarden.core.CoreBlueprints.describe()));
            openCoreTypes(player);
        }else if(option == count){
            top.colorgarden.core.CoreBlueprints.select(null);
            player.sendMessage(I18n.get("campaign.core.type-set", top.colorgarden.core.CoreBlueprints.describe()));
            openCoreTypes(player);
        }else if(option == count + 1){
            hide(player, coreTypeId);
            openMain(player);
        }else{
            hide(player, coreTypeId);
        }
    }

    // ---------------------------------------------------------------- abandon

    private static void askAbandon(Player player){
        if(!available || player == null || player.con == null || !player.admin) return;
        hide(player, mainId);

        Sector current = SectorService.current();
        if(current == null){
            player.sendMessage(I18n.get("campaign.msg.not-playing"));
            return;
        }

        String message = I18n.get("campaign.admin.abandon-confirm", current.name())
            + "\n" + I18n.get("campaign.admin.abandon-hint");

        String[][] options = {
            {I18n.get("campaign.admin.abandon-yes"), I18n.get("campaign.menu.cancel")}
        };

        Call.followUpMenu(player.con, abandonId, I18n.get("campaign.admin.abandon"), message, options);
    }

    /**
     * Abandons the current sector the way vanilla does: killing every core ends the game, and the
     * game-over handler decides whether the sector is cleared. Nothing else is touched.
     */
    private static void onAbandon(Player player, int option){
        if(option < 0 || !player.admin) return;
        hide(player, abandonId);
        if(option != 0) return;

        Sector current = SectorService.current();
        if(current == null || !mindustry.Vars.state.isGame()){
            player.sendMessage(I18n.get("campaign.msg.not-playing"));
            return;
        }

        player.sendMessage(I18n.get("campaign.admin.abandoning", current.name()));

        try{
            for(mindustry.gen.Building core : mindustry.Vars.state.rules.defaultTeam.cores().copy()){
                core.kill();
            }
        }catch(Throwable t){
            player.sendMessage("[scarlet]" + I18n.get("campaign.admin.abandon-failed", String.valueOf(t.getMessage())));
        }
    }
}
