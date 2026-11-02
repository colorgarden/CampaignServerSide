// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.vote;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.ResearchService;
import top.colorgarden.core.SectorService;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.util.I18n;


import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.Sector;

/**
 * Player votes for switching the sector or unlocking research.
 *
 * <p>Votes only exist to let <i>non-admins</i> move the campaign forward: a vote kind is effective
 * only when the matching action is enabled and <b>not</b> restricted to admins
 * ({@link #votingEnabled(Kind)}). A vote passes once {@code ceil(online * votePercent)} players voted
 * yes (at least two) and fails on timeout, on enough no-votes, or when an admin cancels it.</p>
 */
public class CampaignVote{
    public enum Kind{
        sector, research
    }

    /** The vote that is currently running, if any. */
    private static @Nullable Vote current;
    /** Cooldown end time per player uuid. */
    private static final ObjectMap<String, Long> cooldownUntil = new ObjectMap<>();

    public static void init(){
        Events.on(ResetEvent.class, event -> current = null);
        Events.run(Trigger.update, CampaignVote::update);
    }

    public static @Nullable Vote active(){
        return current;
    }

    /**
     * @return whether votes for this kind are effective: voting enabled, the action enabled and
     *         the action <i>not</i> restricted to admins.
     */
    public static boolean votingEnabled(Kind kind){
        CampaignConfig config = CampaignConfig.data();
        if(!config.voting) return false;

        if(kind == Kind.sector){
            return config.voteSector && config.allowSectorSwitch && !config.sectorSwitchAdminOnly;
        }
        return config.voteResearch && config.allowResearch && !config.researchAdminOnly;
    }

    /** @return how many yes votes are needed with the given number of online players. */
    public static int requiredVotes(int online){
        int byPercent = (int)Math.ceil(online * CampaignConfig.data().votePercent);
        return Math.max(2, Math.min(Math.max(online, 1), byPercent));
    }

    /** @return how many yes votes are needed right now. */
    public static int requiredVotes(){
        return requiredVotes(Groups.player.size());
    }

    /**
     * Starts a vote.
     *
     * @return null on success, otherwise a player-readable reason.
     */
    public static @Nullable String start(Player starter, Kind kind, String target){
        CampaignConfig config = CampaignConfig.data();

        if(!config.voting) return I18n.get("campaign.vote.disabled");
        if(!votingEnabled(kind)){
            if(kind == Kind.sector){
                return I18n.get(config.sectorSwitchAdminOnly ? "campaign.vote.sector-admin-only" : "campaign.vote.sector-disabled");
            }
            return I18n.get(config.researchAdminOnly ? "campaign.vote.research-admin-only" : "campaign.vote.research-disabled");
        }
        if(current != null) return I18n.get("campaign.vote.in-progress");
        if(Groups.player.size() < Math.max(2, config.voteMinPlayers)) return I18n.get("campaign.vote.not-enough-players");

        Long until = cooldownUntil.get(starter.uuid());
        if(until != null && Time.millis() < until){
            return I18n.get("campaign.vote.cooldown", (until - Time.millis()) / 1000 + 1);
        }

        String label;
        String resolved;
        if(kind == Kind.sector){
            Sector sector = SectorService.resolve(target);
            if(sector == null) return I18n.get("campaign.vote.no-such-sector", target);
            resolved = sector.planet.name + ":" + sector.id;
            label = sector.name();
        }else{
            UnlockableContent content = ResearchService.find(target);
            if(content == null) return I18n.get("campaign.vote.no-such-content", target);
            resolved = content.name;
            label = content.localizedName;
        }

        Vote vote = new Vote();
        vote.kind = kind;
        vote.arg = resolved;
        vote.label = label;
        vote.starterName = starter.plainName();
        vote.starterUuid = starter.uuid();
        vote.startedAt = Time.millis();
        vote.endsAt = vote.startedAt + (long)(config.voteDuration * 1000f);
        vote.yes.add(starter.uuid());

        current = vote;
        cooldownUntil.put(starter.uuid(), vote.endsAt + (long)(config.voteCooldown * 1000f));

        announce(I18n.get("campaign.vote.started", starter.plainName(), describe(vote), requiredVotes(), Groups.player.size()));

        Log.info("[CampaignServer] @ started a @ vote for '@'.", starter.plainName(), kind, label);
        push();
        return null;
    }

    /**
     * Casts a vote, or cancels it as an admin.
     *
     * @param choice yes/no/cancel
     * @return null on success, otherwise a player-readable reason.
     */
    public static @Nullable String cast(Player player, String choice){
        Vote vote = current;
        if(vote == null) return I18n.get("campaign.vote.no-vote");

        if("cancel".equalsIgnoreCase(choice)){
            if(!player.admin) return I18n.get("campaign.vote.cancel-admin-only");
            finish(false, I18n.get("campaign.vote.cancelled-by", player.plainName()));
            return null;
        }

        boolean yes;
        if("yes".equalsIgnoreCase(choice) || "y".equalsIgnoreCase(choice)){
            yes = true;
        }else if("no".equalsIgnoreCase(choice) || "n".equalsIgnoreCase(choice)){
            yes = false;
        }else{
            return I18n.get("campaign.vote.need-choice");
        }

        if(yes){
            vote.no.remove(player.uuid());
            if(!vote.yes.add(player.uuid())) return I18n.get("campaign.vote.already-yes");
        }else{
            vote.yes.remove(player.uuid());
            if(!vote.no.add(player.uuid())) return I18n.get("campaign.vote.already-no");
        }

        announce(I18n.get("campaign.vote.cast", player.plainName(), I18n.get(yes ? "campaign.vote.yes" : "campaign.vote.no"),
            describe(vote), vote.yes.size, requiredVotes()));

        if(vote.yes.size >= requiredVotes()){
            finish(true, null);
        }else if(vote.no.size >= requiredVotes()){
            finish(false, I18n.get("campaign.vote.too-many-no"));
        }else{
            push();
        }

        return null;
    }

    /** Cancels the running vote; {@code by} may be null for the console. */
    public static boolean cancel(@Nullable Player by){
        if(current == null) return false;
        finish(false, by == null ? I18n.get("campaign.vote.cancelled-console-reason")
            : I18n.get("campaign.vote.cancelled-by", by.plainName()));
        return true;
    }

    /** Called every tick on the server. */
    public static void update(){
        Vote vote = current;
        if(vote == null) return;

        if(Time.millis() >= vote.endsAt){
            finish(false, I18n.get("campaign.vote.expired"));
        }
    }

    /** @return a localized description of what the vote is about. */
    public static String describe(Vote vote){
        return I18n.get(vote.kind == Kind.sector ? "campaign.vote.describe-sector" : "campaign.vote.describe-research", vote.label);
    }

    private static void finish(boolean passed, @Nullable String reason){
        Vote vote = current;
        current = null;
        if(vote == null) return;

        if(!passed){
            announce(I18n.get("campaign.vote.failed", describe(vote), reason == null ? I18n.get("campaign.vote.not-enough") : reason));
            Log.info("[CampaignServer] Vote for @ failed: @.", vote.label, reason);
            push();
            return;
        }

        announce(I18n.get("campaign.vote.passed", describe(vote), vote.yes.size));
        Log.info("[CampaignServer] Vote for @ passed.", vote.label);

        try{
            if(vote.kind == Kind.sector){
                Sector sector = SectorService.resolve(vote.arg);
                if(sector == null){
                    announce(I18n.get("campaign.vote.sector-gone"));
                    return;
                }
                SectorService.enter(sector);
            }else{
                UnlockableContent content = ResearchService.find(vote.arg);
                if(content == null){
                    announce(I18n.get("campaign.vote.content-gone"));
                    return;
                }
                ResearchService.research(content, true);
                announce(I18n.get("campaign.vote.researched", content.localizedName));
            }
        }catch(Throwable t){
            Log.err("[CampaignServer] Could not apply the vote result.", t);
            announce(I18n.get("campaign.vote.apply-error", Strings.getSimpleMessage(t)));
        }finally{
            push();
        }
    }

    private static void announce(String message){
        Call.sendMessage(message);
    }

    private static void push(){
        try{
            CampaignNetwork.pushInfoToAll();
        }catch(Throwable ignored){
            //pushing is best effort
        }
    }

    /** A running vote. */
    public static class Vote{
        public Kind kind = Kind.sector;
        /** Resolved target: {@code planet:id} for sectors, content name for research. */
        public String arg = "";
        /** Display name of the target. */
        public String label = "";
        public String starterName = "";
        public String starterUuid = "";
        public long startedAt, endsAt;
        public final ObjectSet<String> yes = new ObjectSet<>();
        public final ObjectSet<String> no = new ObjectSet<>();

        public int secondsLeft(){
            return Math.max(0, (int)((endsAt - Time.millis()) / 1000L));
        }
    }
}
