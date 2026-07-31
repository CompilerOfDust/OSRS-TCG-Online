package com.thecardexchange.tcg.mode;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The mode enum's parsing, which has to survive whatever is in a config file —
 * a value hand-edited, left behind by an older build, or written by a newer one.
 */
public class GameModeTest
{
	@Test
	public void parsesTheModesItWrites()
	{
		for (GameMode mode : GameMode.values())
		{
			assertEquals(mode, GameMode.fromConfigValue(mode.name()));
		}
	}

	@Test
	public void toleratesCasingAndPadding()
	{
		assertEquals(GameMode.CARDMAN, GameMode.fromConfigValue("cardman"));
		assertEquals(GameMode.CARDMAN, GameMode.fromConfigValue("  CardMan  "));
		assertEquals(GameMode.NORMAL, GameMode.fromConfigValue("normal"));
	}

	@Test
	public void readsAnythingUnrecognisedAsNotSelected()
	{
		// Failing soft matters here: a bad string must never stop the plugin
		// starting, and "no mode" is the safe reading of "I can't tell".
		assertEquals(GameMode.NOT_SELECTED, GameMode.fromConfigValue(null));
		assertEquals(GameMode.NOT_SELECTED, GameMode.fromConfigValue(""));
		assertEquals(GameMode.NOT_SELECTED, GameMode.fromConfigValue("HARDCORE_CARDMAN"));
	}

	@Test
	public void onlyRealModesCountAsSelected()
	{
		assertFalse(GameMode.NOT_SELECTED.isSelected());
		assertTrue(GameMode.NORMAL.isSelected());
		assertTrue(GameMode.CARDMAN.isSelected());
	}

	@Test
	public void offersExactlyTheTwoPlayableModes()
	{
		assertArrayEquals(new GameMode[]{GameMode.NORMAL, GameMode.CARDMAN}, GameMode.selectable());
	}
}
