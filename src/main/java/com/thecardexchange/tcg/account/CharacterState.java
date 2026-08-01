package com.thecardexchange.tcg.account;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.client.util.Text;
import com.thecardexchange.tcg.mode.GameMode;

/**
 * What the server says about the character we are logged in as.
 *
 * The plugin holds this and renders it; it never computes it. Eligibility, the
 * chosen mode and whether the character is held are all the server's answers —
 * a client that decided any of them for itself would be the easiest thing in the
 * feature to forge.
 */
@Value
public class CharacterState
{
	/** Nobody logged in, or nothing heard from the server yet. */
	public static final CharacterState UNKNOWN =
		new CharacterState("", false, GameMode.NOT_SELECTED, "NONE", null, -1, 0, 0, 0);

	String rsn;
	boolean bound;
	GameMode gameMode;
	/** NONE | FLAGGED | CLEARED | REJECTED, as the server spells it. */
	String review;
	/** The sentence to show while the character is held; null when it isn't. */
	@Nullable
	String reviewMessage;

	/**
	 * The wallet, carried on every character response.
	 *
	 * <p>This is what makes a credit reward visible in game. Nothing polls for a balance, so without it a
	 * grant would sit unseen until the player next opened the pack window — and the balance is arriving
	 * anyway, on a request the plugin already makes every five minutes and on every level-up.
	 *
	 * <p>{@code -1} means the server didn't say, which is what an older server looks like. Treated as
	 * unknown rather than as zero, so a version mismatch never reads as "you're broke".
	 */
	int credits;
	int packPrice;

	/** The welcome balance, set only on the response that actually paid it. Zero otherwise. */
	int grantedCredits;
	/** What this heartbeat's levels earned. Zero on a beat that earned nothing. */
	int creditsAwarded;

	static CharacterState of(JsonObject json)
	{
		if (json == null)
		{
			return UNKNOWN;
		}
		String rsn = json.has("rsn") && !json.get("rsn").isJsonNull()
			? Text.removeTags(json.get("rsn").getAsString()) : "";
		boolean bound = json.has("bound") && !json.get("bound").isJsonNull()
			&& json.get("bound").getAsBoolean();
		String mode = json.has("gameMode") && !json.get("gameMode").isJsonNull()
			? json.get("gameMode").getAsString() : null;
		String review = json.has("review") && !json.get("review").isJsonNull()
			? json.get("review").getAsString() : "NONE";
		String message = json.has("reviewMessage") && !json.get("reviewMessage").isJsonNull()
			? Text.removeTags(json.get("reviewMessage").getAsString()) : null;
		int credits = json.has("credits") && !json.get("credits").isJsonNull()
			? json.get("credits").getAsInt() : -1;
		int packPrice = json.has("packPrice") && !json.get("packPrice").isJsonNull()
			? json.get("packPrice").getAsInt() : 0;
		int granted = json.has("grantedCredits") && !json.get("grantedCredits").isJsonNull()
			? json.get("grantedCredits").getAsInt() : 0;
		int awarded = json.has("creditsAwarded") && !json.get("creditsAwarded").isJsonNull()
			? json.get("creditsAwarded").getAsInt() : 0;

		return new CharacterState(rsn, bound, GameMode.fromConfigValue(mode), review, message,
			credits, packPrice, granted, awarded);
	}

	/** True while the character is held and must not be played on. */
	public boolean isHeld()
	{
		return "FLAGGED".equals(review) || "REJECTED".equals(review);
	}

	/** True once the player has settled on a ruleset. */
	public boolean hasMode()
	{
		return gameMode.isSelected();
	}
}
