// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden;

import top.colorgarden.command.CampaignCommands;
import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.CampaignHooks;
import top.colorgarden.core.ResearchService;
import top.colorgarden.core.SectorService;
import top.colorgarden.net.CampaignClientApi;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.util.I18n;




import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.mod.Plugin;

import static mindustry.Vars.headless;

/**
 * Server-side plugin that makes the Mindustry campaign playable on a dedicated server.
 *
 * <p>Entry point. {@link #registerServerCommands(CommandHandler)} and
 * {@link #registerClientCommands(CommandHandler)} are called while the mod list is loaded,
 * {@link #init()} is called afterwards, once the server modules (and {@code ServerControl})
 * exist.</p>
 *
 * <p>The same jar doubles as the client-side API provider: on a client only
 * {@link CampaignNetwork} is active and other mods talk to the server through
 * {@code top.colorgarden.campaignclientapi.CampaignClientApi}.</p>
 */
public class CampaignServerMod extends Plugin{
    /** Set in {@link #init()}; may be null if init has not run yet. */
    public static CampaignServerMod instance;

    @Override
    public void registerServerCommands(CommandHandler handler){
        CampaignCommands.registerServer(handler);
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        CampaignCommands.registerClient(handler);
    }

    @Override
    public void init(){
        instance = this;
        try{
            //packets have to be registered on both sides, before anything else may fail
            CampaignNetwork.init();

            CampaignConfig.load();
            I18n.load(CampaignConfig.data().language);

            //a client already has the game bundle; only a server needs the shipped content names
            if(headless) I18n.localizeContent();

            if(headless){
                SectorService.init();
                ResearchService.init();
                CampaignHooks.install();
                Log.info("[CampaignServer] " + I18n.get("campaign.log.loaded", "campaign-help"));
            }else{
                Log.info("[CampaignServer] " + I18n.get("campaign.log.client-mode-ok"));
            }
        }catch(Throwable t){
            Log.err("[CampaignServer] Initialization failed; the plugin will stay inactive.", t);
        }
    }
}
