package top.colorgarden.vote;

import arc.Core;
import arc.files.Fi;
import arc.util.Time;
import top.colorgarden.util.I18n;

/**
 * Publishes the running vote to the scoreboard.
 *
 * <p>ScriptAgent renders the top-left board from its own script side, so the plugin cannot call into
 * it without a hard dependency. Instead the vote is written to a tiny state file that a script reads
 * and turns into a display line; the file is empty when nothing is running.</p>
 */
public final class ScoreboardFeed{
    /** Lines: {@code desc|yes|no|abstain|required|seconds}. */
    private static final String PATH = "config/scripts/data/campaign-vote.txt";

    private static long lastWrite;
    private static String lastContent = "";

    private ScoreboardFeed(){
    }

    /** Writes at most once a second, and only when the text actually changed. */
    public static void tick(){
        long now = Time.millis();
        if(now - lastWrite < 1000L) return;
        lastWrite = now;

        CampaignVote.Vote vote = CampaignVote.active();
        String content = "";

        if(vote != null){
            content = CampaignVote.describe(vote) + "|" + vote.yes.size + "|" + vote.no.size + "|"
                + vote.abstain.size + "|" + vote.required() + "|" + vote.secondsLeft();
        }

        if(content.equals(lastContent)) return;
        lastContent = content;

        try{
            Fi file = Core.files.local(PATH);
            file.parent().mkdirs();
            file.writeString(content, false, "UTF-8");
        }catch(Throwable ignored){
            //the board is cosmetic; a filesystem problem must never break the vote itself
        }
    }

    /** @return the label shown for a yes/no/abstain side, used by the display. */
    static String sideLabel(String key){
        return I18n.get(key);
    }
}
