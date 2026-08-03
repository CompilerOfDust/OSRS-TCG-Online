package com.thecardexchange.tcg;

import javax.annotation.Nullable;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(TheCardExchangeTcgConfig.GROUP)
public interface TheCardExchangeTcgConfig extends Config
{
	String GROUP = "thecardexchangetcg";

	/**
	 * Where the API lives when nothing overrides it: one host per region, filled in with
	 * {@link ApiRegion#subdomain()} — {@code https://eu.osrscardexchange.com} and
	 * {@code https://us.osrscardexchange.com}.
	 *
	 * <p>There is no single baked-in URL any more, because there is no single API: the instance a client
	 * belongs on is decided by the OSRS world it is logged into, so that both players in a trade reach
	 * the same one. {@link ApiEndpoint} is what resolves it; the dev launch script sets
	 * {@link #API_URL_PROPERTY} to a local backend, which overrides all of this.
	 */
	String API_HOST_TEMPLATE = "https://%s.osrscardexchange.com";

	/**
	 * The public site — the local dev one.
	 *
	 * <p>Pages the plugin links to (the game-mode guide, the marketplace) live on the website, not on
	 * the api: they are different origins in production, so one base URL cannot serve both.
	 */
	String DEFAULT_WEB_APP_URL = "http://localhost:3000";
	/** JVM system property that overrides the web app default. */
	String WEB_URL_PROPERTY = "thecardexchange.webUrl";
	/** Environment variable that overrides the web app default. */
	String WEB_URL_ENV = "THECARDEXCHANGE_WEB_URL";

	/**
	 * The default web app URL, resolved the same way as the API one: system property, then environment
	 * variable, then the baked-in local default — so a deployment points the plugin at the hosted site
	 * without a rebuild, and a player setting the config field still overrides everything.
	 */
	static String defaultWebAppUrl()
	{
		String property = System.getProperty(WEB_URL_PROPERTY);
		if (property != null && !property.trim().isEmpty())
		{
			return property.trim();
		}
		String env = System.getenv(WEB_URL_ENV);
		if (env != null && !env.trim().isEmpty())
		{
			return env.trim();
		}
		return DEFAULT_WEB_APP_URL;
	}
	/** JVM system property that overrides the default (set by the launch script via {@code -PapiUrl=}). */
	String API_URL_PROPERTY = "thecardexchange.apiUrl";
	/** Environment variable that overrides the default (for production deployments / self-hosting). */
	String API_URL_ENV = "THECARDEXCHANGE_API_URL";

	/**
	 * An API base URL supplied from outside the client — the {@link #API_URL_PROPERTY} system property,
	 * then the {@link #API_URL_ENV} environment variable — or {@code null} when neither is set and the
	 * world's region should decide instead. This is what the dev launch script uses to point a client at
	 * a local backend without touching config.
	 */
	@Nullable
	static String apiBaseUrlOverride()
	{
		String property = System.getProperty(API_URL_PROPERTY);
		if (property != null && !property.trim().isEmpty())
		{
			return property.trim();
		}
		String env = System.getenv(API_URL_ENV);
		if (env != null && !env.trim().isEmpty())
		{
			return env.trim();
		}
		return null;
	}

	/**
	 * Where the exchange API lives, when you want to override it. **Blank is the normal setting**: the
	 * plugin then picks the instance matching the OSRS world you are on, which is what puts both players
	 * in a trade on the same server. Filling this in pins every request to one host — right for a local
	 * backend or a self-hosted copy, wrong for playing across regions.
	 *
	 * <p>Resolution lives in {@link ApiEndpoint}: this field, then the environment, then the region.
	 */
	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Leave blank — the plugin picks the server matching the world you are on, which is "
			+ "what lets you trade with the people around you. Only set this to point at your own copy of "
			+ "the API; it pins every request to that one host.",
		position = 0
	)
	default String apiBaseUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "chatNotifications",
		name = "Chat notifications",
		description = "Announce linking status in the chat box.",
		position = 1
	)
	default boolean chatNotifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "tradeCardsMenuOption",
		name = "\"Trade cards\" right-click",
		description = "Add a 'Trade cards' option beneath 'Trade with' when you right-click another player, "
			+ "which posts a card trade request in your Trade chat tab.",
		position = 2
	)
	default boolean tradeCardsMenuOption()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exchangeCardsMenuOption",
		name = "\"Exchange cards\" right-click",
		description = "Add an 'Exchange cards' option on the Grand Exchange Clerk, which opens the Card "
			+ "Exchange marketplace.",
		position = 3
	)
	default boolean exchangeCardsMenuOption()
	{
		return true;
	}

	@ConfigItem(
		keyName = "cardPacksOrb",
		name = "Card collection orb",
		description = "Show a Card Collection orb in the top-right corner. Clicking it opens your card "
			+ "collection over the inventory.",
		position = 4
	)
	default boolean cardPacksOrb()
	{
		return true;
	}

	@ConfigItem(
		keyName = "openPacksOrb",
		name = "Open packs orb",
		description = "Show an Open Packs orb in the top-left corner. Clicking it brings out a pack to "
			+ "open on screen.",
		position = 5
	)
	default boolean openPacksOrb()
	{
		return true;
	}

	@ConfigItem(
		keyName = "creditsBanner",
		name = "Credit balance",
		description = "Show your credit balance at the top of the screen. Credits are earned by playing "
			+ "- levels, quests, diaries, boss kills - so the balance moves while you are doing "
			+ "something else entirely.",
		position = 6
	)
	default boolean creditsBanner()
	{
		return true;
	}

	@ConfigItem(
		keyName = "packSounds",
		name = "Pack sounds",
		description = "Play a sound when you open a pack and as each card turns over. The better the "
			+ "card's gem tier, the heavier the sound.",
		position = 7
	)
	default boolean packSounds()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "packSoundVolume",
		name = "Pack sound volume",
		description = "How loud the pack sounds play, as a percentage.",
		position = 8
	)
	default int packSoundVolume()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "lockUncollectedItems",
		name = "Lock uncollected items",
		description = "Items you have no card for are greyed out and can't be worn, eaten or used. You can "
			+ "always pick them up, bank them, drop them, and skill freely.",
		position = 9
	)
	default boolean lockUncollectedItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "webAppUrl",
		name = "Web app URL",
		description = "Where the plugin sends you for pages on the site (game-mode guide, marketplace). "
			+ "Only change this if you are running your own copy.",
		position = 15
	)
	default String webAppUrl()
	{
		return defaultWebAppUrl();
	}

	@ConfigItem(
		keyName = "networkBadges",
		name = "Show network badges",
		description = "Show an icon beside other OSRS TCG Online players who are logged in. Only people "
			+ "running this plugin see it — it cannot change how anyone appears in the normal game.",
		position = 10
	)
	default boolean networkBadges()
	{
		return true;
	}

	@ConfigItem(
		keyName = "networkBadgeChat",
		name = "Badge: in chat",
		description = "Put the badge in front of member names in chat.",
		position = 11
	)
	default boolean networkBadgeChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "networkBadgeOverhead",
		name = "Badge: above heads",
		description = "Draw the badge above nearby members. Capped so a crowded bank stays readable.",
		position = 12
	)
	default boolean networkBadgeOverhead()
	{
		return true;
	}

	@ConfigItem(
		keyName = "networkBadgeMenu",
		name = "Badge: right-click menu",
		description = "Put the badge on member names in the right-click menu and hover text.",
		position = 13
	)
	default boolean networkBadgeMenu()
	{
		return true;
	}

	@ConfigItem(
		keyName = "networkShowMeOnline",
		name = "Show me as online",
		description = "Let other players see your badge while you are logged in. Turn this off and you "
			+ "disappear from the network list: no badge, and nobody can right-click you to offer a card "
			+ "trade. You can still start trades yourself, and you still see everyone else, rank on the "
			+ "boards and open packs as normal. Enforced on the server, so it holds whatever any client does.",
		position = 14
	)
	default boolean networkShowMeOnline()
	{
		return true;
	}
}
