// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net.packet;

import top.colorgarden.net.CampaignInfo;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.net.CampaignProtocol;


import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.Packet;

/**
 * Server -&gt; client: result of a request, or an unsolicited update when {@code requestId} is
 * {@code CampaignProtocol.pushId}. {@code json} carries a {@code CampaignInfo} for info replies.
 */
public class CampaignReplyPacket extends Packet{
    public int requestId;
    public byte action;
    public boolean success;
    public String message = "";
    public String json = "";

    @Override
    public void write(Writes write){
        write.i(requestId);
        write.b(action);
        write.bool(success);
        write.str(message);
        write.str(json);
    }

    @Override
    public void read(Reads read){
        requestId = read.i();
        action = read.b();
        success = read.bool();
        message = read.str();
        json = read.str();
    }

    @Override
    public boolean allow(boolean server){
        return !server;
    }

    @Override
    public void handleClient(){
        CampaignNetwork.handleReply(this);
    }
}
