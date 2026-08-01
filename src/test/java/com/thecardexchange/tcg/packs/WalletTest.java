package com.thecardexchange.tcg.packs;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one piece of the readiness indicator worth testing on its own: <em>when</em> the pip lights up.
 * The drawing is a handful of {@code fillOval} calls and a colour lerp, which a test could only restate.
 */
public class WalletTest
{
	private static Holdings holdings(int credits, int packPrice)
	{
		return new Holdings(credits, 0, packPrice, Collections.emptyMap(),
			Collections.emptySet(), Collections.emptySet());
	}

	@Test
	public void startsUnknownAndOffersNothing()
	{
		Wallet wallet = new Wallet();
		assertFalse(wallet.isKnown());
		// Not "you're broke" — nobody has said yet, and a badge is a promise.
		assertFalse(wallet.canOpenPack());
	}

	@Test
	public void staysQuietJustShortOfAPack()
	{
		Wallet wallet = new Wallet();
		wallet.apply(holdings(999, 1000));
		assertFalse(wallet.canOpenPack());
	}

	@Test
	public void lightsUpAtExactlyThePrice()
	{
		Wallet wallet = new Wallet();
		wallet.apply(holdings(1000, 1000));
		assertTrue(wallet.canOpenPack());
	}

	@Test
	public void staysQuietWhenThePriceIsUnknown()
	{
		Wallet wallet = new Wallet();
		wallet.apply(holdings(50_000, 0));
		// Rich, but we have no idea what a pack costs — so no promise is made.
		assertFalse(wallet.canOpenPack());
	}

	@Test
	public void keepsTheLastKnownPriceWhenAResponseOmitsIt()
	{
		Wallet wallet = new Wallet();
		wallet.apply(holdings(5_000, 1000));
		// A heartbeat that carries a balance but no price must not make us forget the price.
		wallet.apply(2_000, 0);
		assertEquals(1000, wallet.getPackPrice());
		assertEquals(2_000, wallet.getCredits());
		assertTrue(wallet.canOpenPack());
	}

	@Test
	public void ignoresAnUnknownBalanceRatherThanWipingAGoodOne()
	{
		Wallet wallet = new Wallet();
		wallet.apply(holdings(5_000, 1000));
		// What an older server looks like: no credits field at all.
		wallet.apply(-1, 0);
		assertEquals(5_000, wallet.getCredits());
		assertTrue(wallet.canOpenPack());
	}

	@Test
	public void forgetsEverythingWhenTheCharacterGoesAway()
	{
		Wallet wallet = new Wallet();
		wallet.apply(holdings(5_000, 1000));

		wallet.clear();

		// Hopping to an alt must not leave the main's balance lit on the orb.
		assertFalse(wallet.isKnown());
		assertFalse(wallet.canOpenPack());
	}
}
