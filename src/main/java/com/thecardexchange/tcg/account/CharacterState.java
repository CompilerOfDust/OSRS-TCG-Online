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
		new CharacterState("", false, GameMode.NOT_SELECTED, "NONE", null);

	String rsn;
	boolean bound;
	GameMode gameMode;
	/** NONE | FLAGGED | CLEARED | REJECTED, as the server spells it. */
	String review;
	/** The sentence to show while the character is held; null when it isn't. */
	@Nullable
	String reviewMessage;

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

		return new CharacterState(rsn, bound, GameMode.fromConfigValue(mode), review, message);
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
