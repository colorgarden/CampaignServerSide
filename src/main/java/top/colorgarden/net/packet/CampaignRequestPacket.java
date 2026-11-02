// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net.packet;

import top.colorgarden.net.CampaignNetwork;


import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.net.NetConnection;
import mindustry.net.Packet;

/** Client -&gt; server: one API call (info / sector switch / research / save). */
public class CampaignRequestPacket extends Packet{
    /** Echoed back in the reply; {@code 0} is reserved for server pushes. */
    public int requestId;
    public byte action;
    public String arg = "";

    @Override
    public void write(Writes write){
        write.i(requestId);
        write.b(action);
        write.str(arg);
    }

    @Override
    public void read(Reads read){
        requestId = read.i();
        action = read.b();
        arg = read.str();
    }

    @Override
    public boolean allow(boolean server){
        return server;
    }

    @Override
    public void handleServer(NetConnection con){
        CampaignNetwork.handleRequest(con, this);
    }
}
