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
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;

/**
 * Player votes for switching the sector or planet, unlocking research and rewiring launch pads.
 *
 * <p>Votes are independent of the "admin only" switches: those decide whether a player may perform the
 * action <i>directly</i>, while a vote lets everyone decide together. A kind is effective when voting
 * is on, the matching action is enabled and its own {@code vote*} switch is on
 * ({@link #votingEnabled(Kind)}). With the default {@code majority} decision a vote passes once
 * everyone voted and there are more yes than no.</p>
 */
public class CampaignVote{
    public enum Kind{
        sector, research, planet, destination, redirect, pause
    }

    /** The vote that is currently running, if any. */
    private static @Nullable Vote current;
    /** Cooldown end time per player uuid. */
    private static final ObjectMap<String, Long> cooldownUntil = new ObjectMap<>();

    public static void init(){
        Events.on(ResetEvent.class, event -> current = null);
        Events.run(Trigger.update, CampaignVote::update);
        VoteMenu.init();
    }

    public static @Nullable Vote active(){
        return current;
    }

    /**
     * @return whether votes for this kind are effective: voting enabled, the action enabled and the
     *         vote switch for this kind on. The matching {@code *AdminOnly} flag is deliberately
     *         ignored - it only controls direct use of the command.
     */
    public static boolean votingEnabled(Kind kind){
        CampaignConfig config = CampaignConfig.data();
        if(!config.voting) return false;

        return switch(kind){
            case sector -> config.voteSector && config.allowSectorSwitch;
            case planet -> config.votePlanet && config.allowPlanetSwitch;
            case research -> config.voteResearch && config.allowResearch;
            case destination -> config.voteDestination && config.allowSectorSwitch;
            case redirect -> config.voteRedirect && config.allowSectorSwitch;
            case pause -> config.votePause;
        };
    }

    /** @return how many yes votes are needed with the given number of online players. */
    public static int requiredVotes(int online){
        int byPercent = (int)Math.ceil(online * CampaignConfig.data().votePercent);
        return Math.max(1, Math.min(Math.max(online, 1), byPercent));
    }

    /** @return how many yes votes are needed right now. */
    public static int requiredVotes(){
        return requiredVotes(Groups.player.size());
    }

    /**
     * Case-insensitive search for vote targets of one kind.
     *
     * @param query          empty to list the first entries
     * @param includeBlocked also list research that is still locked (with the missing prerequisite)
     * @return up to {@code limit} {@code localized (id)} strings
     */
    public static Seq<String> search(Kind kind, @Nullable String query, int limit, boolean includeBlocked){
        Seq<String> out = new Seq<>();
        String needle = query == null ? "" : query.trim().toLowerCase();
        CampaignConfig config = CampaignConfig.data();

        if(kind == Kind.redirect || kind == Kind.pause) return out;

        if(kind == Kind.planet){
            for(Planet planet : SectorService.planets()){
                if(planet == SectorService.planet()) continue;

                Sector target = SectorService.targetFor(planet);
                if(target == null) continue;
                if(config.enforceSectorUnlock && !SectorService.canEnter(target)) continue;

                if(matches(planet.name, planet.localizedName, needle)){
                    out.add(planet.localizedName + " (" + planet.name + ")");
                }
                if(out.size >= limit) break;
            }
            return out;
        }

        if(kind == Kind.research){
            Planet planet = SectorService.planet();

            for(TechNode node : mindustry.content.TechTree.all){
                UnlockableContent content = node.content;
                if(content == null || content.unlocked()) continue;
                //sector presets are unlocked by playing, not by research
                if(content instanceof SectorPreset) continue;
                //content vanilla unlocks for free must not be votable either
                if(ResearchService.autoUnlockable(node)) continue;
                //vanilla only offers content of the current planet (the tech tree is shared by all)
                if(!ResearchService.shownOnPlanet(content, planet)) continue;

                if(matches(content.name, content.localizedName, needle)){
                    //locked entries stay in the list, marked with the prerequisite that is missing
                    String blocker = ResearchService.shortBlocker(content);
                    if(!blocker.isEmpty() && !includeBlocked) continue;

                    out.add(blocker.isEmpty()
                        ? content.localizedName + " (" + content.name + ")"
                        : content.localizedName + " [scarlet]— " + blocker + "[] (" + content.name + ")");
                }
                if(out.size >= limit) break;
            }
            return out;
        }

        if(kind == Kind.destination){
            Sector current = SectorService.current();
            if(current == null) return out;

            for(Sector sector : current.planet.sectors){
                if(sector == null || sector == current || !sector.hasBase()) continue;
                if(matches(sector.name(), String.valueOf(sector.id), needle)){
                    out.add(sector.name() + " (" + current.planet.name + ":" + sector.id + ")");
                }
                if(out.size >= limit) break;
            }
            return out;
        }

        //sector votes target the named sector presets of the current planet only,
        //and never the sector that is already being played
        Planet currentPlanet = SectorService.planet();
        if(currentPlanet == null) return out;

        Sector playing = SectorService.current();

        for(SectorPreset preset : SectorService.presets(currentPlanet)){
            Sector sector = preset.sector;
            if(sector == null || sector == playing) continue;
            if(config.enforceSectorUnlock && !SectorService.canEnter(sector)) continue;

            if(matches(preset.name, preset.localizedName, needle) || matches(String.valueOf(sector.id), "", needle)){
                out.add(preset.localizedName + " (" + currentPlanet.name + ":" + sector.id + ")");
            }
            if(out.size >= limit) break;
        }
        return out;
    }

    /** @return only the targets that can be used right now. */
    public static Seq<String> search(Kind kind, @Nullable String query, int limit){
        return search(kind, query, limit, false);
    }

    /** @return a localized one-line "did you mean" hint, or "" when nothing is close. */
    public static String suggest(Kind kind, @Nullable String query){
        Seq<String> found = search(kind, query, 4, false);
        return found.isEmpty() ? "" : "\n" + I18n.get("campaign.vote.suggest", found.toString(", "));
    }

    private static boolean matches(String name, String localized, String needle){
        if(needle.isEmpty()) return true;
        return (name != null && name.toLowerCase().contains(needle))
            || (localized != null && localized.toLowerCase().contains(needle));
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
            return I18n.get("campaign.vote.kind-disabled");
        }
        if(current != null) return I18n.get("campaign.vote.in-progress");
        if(Groups.player.size() < Math.max(1, config.voteMinPlayers)) return I18n.get("campaign.vote.not-enough-players");

        Long until = cooldownUntil.get(starter.uuid());
        if(until != null && Time.millis() < until){
            return I18n.get("campaign.vote.cooldown", (until - Time.millis()) / 1000 + 1);
        }

        String label;
        String resolved;
        if(kind == Kind.sector){
            Sector sector = SectorService.resolve(target);
            if(sector == null) return I18n.get("campaign.vote.no-such-sector", target) + suggest(kind, target);
            if(sector == SectorService.current()) return I18n.get("campaign.vote.already-there", sector.name());
            if(CampaignConfig.data().enforceSectorUnlock && !SectorService.canEnter(sector)){
                return I18n.get("campaign.vote.sector-locked", sector.name());
            }
            resolved = sector.planet.name + ":" + sector.id;
            label = sector.name();
        }else if(kind == Kind.planet){
            Planet planet = SectorService.planetByName(target);
            if(planet == null) return I18n.get("campaign.planet.invalid", target);

            Sector destination = SectorService.targetFor(planet);
            if(destination == null) return I18n.get("campaign.planet.empty", planet.localizedName);
            if(CampaignConfig.data().enforceSectorUnlock && !SectorService.canEnter(destination)){
                return I18n.get("campaign.vote.sector-locked", destination.name());
            }

            resolved = planet.name;
            label = planet.localizedName;
        }else if(kind == Kind.destination){
            Sector current = SectorService.current();
            if(current == null) return I18n.get("campaign.msg.not-playing");

            Sector destination = SectorService.resolve(target);
            if(destination == null) return I18n.get("campaign.vote.no-such-sector", target);
            if(destination.planet != current.planet){
                return I18n.get("campaign.destination.other-planet", destination.name(), current.planet.localizedName);
            }
            if(destination == current) return I18n.get("campaign.vote.destination-same");

            resolved = destination.planet.name + ":" + destination.id;
            label = destination.name();
        }else if(kind == Kind.redirect){
            Sector current = SectorService.current();
            if(current == null) return I18n.get("campaign.msg.not-playing");

            resolved = current.name();
            label = current.name();
        }else if(kind == Kind.pause){
            //the action is fixed when the vote starts, so it cannot flip while people are voting
            boolean paused = mindustry.Vars.state.isPaused();

            resolved = paused ? "resume" : "pause";
            label = I18n.get(paused ? "campaign.pause.resume" : "campaign.pause.pause");
        }else{
            UnlockableContent content = ResearchService.find(target);
            if(content == null) return I18n.get("campaign.vote.no-such-content", target) + suggest(kind, target);
            if(content instanceof SectorPreset) return I18n.get("campaign.research.sector-preset", content.localizedName);
            if(ResearchService.autoUnlockable(content.techNode)){
                return I18n.get("campaign.research.auto", content.localizedName);
            }
            if(!ResearchService.shownOnPlanet(content, SectorService.planet())){
                return I18n.get("campaign.research.other-planet", content.localizedName);
            }
            if(CampaignConfig.data().researchStrict && !content.unlocked()){
                String blocker = ResearchService.researchBlocker(content);
                if(blocker != null) return blocker;
            }
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
        VoteMenu.pushVote();

        //a ballot that is already decided (a lone player, or everyone has voted) should not wait for
        //the idle timeout; resolve on the next frame so the world is not reloaded inside a command
        arc.Core.app.post(() -> {
            Vote running = current;
            if(running != vote) return;

            Boolean decided = decide(running);
            if(decided != null) finish(decided, decided ? null : failReason(running));
        });

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

        vote.lastCastAt = Time.millis();

        if(yes){
            vote.no.remove(player.uuid());
            if(!vote.yes.add(player.uuid())) return I18n.get("campaign.vote.already-yes");
        }else{
            vote.yes.remove(player.uuid());
            if(!vote.no.add(player.uuid())) return I18n.get("campaign.vote.already-no");
        }

        announce(I18n.get("campaign.vote.cast", player.plainName(), I18n.get(yes ? "campaign.vote.yes" : "campaign.vote.no"),
            describe(vote), vote.yes.size, requiredVotes()));

        Boolean decided = decide(vote);
        if(decided == null){
            push();
            VoteMenu.pushVote();
        }else{
            finish(decided, decided ? null : failReason(vote));
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

        long now = Time.millis();

        if(now >= vote.endsAt){
            Boolean decided = decide(vote);
            finish(decided != null && decided, decided == null || !decided
                ? I18n.get("campaign.vote.expired") : null);
            return;
        }

        float timeout = CampaignConfig.data().voteTimeout;
        if(timeout > 0f && now - vote.lastCastAt >= (long)(timeout * 1000f)){
            Boolean decided = decide(vote);
            if(decided == null) decided = fallback(vote);
            finish(decided, decided ? null : I18n.get("campaign.vote.timeout-no", vote.yes.size, vote.no.size));
        }
    }

    /**
     * @return TRUE to pass, FALSE to fail, NULL while the vote should keep running, according to
     *         {@code voteDecision}.
     */
    private static @Nullable Boolean decide(Vote vote){
        int online = Math.max(1, Groups.player.size());
        int required = requiredVotes(online);
        String mode = CampaignConfig.data().voteDecision;

        if("veto".equals(mode)){
            if(!vote.no.isEmpty()) return false;
            return vote.yes.size >= required ? Boolean.TRUE : null;
        }

        if("majority".equals(mode)){
            if(vote.yes.size + vote.no.size >= online) return vote.yes.size > vote.no.size;
            return null;
        }

        if(vote.yes.size >= required) return true;
        if(vote.no.size >= required) return false;
        return null;
    }

    /** Resolution when a timed-out vote is still undecided. */
    private static boolean fallback(Vote vote){
        return "majority".equals(CampaignConfig.data().voteDecision) && vote.yes.size > vote.no.size;
    }

    private static String failReason(Vote vote){
        return switch(CampaignConfig.data().voteDecision){
            case "veto" -> I18n.get("campaign.vote.vetoed");
            case "majority" -> I18n.get("campaign.vote.majority-failed", vote.yes.size, vote.no.size);
            default -> I18n.get("campaign.vote.too-many-no");
        };
    }

    /** @return a localized description of what the vote is about. */
    public static String describe(Vote vote){
        String key = switch(vote.kind){
            case sector -> "campaign.vote.describe-sector";
            case planet -> "campaign.vote.describe-planet";
            case research -> "campaign.vote.describe-research";
            case destination -> "campaign.vote.describe-destination";
            case redirect -> "campaign.vote.describe-redirect";
            case pause -> "campaign.vote.describe-pause";
        };
        return I18n.get(key, vote.label);
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
            }else if(vote.kind == Kind.planet){
                Planet planet = SectorService.planetByName(vote.arg);
                Sector destination = SectorService.targetFor(planet);
                if(destination == null){
                    announce(I18n.get("campaign.vote.sector-gone"));
                    return;
                }
                SectorService.enter(destination);
            }else if(vote.kind == Kind.destination){
                Sector current = SectorService.current();
                Sector destination = SectorService.resolve(vote.arg);
                if(current == null || destination == null){
                    announce(I18n.get("campaign.vote.sector-gone"));
                    return;
                }
                SectorService.setDestination(current, destination);
                announce(I18n.get("campaign.vote.destination-done", current.name(), destination.name()));
            }else if(vote.kind == Kind.redirect){
                Sector current = SectorService.current();
                if(current == null){
                    announce(I18n.get("campaign.vote.sector-gone"));
                    return;
                }
                int count = SectorService.redirectAllExports(current);
                announce(I18n.get("campaign.vote.redirect-done", count, current.name()));
            }else if(vote.kind == Kind.pause){
                //vanilla pause: the game state machine, so the server simply stops simulating
                boolean pause = "pause".equals(vote.arg);

                mindustry.Vars.state.set(pause
                    ? mindustry.core.GameState.State.paused
                    : mindustry.core.GameState.State.playing);

                announce(I18n.get(pause ? "campaign.vote.paused" : "campaign.vote.resumed"));
            }else{
                UnlockableContent content = ResearchService.find(vote.arg);
                if(content == null){
                    announce(I18n.get("campaign.vote.content-gone"));
                    return;
                }
                if(CampaignConfig.data().researchStrict && !content.unlocked()){
                    UnlockableContent blocker = ResearchService.lockedParent(content);
                    if(blocker != null){
                        announce(I18n.get("campaign.research.parent-locked", blocker.localizedName));
                        return;
                    }
                }
                ResearchService.research(content, true);
                if(content.unlocked()){
                    announce(I18n.get("campaign.vote.researched", content.localizedName));
                }else{
                    //partial payment, exactly like vanilla: the missing part stays as progress
                    announce(I18n.get("campaign.vote.research-partial", content.localizedName,
                        ResearchService.formatStacks(ResearchService.missing(content))));
                }
            }
        }catch(Throwable t){
            Log.err("[CampaignServer] Could not apply the vote result.", t);
            announce(I18n.get("campaign.vote.apply-error", Strings.getSimpleMessage(t)));
        }finally{
            push();
            VoteMenu.hideVote();
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

        /** Timestamp of the most recent vote (start counts as one). */
        public long lastCastAt = Time.millis();

        public int secondsLeft(){
            return Math.max(0, (int)((endsAt - Time.millis()) / 1000L));
        }
    }
}
