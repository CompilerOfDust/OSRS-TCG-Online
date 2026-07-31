package com.thecardexchange.tcg.mode;

import javax.annotation.Nullable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The way a player has chosen to play the card game. A character starts on
 * {@link #NOT_SELECTED} — the plugin never picks for them — and the side panel
 * is where the choice is made. Once it is made it is final, the same way an
 * account type is: see {@link GameModeManager#select(GameMode)}.
 *
 * <p>The mode is recorded, not yet enforced: the plugin's features behave the
 * same in both. The rules below are what each mode <em>means</em>, and what the
 * game layer will hang off it.
 */
@Getter
@RequiredArgsConstructor
public enum GameMode
{
	/** No choice made yet. The panel shows the chooser; nothing is decided. */
	NOT_SELECTED(
		"Not selected",
		"You haven't chosen how you're playing yet."),

	/**
	 * The unrestricted mode: collect, open and trade cards alongside ordinary
	 * Old School RuneScape.
	 */
	NORMAL(
		"Normal",
		"Collect, open and trade cards alongside ordinary Old School RuneScape. "
			+ "Your account plays as it always has."),

	/**
	 * Cardcore rules — the community challenge the exchange's CardMan Mode
	 * highscores board tracks: an item is yours to use only once you have
	 * pulled its card, so the collection <em>is</em> the progression.
	 */
	CARDMAN(
		"CardMan",
		"Cardcore rules: an item is yours to use only once you've pulled its card. "
			+ "The collection is the progression.");

	/** What the player sees — the panel and any chat line use this, never {@link #name()}. */
	private final String displayName;

	/** One-paragraph explanation shown beside the choice. */
	private final String description;

	/** True for a real, playable mode — i.e. anything but {@link #NOT_SELECTED}. */
	public boolean isSelected()
	{
		return this != NOT_SELECTED;
	}

	/** The modes a player can actually choose between, in the order the panel offers them. */
	public static GameMode[] selectable()
	{
		return new GameMode[]{NORMAL, CARDMAN};
	}

	/**
	 * Parses a stored config value. Anything unrecognised — a blank, a value
	 * from a newer build, a hand-edited settings file — reads as
	 * {@link #NOT_SELECTED} rather than throwing, so a bad string can never stop
	 * the plugin from starting.
	 */
	public static GameMode fromConfigValue(@Nullable String value)
	{
		if (value == null)
		{
			return NOT_SELECTED;
		}
		String trimmed = value.trim();
		for (GameMode mode : values())
		{
			if (mode.name().equalsIgnoreCase(trimmed))
			{
				return mode;
			}
		}
		return NOT_SELECTED;
	}
}
