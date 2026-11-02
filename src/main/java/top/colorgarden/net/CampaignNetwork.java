// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net;

import top.colorgarden.config.CampaignConfig;
import top.colorgarden.core.ResearchService;
import top.colorgarden.core.SectorService;
import top.colorgarden.net.CampaignClientApi;
import top.colorgarden.net.CampaignInfo;
import top.colorgarden.net.CampaignProtocol;
import top.colorgarden.net.packet.CampaignHelloAckPacket;
import top.colorgarden.net.packet.CampaignHelloPacket;
import top.colorgarden.net.packet.CampaignReplyPacket;
import top.colorgarden.net.packet.CampaignRequestPacket;
import top.colorgarden.util.CampaignException;
import top.colorgarden.util.I18n;
import top.colorgarden.vote.CampaignVote;


import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import arc.util.Time;
import mindustry.core.Version;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Player;
import mindustry.game.EventType.*;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.type.Sector;

import static mindustry.Vars.*;

/**
 * The campaign network protocol.
 *
 * <p>Server side: answers {@link CampaignHelloPacket} handshakes and {@link CampaignRequestPacket}
 * calls, with admin checks matching the console commands. Client side: tracks the handshake and
 * routes replies to the callbacks registered by {@link top.colorgarden.campaignclientapi.CampaignClientApi}.</p>
 *
 * <p>The four packet classes are registered in a fixed order, so the server and the client must run
 * the same build of this mod (and the same mod set, since custom packet ids are positional).</p>
 */
public class CampaignNetwork{
    public static boolean registered;

    // --- client state ---
    private static boolean helloSent, helloReceived;
    private static int serverProtocol = -1;
    private static boolean serverAllowsSwitch = true, serverAllowsResearch = true, serverAdmin;
    private static @Nullable CampaignInfo lastInfo;
    private static @Nullable Cons<CampaignInfo> pushListener;
    private static final ObjectMap<Integer, Pending> pending = new ObjectMap<>();
    private static int nextRequestId = 1;
    private static float purgeTimer;

    // --- server state ---
    private static final ObjectSet<NetConnection> capable = new ObjectSet<>();
    private static final ObjectMap<NetConnection, Long> lastRequest = new ObjectMap<>();
    /** Minimum time between two API calls from the same connection, in milliseconds. */
    private static final long requestCooldown = 100L;

    public static void init(){
        if(registered) return;
        registered = true;

        //registration order is part of the protocol: keep it identical on both sides
        Net.registerPacket(CampaignHelloPacket::new);
        Net.registerPacket(CampaignHelloAckPacket::new);
        Net.registerPacket(CampaignRequestPacket::new);
        Net.registerPacket(CampaignReplyPacket::new);

        if(headless){
            Events.on(WorldLoadEvent.class, event -> updateServerTag());
            Events.on(ResetEvent.class, event -> {
                capable.clear();
                lastRequest.clear();
            });
            Events.on(PlayerLeave.class, event -> {
                if(event.player != null && event.player.con != null){
                    capable.remove(event.player.con);
                    lastRequest.remove(event.player.con);
                }
            });
            updateServerTag();
        }else{
            Events.on(WorldLoadEvent.class, event -> onClientWorldLoad());
            Events.on(StateChangeEvent.class, event -> {
                if(event.to == mindustry.core.GameState.State.menu) resetClient();
            });
            Events.run(Trigger.update, CampaignNetwork::updateClient);
        }

        Log.info("[CampaignServer] Campaign API protocol @ registered.", CampaignProtocol.version);
    }

    // ---------------------------------------------------------------- shared helpers

    /** @return whether the server this client is connected to advertises the campaign API. */
    public static boolean serverSupportsApi(){
        try{
            return state.rules != null && state.rules.tags != null && state.rules.tags.containsKey(CampaignProtocol.tagKey);
        }catch(Throwable t){
            return false;
        }
    }

    /** @return how many clients completed the handshake on this server. */
    public static int capableCount(){
        return capable.size;
    }

    /** @return the handshake state of this client (always false on a server). */
    public static boolean clientReady(){
        return helloReceived;
    }

    public static @Nullable CampaignInfo lastInfo(){
        return lastInfo;
    }

    public static void setPushListener(Cons<CampaignInfo> listener){
        pushListener = listener;
    }

    public static int serverProtocol(){
        return serverProtocol;
    }

    public static boolean serverAllowsSwitch(){
        return serverAllowsSwitch;
    }

    public static boolean serverAllowsResearch(){
        return serverAllowsResearch;
    }

    public static boolean isAdmin(){
        return serverAdmin;
    }

    // ---------------------------------------------------------------- server side

    /**
     * Advertises the API through the rules tag that is sent with the world data.
     *
     * <p>Called whenever a world is loaded and again once a sector is ready to be hosted, because
     * {@code state.isGame()} is still false while a save is being read.</p>
     */
    public static void updateServerTag(){
        if(state.rules == null) return;

        if(state.rules.sector != null){
            state.rules.tags.put(CampaignProtocol.tagKey, Integer.toString(CampaignProtocol.version));
        }else{
            state.rules.tags.remove(CampaignProtocol.tagKey);
        }
    }

    public static void handleHello(NetConnection con, CampaignHelloPacket packet){
        Player player = con.player;
        if(player == null) return;

        capable.add(con);

        CampaignHelloAckPacket ack = new CampaignHelloAckPacket();
        ack.protocol = CampaignProtocol.version;
        ack.gameVersion = Version.buildString();
        ack.campaign = state.isGame() && state.rules != null && state.rules.sector != null;
        ack.admin = player.admin;
        ack.allowSwitch = CampaignConfig.data().allowSectorSwitch;
        ack.allowResearch = CampaignConfig.data().allowResearch;
        con.send(ack, true);

        Log.info("[CampaignServer] @ completed the campaign API handshake (client protocol @).", player.plainName(), packet.protocol);
    }

    public static void handleRequest(NetConnection con, CampaignRequestPacket packet){
        Player player = con.player;
        if(player == null) return;

        if(!allowRequest(con)){
            Log.debug("[CampaignServer] Dropping rate limited API call from @.", player.plainName());
            return;
        }

        switch(packet.action){
            case CampaignProtocol.actionInfo -> {
                CampaignInfo info = CampaignInfo.build(packet.arg);
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, true, "", info.toJson());
            }

            case CampaignProtocol.actionSwitch -> {
                if(!requirePermission(con, packet, player, CampaignConfig.data().sectorSwitchAdminOnly)) return;
                if(!CampaignConfig.data().allowSectorSwitch){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.error.switch-disabled"), "");
                    return;
                }

                Sector sector = SectorService.resolve(packet.arg);
                if(sector == null){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.error.no-such-sector", packet.arg), "");
                    return;
                }

                try{
                    SectorService.enter(sector, false, player.admin && CampaignConfig.data().adminBypassLock);
                    CampaignInfo info = CampaignInfo.build("");
                    info.admin = player.admin;
                    sendReply(con, packet.requestId, packet.action, true, I18n.get("campaign.api.switched", sector.name()), info.toJson());
                    Log.info("[CampaignServer] @ switched the campaign to sector '@'.", player.plainName(), sector.name());
                }catch(CampaignException e){
                    sendReply(con, packet.requestId, packet.action, false, e.getMessage(), "");
                }catch(Throwable t){
                    Log.err("[CampaignServer] API sector switch failed.", t);
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.error.unexpected", Strings.getSimpleMessage(t)), "");
                }
            }

            case CampaignProtocol.actionResearch -> {
                if(!requirePermission(con, packet, player, CampaignConfig.data().researchAdminOnly)) return;
                if(!CampaignConfig.data().allowResearch){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.error.research-disabled"), "");
                    return;
                }

                UnlockableContent unlockable = ResearchService.find(packet.arg);
                if(unlockable == null){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.research.unknown", packet.arg), "");
                    return;
                }

                boolean changed = ResearchService.research(unlockable, true);
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, true,
                    I18n.get(changed ? "campaign.api.researched" : "campaign.api.already-unlocked", unlockable.name), info.toJson());
                Log.info("[CampaignServer] @ researched '@' through the API.", player.plainName(), unlockable.name);
            }

            case CampaignProtocol.actionResearchAll -> {
                if(!requirePermission(con, packet, player, CampaignConfig.data().researchAdminOnly)) return;
                if(!CampaignConfig.data().allowResearch){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.error.research-disabled"), "");
                    return;
                }

                int count = ResearchService.researchAll(SectorService.planet());
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, true, I18n.get("campaign.api.research-all", count), info.toJson());
                Log.info("[CampaignServer] @ unlocked all research through the API.", player.plainName());
            }

            case CampaignProtocol.actionSave -> {
                if(!requirePermission(con, packet, player, true)) return;

                boolean ok = SectorService.saveCurrent();
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, ok, I18n.get(ok ? "campaign.api.saved" : "campaign.msg.save-failed"), info.toJson());
            }

            case CampaignProtocol.actionVoteSector, CampaignProtocol.actionVoteResearch -> {
                CampaignVote.Kind kind = packet.action == CampaignProtocol.actionVoteSector
                    ? CampaignVote.Kind.sector : CampaignVote.Kind.research;
                String problem = CampaignVote.start(player, kind, packet.arg);
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, problem == null,
                    problem == null ? I18n.get("campaign.api.vote-started") : problem, info.toJson());
            }

            case CampaignProtocol.actionVoteCast -> {
                String problem = CampaignVote.cast(player, packet.arg);
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, problem == null,
                    problem == null ? I18n.get("campaign.api.vote-counted") : problem, info.toJson());
            }

            case CampaignProtocol.actionVoteCancel -> {
                if(!player.admin){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.vote.cancel-admin-only"), "");
                    return;
                }
                boolean cancelled = CampaignVote.cancel(player);
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, cancelled,
                    I18n.get(cancelled ? "campaign.api.vote-cancelled" : "campaign.vote.no-vote"), info.toJson());
            }

            case CampaignProtocol.actionSetDestination -> {
                if(!requirePermission(con, packet, player, true)) return;

                Sector sector = SectorService.current();
                if(sector == null){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.msg.not-playing"), "");
                    return;
                }

                if(packet.arg.isEmpty() || "clear".equalsIgnoreCase(packet.arg)){
                    SectorService.setDestination(sector, null);
                    CampaignInfo info = CampaignInfo.build("");
                    info.admin = player.admin;
                    sendReply(con, packet.requestId, packet.action, true,
                        I18n.get("campaign.destination.cleared", sector.name()), info.toJson());
                    return;
                }

                Sector destination = SectorService.resolve(packet.arg);
                if(destination == null){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.destination.invalid", packet.arg), "");
                    return;
                }

                try{
                    SectorService.setDestination(sector, destination);
                }catch(CampaignException e){
                    sendReply(con, packet.requestId, packet.action, false, e.getMessage(), "");
                    return;
                }
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, true,
                    I18n.get("campaign.destination.set", sector.name(), destination.name()), info.toJson());
                pushInfoToAll();
            }

            case CampaignProtocol.actionRedirect -> {
                if(!requirePermission(con, packet, player, true)) return;

                Sector sector = SectorService.current();
                if(sector == null){
                    sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.msg.not-playing"), "");
                    return;
                }

                int count = SectorService.redirectAllExports(sector);
                CampaignInfo info = CampaignInfo.build("");
                info.admin = player.admin;
                sendReply(con, packet.requestId, packet.action, true,
                    I18n.get("campaign.destination.redirected", count, sector.name()), info.toJson());
                pushInfoToAll();
            }

            default -> sendReply(con, packet.requestId, packet.action, false,
                I18n.get("campaign.api.unknown-action", CampaignProtocol.actionName(packet.action)), "");
        }
    }

    /** Pushes a fresh info snapshot to every handshaken client, e.g. after a sector change. */
    public static void pushInfoToAll(){
        if(!headless || capable.isEmpty()) return;

        String json;
        try{
            json = CampaignInfo.build("").toJson();
        }catch(Throwable t){
            Log.err("[CampaignServer] Could not build the campaign info snapshot.", t);
            return;
        }

        //snapshot: sending never mutates the set, but a disconnecting client may
        NetConnection[] connections = new NetConnection[capable.size];
        int index = 0;
        for(NetConnection con : capable){
            connections[index++] = con;
        }

        for(NetConnection con : connections){
            if(con == null || con.player == null) continue;
            try{
                con.send(buildPush(json), true);
            }catch(Throwable ignored){
                //connection may be closing
            }
        }
    }

    private static CampaignReplyPacket buildPush(String json){
        CampaignReplyPacket packet = new CampaignReplyPacket();
        packet.requestId = CampaignProtocol.pushId;
        packet.action = CampaignProtocol.actionInfo;
        packet.success = true;
        packet.json = json;
        return packet;
    }

    private static boolean requirePermission(NetConnection con, CampaignRequestPacket packet, Player player, boolean adminOnly){
        if(!adminOnly || player.admin) return true;

        sendReply(con, packet.requestId, packet.action, false, I18n.get("campaign.error.admin-only"), "");
        Log.warn("[CampaignServer] @ tried to use API action '@' without admin.", player.plainName(), CampaignProtocol.actionName(packet.action));
        return false;
    }

    private static boolean allowRequest(NetConnection con){
        long now = Time.millis();
        Long last = lastRequest.get(con);
        if(last != null && now - last < requestCooldown){
            return false;
        }
        lastRequest.put(con, now);
        return true;
    }

    private static void sendReply(NetConnection con, int requestId, byte action, boolean success, String message, String json){
        CampaignReplyPacket reply = new CampaignReplyPacket();
        reply.requestId = requestId;
        reply.action = action;
        reply.success = success;
        reply.message = message == null ? "" : message;
        reply.json = json == null ? "" : json;

        try{
            con.send(reply, true);
        }catch(Throwable t){
            Log.debug("[CampaignServer] Could not send a campaign API reply: @", Strings.getSimpleMessage(t));
        }
    }

    // ---------------------------------------------------------------- client side

    private static void onClientWorldLoad(){
        resetClient();
        Core.app.post(CampaignNetwork::requestHandshake);
    }

    private static void resetClient(){
        helloSent = false;
        helloReceived = false;
        serverProtocol = -1;
        serverAdmin = false;
        lastInfo = null;

        int[] ids = new int[pending.size];
        int i = 0;
        for(Integer id : pending.keys()){
            ids[i++] = id;
        }
        for(int id : ids){
            Pending entry = pending.remove(id);
            if(entry != null) fail(entry.callback, CampaignProtocol.actionInfo, I18n.get("campaign.api.connection-reset"));
        }
        pending.clear();
        purgeTimer = 0f;
    }

    /** Sends the handshake if the server advertises the API; safe to call repeatedly. */
    public static void requestHandshake(){
        if(headless || helloSent || !net.client() || !net.active() || state.isMenu() || !serverSupportsApi()){
            return;
        }

        helloSent = true;

        CampaignHelloPacket packet = new CampaignHelloPacket();
        packet.protocol = CampaignProtocol.version;
        packet.gameVersion = Version.buildString();
        net.send(packet, true);

        Log.info("[CampaignServer] Sent the campaign API handshake (protocol @).", CampaignProtocol.version);
    }

    public static void handleHelloAck(CampaignHelloAckPacket packet){
        helloReceived = true;
        serverProtocol = packet.protocol;
        serverAdmin = packet.admin;
        serverAllowsSwitch = packet.allowSwitch;
        serverAllowsResearch = packet.allowResearch;

        if(packet.protocol != CampaignProtocol.version){
            Log.warn("[CampaignServer] Campaign API version mismatch: server @, client @. Update both sides.", packet.protocol, CampaignProtocol.version);
        }else{
            Log.info("[CampaignServer] Campaign API ready (admin: @).", packet.admin);
        }
    }

    /**
     * Sends one API call.
     *
     * @return the request id, or -1 if the request could not be sent (the callback still fires with a failure).
     */
    public static int sendRequest(byte action, String arg, @Nullable Cons<CampaignReplyPacket> callback){
        if(headless || !net.client() || state.isMenu()){
            return fail(callback, action, I18n.get("campaign.api.not-connected"));
        }
        if(!serverSupportsApi()){
            return fail(callback, action, I18n.get("campaign.api.unsupported"));
        }

        requestHandshake();

        int id = nextRequestId++;
        if(id <= CampaignProtocol.pushId) id = nextRequestId++;

        if(callback != null){
            pending.put(id, new Pending(callback, Time.millis() + CampaignProtocol.requestTimeoutMillis));
        }

        CampaignRequestPacket packet = new CampaignRequestPacket();
        packet.requestId = id;
        packet.action = action;
        packet.arg = arg == null ? "" : arg;
        net.send(packet, true);

        return id;
    }

    public static void handleReply(CampaignReplyPacket packet){
        CampaignInfo info = CampaignInfo.fromJson(packet.json);
        if(info != null){
            lastInfo = info;
        }

        if(packet.requestId == CampaignProtocol.pushId){
            Cons<CampaignInfo> listener = pushListener;
            if(listener != null && info != null){
                Core.app.post(() -> listener.get(info));
            }
            return;
        }

        Pending entry = pending.remove(packet.requestId);
        if(entry != null){
            final CampaignReplyPacket result = packet;
            Core.app.post(() -> entry.callback.get(result));
        }
    }

    private static void updateClient(){
        if(pending.isEmpty()) return;

        purgeTimer += Time.delta;
        if(purgeTimer < 30f) return;
        purgeTimer = 0f;

        long now = Time.millis();
        IntSeq expired = new IntSeq();
        for(ObjectMap.Entry<Integer, Pending> entry : pending){
            if(entry.value.deadline < now){
                expired.add(entry.key);
            }
        }

        for(int i = 0; i < expired.size; i++){
            Pending entry = pending.remove(expired.get(i));
            if(entry != null) fail(entry.callback, CampaignProtocol.actionInfo, I18n.get("campaign.api.timeout"));
        }
    }

    private static int fail(@Nullable Cons<CampaignReplyPacket> callback, byte action, String message){
        if(callback != null){
            CampaignReplyPacket reply = new CampaignReplyPacket();
            reply.action = action;
            reply.success = false;
            reply.message = message;
            Core.app.post(() -> callback.get(reply));
        }
        return -1;
    }

    /** Outstanding request plus its deadline. */
    private static class Pending{
        final Cons<CampaignReplyPacket> callback;
        final long deadline;

        Pending(Cons<CampaignReplyPacket> callback, long deadline){
            this.callback = callback;
            this.deadline = deadline;
        }
    }
}
