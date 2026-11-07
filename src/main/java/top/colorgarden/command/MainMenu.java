package top.colorgarden.command;

import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;
import mindustry.type.Sector;
import top.colorgarden.core.SectorService;
import top.colorgarden.util.I18n;
import top.colorgarden.vote.VoteMenu;

/**
 * The entry point players get from {@code /campaign} (also {@code /c}).
 *
 * <p>It only routes: the vote dialogs, the admin panel and the sector browser each own their menus and
 * their state, so this file stays a small hub that a vanilla client can render as a
 * {@code followUpMenu} without any client-side mod.</p>
 */
public class MainMenu{
    private static int mainId = -1;
    private static boolean available;

    public static void init(){
        try{
            mainId = Menus.registerMenu((p, o) -> I18n.withLocale(I18n.localeOf(p), () -> onMain(p, o)));
            available = true;
        }catch(Throwable t){
            available = false;
            Log.warn("[CampaignServer] " + I18n.get("campaign.log.menu-unavailable", String.valueOf(t)));
        }
    }

    public static boolean available(){
        return available;
    }

    /** Opens the hub for one player, in that player's language. */
    public static void openMain(Player player){
        if(!available || player == null || player.con == null) return;

        I18n.withLocale(I18n.localeOf(player), () -> {
            String[][] options = {
                {I18n.get("campaign.main.vote"), I18n.get("campaign.main.admin")},
                {I18n.get("campaign.main.sectors"), I18n.get("campaign.main.close")}
            };

            Call.followUpMenu(player.con, mainId, I18n.get("campaign.main.title"),
                currentSectorLine(), options);
        });
    }

    /** @return the one-line summary shown above the buttons. */
    private static String currentSectorLine(){
        Sector sector = SectorService.current();
        if(sector == null || !Vars.state.isGame()) return I18n.get("campaign.msg.not-playing");

        return I18n.get("campaign.main.current", sector.planet.localizedName, sector.name(), sector.id,
            Vars.state.wave, SectorService.statusOf(sector));
    }

    private static void onMain(Player player, int option){
        if(player == null || player.con == null) return;

        //followUpMenu stacks on the client instead of replacing, so the hub has to be closed before a
        //sub-panel opens, otherwise both stay visible underneath each other
        Call.hideFollowUpMenu(player.con, mainId);

        switch(option){
            case 0 -> VoteMenu.openMain(player);
            case 1 -> {
                if(player.admin){
                    AdminPanel.openMain(player);
                }else{
                    player.sendMessage(I18n.get("campaign.error.admin-only"));
                }
            }
            case 2 -> SectorPanel.openMain(player);
            default -> {
                //closed: the menu is already hidden above
            }
        }
    }

    /** Closes the hub; other panels call this when returning to nothing. */
    public static void hide(Player player){
        if(player != null && player.con != null) Call.hideFollowUpMenu(player.con, mainId);
    }
}
