package com.thecardexchange.tcg;

import javax.annotation.Nullable;
import net.runelite.http.api.worlds.WorldRegion;

/**
 * Which deployment of the exchange API a given OSRS world talks to.
 *
 * <p><b>Why the world decides, and not the network.</b> The API runs as two instances, one in Europe
 * and one in the US, and the trade broker's offer state lives in the memory of whichever instance you
 * are connected to — it is not shared between them. So both players in a trade have to land on the same
 * instance or they cannot see each other. Anycast routing (nearest region wins) would not give us that;
 * two friends on the same world, one in Dublin and one in Dallas, would be routed apart.
 *
 * <p>The world does give us it, for free: trading in Old School requires standing next to each other,
 * which requires being on the same world. So a mapping from <em>world</em> to region puts both sides of
 * every possible trade on one machine, without a shared store, a message bus, or any coordination
 * between the two regions.
 *
 * <p><b>Therefore this mapping must stay a pure function of the world.</b> Not of the player, not of
 * their ping, not of anything measured locally — two clients on one world must always compute the same
 * answer, or the trade they are trying to make is the thing that breaks. That is also why the fallbacks
 * below resolve to a fixed region rather than to something sensible-but-local.
 *
 * <p>Which region a world goes to is a latency judgement and nothing more; it can be re-cut freely as
 * long as it stays deterministic. The current cut sends each of Jagex's world regions to whichever of
 * London and Virginia it is closer to.
 */
public enum ApiRegion
{
	EU("eu"),
	US("us");

	/** Deterministic answer when the world's region is known to RuneLite but not to us. */
	private static final ApiRegion FALLBACK = EU;

	private final String subdomain;

	ApiRegion(String subdomain)
	{
		this.subdomain = subdomain;
	}

	public String subdomain()
	{
		return subdomain;
	}

	/** The API base URL for this region — {@code https://eu.api.osrscardexchange.com} and friends. */
	public String baseUrl()
	{
		return String.format(TheCardExchangeTcgConfig.API_HOST_TEMPLATE, subdomain);
	}

	/**
	 * The API region for a world's Jagex region, or {@code null} when the world list has not told us
	 * which region the world is in yet. {@code null} means "ask again later" — see
	 * {@link ApiEndpoint#region()}, which holds the last known answer rather than guessing a new one.
	 *
	 * <p>A region RuneLite knows and we do not resolves to {@link #FALLBACK} rather than to null:
	 * every client that can see the world list sees the same unknown region, so a fixed answer keeps
	 * two players together, which matters more than which of the two they end up on.
	 */
	@Nullable
	public static ApiRegion forWorldRegion(@Nullable WorldRegion worldRegion)
	{
		if (worldRegion == null)
		{
			return null;
		}
		switch (worldRegion)
		{
			// Closer to Virginia than to London.
			case UNITED_STATES_OF_AMERICA:
			case BRAZIL:
			case AUSTRALIA:
			case JAPAN:
				return US;
			// Closer to London than to Virginia — including Singapore and South Africa, which route
			// west-about to Europe faster than they reach the US east coast.
			case UNITED_KINGDOM:
			case GERMANY:
			case SINGAPORE:
			case SOUTH_AFRICA:
				return EU;
			default:
				return FALLBACK;
		}
	}
}
