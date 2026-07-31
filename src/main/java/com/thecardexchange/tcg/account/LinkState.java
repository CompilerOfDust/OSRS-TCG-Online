package com.thecardexchange.tcg.account;

/**
 * Where the plugin is in linking itself to an exchange account. Drives what the
 * side panel shows and what {@link AccountLinkManager} will do next.
 */
public enum LinkState
{
	/** No stored token — the player has not linked, or has unlinked. */
	NOT_LINKED,
	/** A handshake is being opened with the API (the brief window before a code exists). */
	STARTING,
	/** A code has been issued and shown; polling until the website confirms it. */
	AWAITING_CONFIRMATION,
	/** A valid plugin token is held and verified — the account is linked. */
	LINKED,
	/** The last link attempt or token check failed (network, expiry, refusal). */
	ERROR
}
