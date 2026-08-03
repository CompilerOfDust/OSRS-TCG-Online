package com.thecardexchange.tcg;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
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
@Slf4j
@Singleton
public class ApiEndpoint
{
	/**
	 * A stored config value that points at a local backend. RuneLite persists config **defaults**, so
	 * every client that ran a build from before the default became the live deployment has
	 * `http://localhost:3001` written into its profile — and {@link #override()} reads the config field
	 * ahead of the system property, so that stale value silently beats `-Dthecardexchange.apiUrl` and
	 * every launcher flag. It presents as "the plugin ignores the URL I gave it", which is a miserable
	 * thing to debug.
	 */
	private static final String[] STALE_LOCAL_PREFIXES = {
		"http://localhost", "http://127.0.0.1", "https://localhost", "https://127.0.0.1",
	};

	private final TheCardExchangeTcgConfig config;
	private final WorldService worldService;
	private final ConfigManager configManager;

	/** Written on the client thread, read from OkHttp and scheduler threads. */
	private volatile int world;

	/** The last region we could actually resolve. Never null, so callers always have an endpoint. */
	private volatile ApiRegion lastResolved = ApiRegion.EU;

	@Inject
	ApiEndpoint(TheCardExchangeTcgConfig config, WorldService worldService, ConfigManager configManager)
	{
		this.config = config;
		this.worldService = worldService;
		this.configManager = configManager;
	}

	/**
	 * One-shot migration: clears a stored config URL that points at localhost.
	 *
	 * <p>Only a *localhost* value is cleared, and only from config — a self-hoster who typed their own
	 * host keeps it, because guessing that somebody's deliberate setting is stale would be the worse
	 * error. Nothing is written back, so a player who genuinely wants localhost can simply type it
	 * again and it survives (this runs once per start, and their value is no longer a localhost
	 * *default* they never chose).
	 */
	public void clearStaleLocalOverrides()
	{
		clearIfLocal("apiBaseUrl", config.apiBaseUrl());
		clearIfLocal("webAppUrl", config.webAppUrl());
	}

	private void clearIfLocal(String key, String value)
	{
		if (value == null || value.trim().isEmpty())
		{
			return;
		}
		final String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
		for (String prefix : STALE_LOCAL_PREFIXES)
		{
			if (trimmed.startsWith(prefix))
			{
				log.info("Clearing stale local {} ({}) — it was a persisted default, and it overrides "
					+ "every command-line and environment setting.", key, value.trim());
				configManager.unsetConfiguration(TheCardExchangeTcgConfig.GROUP, key);
				return;
			}
		}
	}

	/** What every request will actually go to, and why — logged once at start-up. */
	public String describe()
	{
		final String configured = config.apiBaseUrl();
		if (configured != null && !configured.trim().isEmpty())
		{
			return "api=" + trimTrailingSlash(configured.trim()) + " (from the plugin's config field)";
		}
		final String external = TheCardExchangeTcgConfig.apiBaseUrlOverride();
		if (external != null)
		{
			return "api=" + trimTrailingSlash(external)
				+ " (from -D" + TheCardExchangeTcgConfig.API_URL_PROPERTY
				+ " / " + TheCardExchangeTcgConfig.API_URL_ENV + "; region routing OFF)";
		}
		return "api=" + region().baseUrl() + " (region " + region() + ", from the OSRS world)";
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
