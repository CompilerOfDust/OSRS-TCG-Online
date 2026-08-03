package com.thecardexchange.tcg;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.WorldService;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;

/**
 * The one answer to "which API do we talk to right now".
 *
 * <p>Resolution order, most specific first:
 *
 * <ol>
 *   <li>the <b>config field</b>, when a player or a self-hoster has filled it in;</li>
 *   <li>the <b>{@code thecardexchange.apiUrl} system property / {@code THECARDEXCHANGE_API_URL} env
 *       var</b>, which is how the dev launch script points a client at a local backend;</li>
 *   <li>otherwise the <b>region of the world we are logged into</b> — see {@link ApiRegion}.</li>
 * </ol>
 *
 * <p>An override wins outright and is used verbatim: a local backend has no regions, and second-guessing
 * an address somebody typed is how you end up unable to point the plugin anywhere.
 *
 * <p>{@link #region()} is deliberately <b>sticky</b>. The world list arrives asynchronously, so early in
 * a session {@code findWorld} returns nothing; treating that as "region unknown, pick a default" would
 * move a connected player between regions as the list loaded. Holding the last resolved answer instead
 * means the region only ever changes when the world genuinely did.
 */
@Singleton
public class ApiEndpoint
{
	private final TheCardExchangeTcgConfig config;
	private final WorldService worldService;

	/** Written on the client thread, read from OkHttp and scheduler threads. */
	private volatile int world;

	/** The last region we could actually resolve. Never null, so callers always have an endpoint. */
	private volatile ApiRegion lastResolved = ApiRegion.EU;

	@Inject
	ApiEndpoint(TheCardExchangeTcgConfig config, WorldService worldService)
	{
		this.config = config;
		this.worldService = worldService;
	}

	/** Client thread: the world the local player is on. Safe to call every tick. */
	public void setWorld(int world)
	{
		this.world = world;
	}

	/**
	 * The region the current world maps to. Falls back to the last known answer while the world list is
	 * still loading — see the class note on why that is not a guess.
	 */
	public ApiRegion region()
	{
		WorldResult worlds = worldService.getWorlds();
		World current = worlds == null ? null : worlds.findWorld(world);
		ApiRegion resolved = current == null ? null : ApiRegion.forWorldRegion(current.getRegion());
		if (resolved != null)
		{
			lastResolved = resolved;
		}
		return lastResolved;
	}

	/**
	 * The explicitly configured base URL, or null when there is none and the region should decide.
	 * Trailing slashes are stripped so callers can append a path unconditionally.
	 */
	@Nullable
	public String override()
	{
		String configured = config.apiBaseUrl();
		if (configured != null && !configured.trim().isEmpty())
		{
			return trimTrailingSlash(configured.trim());
		}
		String external = TheCardExchangeTcgConfig.apiBaseUrlOverride();
		return external == null ? null : trimTrailingSlash(external);
	}

	/** The base URL every request and the trade socket should be built from, without a trailing slash. */
	public String baseUrl()
	{
		String override = override();
		return override != null ? override : region().baseUrl();
	}

	/**
	 * The region the socket is actually pointed at, or null when an override means the region is not in
	 * play at all. {@link com.thecardexchange.tcg.trade.CardTradeManager} compares this across ticks to
	 * decide whether a world hop has moved us to the other instance; under an override it must stay null
	 * so a dev client never reconnects on a world change it does not care about.
	 */
	@Nullable
	public ApiRegion routedRegion()
	{
		return override() != null ? null : region();
	}

	private static String trimTrailingSlash(String url)
	{
		return url.replaceAll("/+$", "");
	}
}
