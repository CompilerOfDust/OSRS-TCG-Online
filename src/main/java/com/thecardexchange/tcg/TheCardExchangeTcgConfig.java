package com.thecardexchange.tcg;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(TheCardExchangeTcgConfig.GROUP)
public interface TheCardExchangeTcgConfig extends Config
{
	String GROUP = "thecardexchangetcg";

	/** Baked-in fallback when nothing else supplies the API URL — the local dev backend. */
	String DEFAULT_API_BASE_URL = "http://localhost:3001";
	/** JVM system property that overrides the default (set by the launch script via {@code -PapiUrl=}). */
	String API_URL_PROPERTY = "thecardexchange.apiUrl";
	/** Environment variable that overrides the default (for production deployments / self-hosting). */
	String API_URL_ENV = "THECARDEXCHANGE_API_URL";

	/**
	 * The default API base URL, resolved from (in order) the {@link #API_URL_PROPERTY} system property,
	 * the {@link #API_URL_ENV} environment variable, then the baked-in local default. This is what lets a
	 * production deployment point the plugin at the hosted API without editing code — while a player who
	 * sets the config field below still overrides everything.
	 */
	static String defaultApiBaseUrl()
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
		return DEFAULT_API_BASE_URL;
	}

	/**
	 * Where the exchange API lives. Its default comes from {@link #defaultApiBaseUrl()} — the
	 * {@code THECARDEXCHANGE_API_URL} env var (or {@code -Dthecardexchange.apiUrl}), else the local dev
	 * backend — so deployments can set it without a rebuild. Setting this field explicitly overrides
	 * that. The plugin token, the pairing endpoints and the verification link are all built from it.
	 */
	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "The OSRS Card Exchange API this plugin talks to. Leave blank to use the "
			+ "THECARDEXCHANGE_API_URL environment variable (or the built-in default); set it to point at "
			+ "your own server.",
		position = 0
	)
	default String apiBaseUrl()
	{
		return defaultApiBaseUrl();
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
		keyName = "packSounds",
		name = "Pack sounds",
		description = "Play a sound when you open a pack and as each card turns over. The better the "
			+ "card's gem tier, the heavier the sound.",
		position = 6
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
		position = 7
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
		position = 8
	)
	default boolean lockUncollectedItems()
	{
		return true;
	}
}
