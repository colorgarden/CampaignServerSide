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
        sector, research, planet, destination, redirect, pause, wave, abandon
    }

    /** Votes that are currently running; several kinds may run in parallel. */
    private static final Seq<Vote> votes = new Seq<>();
    /** Cooldown end time per player uuid. */
    private static final ObjectMap<String, Long> cooldownUntil = new ObjectMap<>();
    /** Targets whose launch setup was already confirmed, so the vote can go ahead. */
    private static final ObjectSet<String> setupDone = new ObjectSet<>();

    public static void init(){
        Events.on(ResetEvent.class, event -> votes.clear());
        Events.run(Trigger.update, CampaignVote::update);
        VoteMenu.init();
    }

    public static @Nullable Vote active(){
        return votes.isEmpty() ? null : votes.peek();
    }

    /** @return a snapshot of the running votes, oldest first. */
    public static Seq<Vote> activeVotes(){
        return votes.copy();
    }

    /** @return the vote of this kind that is still running, dropping ones that clearly expired. */
    public static @Nullable Vote running(Kind kind){
        long now = Time.millis();

        for(Vote vote : votes.copy()){
            //a vote that should have settled but never did would block this kind forever
            if(vote.endsAt + 5000L < now){
                Log.warn("[CampaignServer] dropping stale vote @ (@).", vote.kind, vote.label);
                votes.remove(vote);
                continue;
            }

            if(vote.kind == kind) return vote;
        }

        return null;
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
            case wave -> config.voteWave;
            case abandon -> config.voteAbandon;
        };
    }

    /** @return how many yes votes are needed with the given number of online players. */
    public static int requiredVotes(int online){
        int byPercent = (int)Math.ceil(online * CampaignConfig.data().votePercent);
        return Math.max(1, Math.min(Math.max(online, 1), byPercent));
    }

    /** @return how many yes votes are needed right now. */
    public static int requiredVotes(){
        return requiredVotes(Math.max(1, top.colorgarden.core.ActivityTracker.activeCount()));
    }

    /**
     * Case-insensitive search for vote targets of one kind.
     *
     * @param query          empty to list the first entries
     * @param includeBlocked also list research that is still locked (with the missing prerequisite)
     * @return up to {@code limit} {@code localized (id)} strings
     */
    public static Seq<String> search(Kind kind, @Nullable String query, int limit, boolean includeBlocked){
        return search(kind, query, limit, includeBlocked, true);
    }

    /** @param includeNumbered also list sectors that have no preset (the "#175" entries). */
    public static Seq<String> search(Kind kind, @Nullable String query, int limit, boolean includeBlocked, boolean includeNumbered){
        Seq<String> out = new Seq<>();
        String needle = query == null ? "" : query.trim().toLowerCase();
        CampaignConfig config = CampaignConfig.data();

        if(kind == Kind.redirect || kind == Kind.pause || kind == Kind.wave || kind == Kind.abandon) return out;

        if(kind == Kind.planet){
            for(Planet planet : SectorService.planets()){
                if(planet == SectorService.planet()) continue;

                Sector target = SectorService.targetFor(planet);
                if(target == null) continue;
                //a planet is switchable when the planet itself is accessible; the sector inside it is
                //picked afterwards and may still need its own unlock
                if(config.enforceSectorUnlock && !planet.accessible) continue;

                if(matches(planet.name, planet.localizedName, needle)){
                    out.add(planet.localizedName);
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

                if(matches(content.name, I18n.content(content), needle)){
                    //locked entries stay in the list, marked with the prerequisite that is missing
                    String blocker = ResearchService.shortBlocker(content);
                    if(!blocker.isEmpty() && !includeBlocked) continue;

                    out.add(blocker.isEmpty()
                        ? I18n.content(content)
                        : I18n.content(content) + " [scarlet]— " + blocker + "[]");
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
                    out.add(sector.name() + currentSuffix(sector) + " " + sectorStatus(sector));
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
            if(sector == null) continue;
            if(!includeBlocked && config.enforceSectorUnlock && !SectorService.canEnter(sector)) continue;

            if(matches(preset.name, I18n.content(preset), needle) || matches(String.valueOf(sector.id), "", needle)){
                out.add(I18n.content(preset) + currentSuffix(sector) + " " + sectorStatus(sector) + lockSuffix(sector));
            }
            if(out.size >= limit) break;
        }

        //numbered sectors have no preset: their terrain is generated procedurally, so no map asset is
        //needed. Vanilla only lets you land on one next to a sector that has a base (allowLanding).
        if(includeNumbered) for(Sector sector : currentPlanet.sectors){
            if(sector == null || sector.preset != null) continue;
            if(!includeBlocked && config.enforceSectorUnlock && !SectorService.canEnter(sector)) continue;

            if(matches(String.valueOf(sector.id), "", needle)){
                out.add("#" + sector.id + currentSuffix(sector) + " " + sectorStatus(sector) + lockSuffix(sector));
            }
            if(out.size >= limit) break;
        }
        return out;
    }

    /** @return the prerequisite shown after a sector that cannot be entered yet, or "". */
    private static String lockSuffix(Sector sector){
        if(SectorService.canEnter(sector)) return "";

        String hint = SectorService.launchHint(sector);
        String reason = hint == null || hint.isEmpty() ? I18n.get("campaign.sector.locked-short") : hint;
        return " [scarlet]— " + reason + "[]";
    }

    /** @return "此区块" when this is the sector being played, otherwise an empty string. */
    private static String currentSuffix(Sector sector){
        return sector == SectorService.current() ? " " + I18n.get("campaign.sector.current") : "";
    }

    /**
     * @return the sector state shown next to its name: captured, launched or not launched.
     *
     * <p>{@code Sector#isCaptured()} alone is not enough: it is {@code save != null && !info.waves &&
     * !info.attack}, so a sector that was captured once and later lost its base (the save keeps the
     * "no waves" flag) would still read as captured. Without a standing core the sector falls back to
     * "not launched", which is what the planet view shows for a sector you cannot land on yet.</p>
     */
    public static String sectorStatus(Sector sector){
        if(sector == null) return "";

        if(!sector.hasBase()) return I18n.get("campaign.sector.status.unlaunched");

        return sector.isCaptured()
            ? I18n.get("campaign.sector.status.captured")
            : I18n.get("campaign.sector.status.launched");
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
        //an AFK player would count as the only voter and pass the vote instantly
        if(top.colorgarden.core.ActivityTracker.isAfk(starter)) return I18n.get("campaign.vote.afk-cannot-start");

        if(top.colorgarden.core.ActivityTracker.activeCount() < Math.max(1, config.voteMinPlayers)){
            return I18n.get("campaign.vote.not-enough-players");
        }

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
                String hint = SectorService.launchHint(sector);
                if(!hint.isEmpty()) return I18n.get("campaign.vote.sector-locked", sector.name()) + "\n" + hint;
                return I18n.get("campaign.vote.sector-locked", sector.name());
            }
            //a sector that has no base yet is configured before the vote starts, not after it passed
            String setupKey = starter.uuid() + "|" + sector.planet.name + ":" + sector.id;
            if(!sector.hasBase() && !setupDone.remove(setupKey)){
                final String rawTarget = target;
                top.colorgarden.command.LaunchPanel.showForVote(starter,
                    new SectorService.PendingLaunch(sector, false, true),
                    () -> {
                        setupDone.add(setupKey);
                        start(starter, kind, rawTarget);
                    });
                return null;
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
        }else if(kind == Kind.abandon){
            if(!mindustry.Vars.state.isGame()) return I18n.get("campaign.msg.not-playing");

            Sector playingSector = SectorService.current();
            if(playingSector == null) return I18n.get("campaign.msg.not-playing");

            resolved = playingSector.name();
            label = I18n.get("campaign.vote.abandon-label", playingSector.name());
        }else if(kind == Kind.wave){
            if(!mindustry.Vars.state.isGame()) return I18n.get("campaign.msg.not-playing");

            //one wave per vote, like the command
            resolved = "1";
            label = I18n.get("campaign.wave.skip-label", 1);
        }else{
            UnlockableContent content = ResearchService.find(target);
            if(content == null) return I18n.get("campaign.vote.no-such-content", target) + suggest(kind, target);
            if(content instanceof SectorPreset) return I18n.get("campaign.research.sector-preset", I18n.content(content));
            if(ResearchService.autoUnlockable(content.techNode)){
                return I18n.get("campaign.research.auto", I18n.content(content));
            }
            if(!ResearchService.shownOnPlanet(content, SectorService.planet())){
                return I18n.get("campaign.research.other-planet", I18n.content(content));
            }
            if(CampaignConfig.data().researchStrict && !content.unlocked()){
                String blocker = ResearchService.researchBlocker(content);
                if(blocker != null) return blocker;
            }
            resolved = content.name;
            label = I18n.content(content);
        }

        //only an identical vote (same kind and same target) blocks a new one; different targets
        //of the same kind may run side by side
        for(Vote running : votes){
            if(running.kind == kind && running.arg != null && running.arg.equals(resolved)){
                return I18n.get("campaign.vote.in-progress-which", describe(running));
            }
        }

        Vote vote = new Vote();
        vote.kind = kind;
        vote.arg = resolved;
        vote.label = label;
        vote.starterName = starter.plainName();
        vote.starterUuid = starter.uuid();
        vote.startedAt = Time.millis();
        vote.endsAt = vote.startedAt + (long)(config.voteDuration * 1000f);
        //independent settings, enforced chain: timeout < lead < duration
        float lead = Math.max(0f, Math.min(config.votePushSeconds, config.voteDuration - 1f));
        vote.effectiveTimeout = Math.max(0f, Math.min(config.voteTimeout, lead));
        vote.pushAt = vote.endsAt - (long)(lead * 1000f);
        //calling the vote is itself a yes: the starter does not have to cast again
        vote.yes.add(starter.uuid());

        //freeze the quorum at start: the bar must not move while the vote runs
        vote.onlineAtStart = Math.max(1, top.colorgarden.core.ActivityTracker.activeCount());
        vote.requiredAtStart = requiredVotes(vote.onlineAtStart);

        votes.add(vote);

        announce(I18n.get("campaign.vote.started", starter.plainName(), describe(vote), vote.required(), vote.onlineAtStart));

        Log.info("[CampaignServer] @ started a @ vote for '@'.", starter.plainName(), kind, label);
        push();
        //the ballot is pushed later, when the configured tail of the window starts

        //a ballot that is already decided (a lone player, or everyone has voted) should not wait for
        //the idle timeout; resolve on the next frame so the world is not reloaded inside a command
        arc.Core.app.post(() -> {
            if(!votes.contains(vote)) return;

            Boolean decided = decide(vote);
            if(decided != null) finish(vote, decided, decided ? null : failReason(vote));
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
        return cast(player, choice, active());
    }

    /**
     * Casts a vote chosen by chat, where several votes may be running at once.
     *
     * @param index 1-based vote number, or null to use the only running vote; with more than one
     *              running vote and no index the numbered list is sent instead of guessing
     * @return null when the vote was cast or the player was asked to pick a number
     */
    public static @Nullable String cast(Player player, String choice, @Nullable String index){
        Seq<Vote> running = activeVotes();
        if(running.isEmpty()) return I18n.get("campaign.vote.no-vote");

        if(index != null && !index.trim().isEmpty()){
            int number = -1;
            try{
                number = Integer.parseInt(index.trim());
            }catch(NumberFormatException ignored){
                //fall through to the invalid-index message
            }

            if(number < 1 || number > running.size){
                return I18n.get("campaign.vote.index-invalid", number < 1 ? index : String.valueOf(number), running.size) + "\n" + list(running);
            }

            return cast(player, choice, running.get(number - 1));
        }

        if(running.size > 1){
            player.sendMessage(I18n.get("campaign.vote.pick-number", list(running)));
            return null;
        }

        return cast(player, choice, running.first());
    }

    /** @return a numbered list of the running votes, one per line. */
    public static String list(Seq<Vote> running){
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < running.size; i++){
            Vote vote = running.get(i);
            if(builder.length() > 0) builder.append('\n');
            builder.append("[accent]").append(i + 1).append("[] ").append(describe(vote))
                .append(" [white]").append(vote.yes.size).append('/').append(vote.required())
                .append(" [accent]").append(vote.secondsLeft()).append("s[]");
        }
        return builder.toString();
    }
    /** Casts a vote on one specific vote (needed while several run in parallel). */
    public static @Nullable String cast(Player player, String choice, @Nullable Vote vote){
        if(vote == null) return I18n.get("campaign.vote.no-vote");

        if("cancel".equalsIgnoreCase(choice)){
            if(!player.admin) return I18n.get("campaign.vote.cancel-admin-only");
            finish(vote, false, I18n.get("campaign.vote.cancelled-by", player.plainName()));
            return null;
        }

        //starting the vote already counted a yes for the starter; a second ballot from them would let
        //one player flip or confirm their own proposal
        if(vote.starterUuid != null && vote.starterUuid.equals(player.uuid())){
            return I18n.get("campaign.vote.starter-already-voted");
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
            describe(vote), vote.yes.size, vote.required()));

        Boolean decided = decide(vote);
        if(decided == null){
            push();
            if(vote.pushed) VoteMenu.pushVote();
        }else{
            finish(vote, decided, decided ? null : failReason(vote));
        }

        return null;
    }

    /** Cancels the running vote; {@code by} may be null for the console. */
    public static boolean cancel(@Nullable Player by){
        return cancel(by, active());
    }

    /** Cancels one specific vote; {@code by} may be null for the console. */
    public static boolean cancel(@Nullable Player by, @Nullable Vote vote){
        if(vote == null) return false;
        finish(vote, false, by == null ? I18n.get("campaign.vote.cancelled-console-reason")
            : I18n.get("campaign.vote.cancelled-by", by.plainName()));
        return true;
    }

    /** Called every tick on the server. */
    public static void update(){
        if(votes.isEmpty()) return;

        long now = Time.millis();
        float timeout = CampaignConfig.data().voteTimeout;

        //every vote runs on its own clock, so several can be settled in the same tick
        for(Vote vote : votes.copy()){
            //pop that ballot once, when the configured tail of its window starts
            if(!vote.pushed && now >= vote.pushAt){
                vote.pushed = true;
                VoteMenu.pushVote(vote);
            }

            if(now >= vote.endsAt){
                Boolean decided = decide(vote);
                finish(vote, decided != null && decided, decided == null || !decided
                    ? I18n.get("campaign.vote.expired") : null);
                continue;
            }

            //idle timeout only counts once the ballot was actually shown; otherwise the vote
            //would settle before anyone was ever asked (window tail vs idle timeout collision)
            long idleFrom = Math.max(vote.lastCastAt, vote.pushAt);
            if(vote.pushed && vote.effectiveTimeout > 0f && now - idleFrom >= (long)(vote.effectiveTimeout * 1000f)){
                Boolean decided = decide(vote);
                if(decided == null) decided = fallback(vote);
                finish(vote, decided, decided ? null : I18n.get("campaign.vote.timeout-no", vote.yes.size, vote.no.size));
            }
        }
    }
    /**
     * @return TRUE to pass, FALSE to fail, NULL while the vote should keep running, according to
     *         {@code voteDecision}.
     */
    private static @Nullable Boolean decide(Vote vote){
        int online = vote.onlineAtStart > 0 ? vote.onlineAtStart : Math.max(1, top.colorgarden.core.ActivityTracker.activeCount());
        int required = vote.required();

        int yes = vote.yes.size, no = vote.no.size;
        int remaining = Math.max(0, online - yes - no);
        String mode = CampaignConfig.data().voteDecision;

        //enough yes votes: the result cannot change any more
        if(yes >= required) return true;
        //not even every remaining player voting yes could reach the bar
        if(yes + remaining < required) return false;

        if("veto".equals(mode)){
            return no > 0 ? Boolean.FALSE : null;
        }

        if("majority".equals(mode)){
            //everyone who counts has voted
            return yes + no >= online ? Boolean.valueOf(yes > no) : null;
        }

        return no >= required ? Boolean.FALSE : null;
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
            case wave -> "campaign.vote.describe-wave";
            case abandon -> "campaign.vote.describe-abandon";
        };
        return I18n.get(key, vote.label);
    }

    /** @return the player who started the vote, when they are still online. */
    private static @Nullable Player starter(@Nullable Vote vote){
        if(vote == null) return null;

        for(Player player : Groups.player){
            if(player.uuid().equals(vote.starterUuid)) return player;
        }

        return null;
    }

    private static void finish(@Nullable Vote vote, boolean passed, @Nullable String reason){
        if(vote == null) return;
        votes.remove(vote);

        //the cooldown starts when the result is known, not at the planned end of the voting window
        if(vote.starterUuid != null){
            cooldownUntil.put(vote.starterUuid,
                Time.millis() + (long)(CampaignConfig.data().voteCooldown * 1000f));
        }

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
                //the starter picks the core/cargo for a sector that has to be generated, exactly like
                //vanilla only asks the player who launches
                //the launch setup was already confirmed before the vote started, so this entry must
                //not ask for it again
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
                int total = SectorService.countExportsTo(current);
                announce(count == 0
                    ? I18n.get("campaign.vote.redirect-none", total, current.name())
                    : I18n.get("campaign.vote.redirect-done", count, current.name(), total));
            }else if(vote.kind == Kind.pause){
                //vanilla pause: the game state machine, so the server simply stops simulating
                boolean pause = "pause".equals(vote.arg);

                mindustry.Vars.state.set(pause
                    ? mindustry.core.GameState.State.paused
                    : mindustry.core.GameState.State.playing);

                announce(I18n.get(pause ? "campaign.vote.paused" : "campaign.vote.resumed"));
            }else if(vote.kind == Kind.abandon){
                //vanilla PlanetDialog#abandonSectorConfirm: destroying every core ends the run
                for(mindustry.gen.Building core : mindustry.Vars.state.rules.defaultTeam.cores().copy()){
                    core.kill();
                }

                announce(I18n.get("campaign.vote.abandoned", vote.arg));
            }else if(vote.kind == Kind.wave){
                if(mindustry.Vars.state.isGame()){
                    mindustry.Vars.logic.skipWave();
                }
                announce(I18n.get("campaign.vote.wave-skipped", 1, mindustry.Vars.state.wave));
            }else{
                UnlockableContent content = ResearchService.find(vote.arg);
                if(content == null){
                    announce(I18n.get("campaign.vote.content-gone"));
                    return;
                }
                if(CampaignConfig.data().researchStrict && !content.unlocked()){
                    UnlockableContent blocker = ResearchService.lockedParent(content);
                    if(blocker != null){
                        announce(I18n.get("campaign.research.parent-locked", I18n.content(blocker)));
                        return;
                    }
                }
                ResearchService.research(content, true);
                if(content.unlocked()){
                    announce(I18n.get("campaign.vote.researched", I18n.content(content)));
                }else{
                    //partial payment, exactly like vanilla: the missing part stays as progress
                    announce(I18n.get("campaign.vote.research-partial", I18n.content(content),
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
        /** Quorum frozen when the vote started, so displays never disagree. */
        public int onlineAtStart = 1, requiredAtStart = 1;
        /** Idle timeout actually in force for this vote (clamped below the lead time). */
        public float effectiveTimeout;

        public int required(){
            return requiredAtStart > 0 ? requiredAtStart : CampaignVote.requiredVotes();
        }
        /** When the ballot dialog should pop up (near the end of the window). */
        public long pushAt;
        /** Whether the ballot was already pushed to the players. */
        public boolean pushed;
        public final ObjectSet<String> yes = new ObjectSet<>();
        public final ObjectSet<String> no = new ObjectSet<>();

        /** Timestamp of the most recent vote (start counts as one). */
        public long lastCastAt = Time.millis();

        public int secondsLeft(){
            return Math.max(0, (int)((endsAt - Time.millis()) / 1000L));
        }
    }
}
