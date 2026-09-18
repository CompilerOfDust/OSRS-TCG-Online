package com.thecardexchange.tcg.packs;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The art request is built from a hardcoded host plus the file name the api sends — the Plugin Hub
 * needs every host visible in source, so a response must never be able to pick one. These pin that.
 */
public class CardArtTest
{
	@Test
	public void buildsTheUrlFromTheHardcodedHost()
	{
		assertEquals("https://oldschool.runescape.wiki/images/Baby_blue_dragon.png",
			CardArt.artUrl("Baby_blue_dragon.png"));
		assertEquals("https://oldschool.runescape.wiki/images/Ancient_feral_vyre_(1).png",
			CardArt.artUrl("Ancient_feral_vyre_(1).png"));
	}

	@Test
	public void noArtWhenThereIsNoName()
	{
		assertNull(CardArt.artUrl(null));
		assertNull(CardArt.artUrl(""));
	}

	@Test
	public void aNameThatCouldSteerTheRequestIsNotRequested()
	{
		assertNull(CardArt.artUrl("https://example.com/Thing.png"));
		assertNull(CardArt.artUrl("//example.com/Thing.png"));
		assertNull(CardArt.artUrl("thumb/Thing.png/120px-Thing.png"));
		assertNull(CardArt.artUrl("..\\Thing.png"));
		assertNull(CardArt.artUrl("sub\\Thing.png"));
		assertNull(CardArt.artUrl("../Thing.png"));
		assertNull(CardArt.artUrl("Thing.png?v=2"));
		assertNull(CardArt.artUrl("Thing.png#frag"));
	}
}
