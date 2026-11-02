// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net.packet;

import top.colorgarden.net.CampaignNetwork;


import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.NetConnection;
import mindustry.net.Packet;

/**
 * Client -&gt; server: tells the server that this client speaks the campaign API.
 * Sent only after the client saw the {@code campaign-api} rules tag.
 */
public class CampaignHelloPacket extends Packet{
    public int protocol;
    public String gameVersion = "";

    @Override
    public void write(Writes write){
        write.i(protocol);
        write.str(gameVersion);
    }

    @Override
    public void read(Reads read){
        protocol = read.i();
        gameVersion = read.str();
    }

    @Override
    public boolean allow(boolean server){
        //a client only ever sends this packet, never receives it
        return server;
    }

    @Override
    public void handleServer(NetConnection con){
        CampaignNetwork.handleHello(con, this);
    }
}
