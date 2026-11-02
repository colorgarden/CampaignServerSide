// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.net;


/** Wire constants shared by the server plugin and the client mod. */
public class CampaignProtocol{
    /** Protocol version. Bump whenever a packet layout or a JSON field changes. */
    public static final int version = 1;

    /**
     * Rules tag the server writes into {@code state.rules.tags} while a campaign is hosted.
     * Clients can read it from the world data they receive, which makes the API discoverable
     * without sending an unknown packet to a server that would not understand it.
     */
    public static final String tagKey = "campaign-api";

    /** Request id reserved for unsolicited server pushes. Client requests must never use it. */
    public static final int pushId = 0;

    public static final long requestTimeoutMillis = 20000L;

    //request actions
    public static final byte actionInfo = 0;
    public static final byte actionSwitch = 1;
    public static final byte actionResearch = 2;
    public static final byte actionResearchAll = 3;
    public static final byte actionSave = 4;
    public static final byte actionVoteSector = 5;
    public static final byte actionVoteResearch = 6;
    public static final byte actionVoteCast = 7;
    public static final byte actionVoteCancel = 8;

    public static String actionName(byte action){
        return switch(action){
            case actionInfo -> "info";
            case actionSwitch -> "switch";
            case actionResearch -> "research";
            case actionResearchAll -> "researchAll";
            case actionSave -> "save";
            case actionVoteSector -> "voteSector";
            case actionVoteResearch -> "voteResearch";
            case actionVoteCast -> "voteCast";
            case actionVoteCancel -> "voteCancel";
            default -> "unknown(" + action + ")";
        };
    }
}
