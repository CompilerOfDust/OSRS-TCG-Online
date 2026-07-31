package com.thecardexchange.tcg.account;

import lombok.Value;

/**
 * The open handshake returned by {@code /link/start}: the human-typed {@code code}
 * the player enters on the website, the opaque {@code deviceSecret} the plugin
 * polls with, and where to send the player to confirm.
 */
@Value
public class LinkHandshake
{
	String code;
	String deviceSecret;
	/** Page the player opens to sign in and confirm. */
	String verificationUri;
	/** Same page with the code pre-filled — the one the "Open link page" button uses. */
	String verificationUriComplete;
	/** Seconds the API asks the plugin to wait between polls. */
	int intervalSeconds;
}
