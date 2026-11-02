// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net.packet;

import top.colorgarden.net.CampaignNetwork;


import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.Packet;

/**
 * Server -&gt; client: handshake answer. Tells the client which protocol the server speaks and what
 * the requesting player is allowed to do, so a client UI can hide actions instead of failing them.
 */
public class CampaignHelloAckPacket extends Packet{
    public int protocol;
    public String gameVersion = "";
    public boolean campaign;
    public boolean admin;
    public boolean allowSwitch;
    public boolean allowResearch;

    @Override
    public void write(Writes write){
        write.i(protocol);
        write.str(gameVersion);
        write.bool(campaign);
        write.bool(admin);
        write.bool(allowSwitch);
        write.bool(allowResearch);
    }

    @Override
    public void read(Reads read){
        protocol = read.i();
        gameVersion = read.str();
        campaign = read.bool();
        admin = read.bool();
        allowSwitch = read.bool();
        allowResearch = read.bool();
    }

    @Override
    public boolean allow(boolean server){
        //only a client receives this packet
        return !server;
    }

    @Override
    public void handleClient(){
        CampaignNetwork.handleHelloAck(this);
    }
}
