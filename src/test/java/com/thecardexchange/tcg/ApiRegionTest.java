package com.thecardexchange.tcg;

import net.runelite.http.api.worlds.WorldRegion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The world-to-region mapping, which is load-bearing for trading rather than for latency: both players
 * in a trade are on one world, so a mapping that is a pure function of the world is what puts them on
 * one broker instance. A wrong region costs a few tens of milliseconds; a *non-deterministic* one costs
 * the trade.
 */
public class ApiRegionTest
{
	@Test
	public void mapsEveryJagexRegionToAnInstance()
	{
		// The property that matters: no world Jagex runs can leave a player with no answer, because an
		// unrouted world is a world nobody on it can trade from.
		for (WorldRegion region : WorldRegion.values())
		{
			assertNotNull("no API region for " + region, ApiRegion.forWorldRegion(region));
		}
	}

	@Test
	public void routesAmericasAndThePacificToTheUsInstance()
	{
		assertEquals(ApiRegion.US, ApiRegion.forWorldRegion(WorldRegion.UNITED_STATES_OF_AMERICA));
		assertEquals(ApiRegion.US, ApiRegion.forWorldRegion(WorldRegion.BRAZIL));
		assertEquals(ApiRegion.US, ApiRegion.forWorldRegion(WorldRegion.AUSTRALIA));
		assertEquals(ApiRegion.US, ApiRegion.forWorldRegion(WorldRegion.JAPAN));
	}

	@Test
	public void routesEuropeAfricaAndSouthEastAsiaToTheEuInstance()
	{
		assertEquals(ApiRegion.EU, ApiRegion.forWorldRegion(WorldRegion.UNITED_KINGDOM));
		assertEquals(ApiRegion.EU, ApiRegion.forWorldRegion(WorldRegion.GERMANY));
		assertEquals(ApiRegion.EU, ApiRegion.forWorldRegion(WorldRegion.SOUTH_AFRICA));
		assertEquals(ApiRegion.EU, ApiRegion.forWorldRegion(WorldRegion.SINGAPORE));
	}

	@Test
	public void isDeterministic()
	{
		// Two clients on one world must compute the same answer or the trade between them cannot open.
		for (WorldRegion region : WorldRegion.values())
		{
			assertEquals(ApiRegion.forWorldRegion(region), ApiRegion.forWorldRegion(region));
		}
	}

	@Test
	public void anUnknownWorldIsNotGuessedAt()
	{
		// Null means "the world list has not said yet", which ApiEndpoint answers by keeping the region
		// it already had — deliberately not by picking one, which would move a connected player.
		assertNull(ApiRegion.forWorldRegion(null));
	}

	@Test
	public void buildsThePerRegionHost()
	{
		assertEquals("https://eu.osrscardexchange.com", ApiRegion.EU.baseUrl());
		assertEquals("https://us.osrscardexchange.com", ApiRegion.US.baseUrl());
		// No trailing slash: callers append a path straight onto it.
		assertTrue(ApiRegion.US.baseUrl().endsWith(".com"));
	}
}
