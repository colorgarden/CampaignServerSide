// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.util;




/** Thrown when a campaign operation cannot be performed; the message is safe to show to players. */
public class CampaignException extends RuntimeException{
    public CampaignException(String message){
        super(message);
    }

    public CampaignException(String message, Throwable cause){
        super(message, cause);
    }
}
