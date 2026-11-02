// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net;

import top.colorgarden.net.CampaignInfo;
import top.colorgarden.net.CampaignNetwork;
import top.colorgarden.net.CampaignProtocol;
import top.colorgarden.net.packet.CampaignReplyPacket;


import arc.func.Cons;
import arc.util.Nullable;

/**
 * Client-side entry point for the campaign API. Intended to be called from a client mod.
 *
 * <pre>{@code
 * if(CampaignClientApi.isServerSupported()){
 *     CampaignClientApi.switchSector("groundZero", result ->
 *         ui.showInfo(result.success ? result.message : "failed: " + result.message));
 *
 *     CampaignClientApi.requestInfo("", info -> {
 *         // info.sectorName, info.wave, info.sectors[] ...
 *     });
 * }
 * }</pre>
 *
 * <p>All callbacks run on the main thread. They always fire exactly once, including for locally
 * detected failures and timeouts, so a client UI never hangs. {@code info} arguments may be null
 * when the request failed.</p>
 *
 * <p>The player must be a server admin for the mutating calls (switch/research/save); the handshake
 * reports that through {@link #isAdmin()} so a UI can hide those buttons up front.</p>
 */
public class CampaignClientApi{
    /** @return whether the connected server advertises the campaign API (checked from the world rules tag). */
    public static boolean isServerSupported(){
        return CampaignNetwork.serverSupportsApi();
    }

    /** @return whether the handshake with the server finished. */
    public static boolean isReady(){
        return CampaignNetwork.clientReady();
    }

    /** @return whether this player is a server admin, as reported by the handshake. */
    public static boolean isAdmin(){
        return CampaignNetwork.isAdmin();
    }

    /** @return the protocol version the server speaks, or -1 before the handshake. */
    public static int serverProtocol(){
        return CampaignNetwork.serverProtocol();
    }

    /** @return the last info snapshot received, if any. */
    public static @Nullable CampaignInfo lastInfo(){
        return CampaignNetwork.lastInfo();
    }

    /** Sends the handshake now. Automated on world load; call it only if you disabled that. */
    public static void handshake(){
        CampaignNetwork.requestHandshake();
    }

    /** Registers a listener for unsolicited info pushes (sent after sector changes, captures, ...). */
    public static void setPushListener(@Nullable Cons<CampaignInfo> listener){
        CampaignNetwork.setPushListener(listener);
    }

    /**
     * Requests sector information.
     *
     * @param selector empty or {@code all} for the current sector plus the whole sector list,
     *                 otherwise a sector name/id as accepted by the server ({@code groundZero}, {@code 170}, {@code erekir:onset}).
     */
    public static int requestInfo(@Nullable String selector, @Nullable Cons<CampaignInfo> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionInfo, selector, reply ->
            deliverInfo(listener, reply));
    }

    /** Asks the server to switch the campaign sector. Admin only. */
    public static int switchSector(String sector, @Nullable Cons<Result> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionSwitch, sector, reply ->
            deliverResult(listener, CampaignProtocol.actionSwitch, reply));
    }

    /** Asks the server to unlock one research entry (including its parents). Admin only. */
    public static int research(String content, @Nullable Cons<Result> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionResearch, content, reply ->
            deliverResult(listener, CampaignProtocol.actionResearch, reply));
    }

    /** Asks the server to unlock every research entry. Admin only. */
    public static int researchAll(@Nullable Cons<Result> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionResearchAll, "", reply ->
            deliverResult(listener, CampaignProtocol.actionResearchAll, reply));
    }

    /** Points this sector's launch pads at another sector (same planet only). Admin only. */
    public static int setDestination(String sector, @Nullable Cons<Result> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionSetDestination, sector, reply ->
            deliverResult(listener, CampaignProtocol.actionSetDestination, reply));
    }

    /** Clears the launch pad link of this sector. Admin only. */
    public static int clearDestination(@Nullable Cons<Result> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionSetDestination, "clear", reply ->
            deliverResult(listener, CampaignProtocol.actionSetDestination, reply));
    }

    /** Points every sector that exports items at the current sector (the vanilla landing-pad button). Admin only. */
    public static int redirectExports(@Nullable Cons<Result> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionRedirect, "", reply ->
            deliverResult(listener, CampaignProtocol.actionRedirect, reply));
    }

    /** Asks the server to save the current sector. Admin only. */
    public static int save(@Nullable Cons<Result> listener){
        return CampaignNetwork.sendRequest(CampaignProtocol.actionSave, "", reply ->
            deliverResult(listener, CampaignProtocol.actionSave, reply));
    }

    private static void deliverInfo(@Nullable Cons<CampaignInfo> listener, CampaignReplyPacket reply){
        if(listener == null) return;
        CampaignInfo info = CampaignInfo.fromJson(reply.json);
        if(!reply.success){
            info = null;
        }
        final CampaignInfo result = info;
        listener.get(result);
    }

    private static void deliverResult(@Nullable Cons<Result> listener, byte action, CampaignReplyPacket reply){
        if(listener == null) return;
        listener.get(new Result(reply.success, reply.message, CampaignProtocol.actionName(action), CampaignInfo.fromJson(reply.json)));
    }

    /** Result of a mutating API call. */
    public static class Result{
        public final boolean success;
        public final String message;
        public final String action;
        /** Fresh info snapshot sent with the result, or null when the call failed. */
        public final @Nullable CampaignInfo info;

        public Result(boolean success, String message, String action, @Nullable CampaignInfo info){
            this.success = success;
            this.message = message == null ? "" : message;
            this.action = action;
            this.info = info;
        }

        @Override
        public String toString(){
            return "Result{" + action + ", success=" + success + ", message='" + message + "'}";
        }
    }
}
