package com.thecardexchange.tcg.packs;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * One card in the catalogue, as {@code GET /api/v1/plugin/cards} sends it. Immutable: built off the wire
 * on a background thread, then read by the render thread.
 *
 * <p>{@link #gameId} is the OSRS item/NPC id — item cards are drawn from the client's own icons, which is
 * why NPC cards are the only ones the server bothers sending {@link #art} for.
 */
public final class CatalogueCard
{
	private final int id;
	/**
	 * The catalogue slug (`itm_abyssal-tentacle`) — the card's id on the website.
	 *
	 * <p>Sent by the api rather than derived here, because it cannot be derived: a duplicate name
	 * gets a numeric suffix (`npc_cook__4`), so slugifying the name would quietly link to the wrong
	 * card. Null only for a plugin talking to an api older than the field.
	 */
	@Nullable
	private final String cardId;
	private final String name;
	private final boolean npc;
	private final int gameId;
	@Nullable
	private final String art;
	@Nullable
	private final String pageSlug;
	/** The examine line — the card's description, shown on the parchment box of the card face. */
	@Nullable
	private final String description;
	private final int tier;
	private final Color tierColour;
	/** Curated special (trophy/master) card — gets distinct gold framing. */
	private final boolean special;
	/**
	 * Game ids this card unlocks "downwards" as a cluster master (the api's {@code unlocksItems}) —
	 * item ids, or NPC ids for an NPC card. Usually empty; the item locking never reads this (it runs
	 * on the server-computed unlocked set), it exists so the detail view can name the haul.
	 */
	private final List<Integer> unlocksItems;
	/** Crafting (CARDS.md §8): integer card ids of the components / the result. Usually empty. */
	private final List<Integer> craftedFrom;
	private final List<Integer> combinesInto;

	public CatalogueCard(int id, @Nullable String cardId, String name, boolean npc, int gameId,
		@Nullable String art, @Nullable String pageSlug, @Nullable String description, int tier,
		boolean special, List<Integer> unlocksItems, List<Integer> craftedFrom,
		List<Integer> combinesInto)
	{
		this.id = id;
		this.cardId = cardId;
		this.name = name;
		this.npc = npc;
		this.gameId = gameId;
		this.art = art;
		this.pageSlug = pageSlug;
		this.description = description;
		this.tier = Tiers.clamp(tier);
		this.tierColour = Tiers.colourOf(tier);
		this.special = special;
		this.unlocksItems = Collections.unmodifiableList(new ArrayList<>(unlocksItems));
		this.craftedFrom = Collections.unmodifiableList(new ArrayList<>(craftedFrom));
		this.combinesInto = Collections.unmodifiableList(new ArrayList<>(combinesInto));
	}

	public List<Integer> getUnlocksItems()
	{
		return unlocksItems;
	}

	public boolean isSpecial()
	{
		return special;
	}

	public List<Integer> getCraftedFrom()
	{
		return craftedFrom;
	}

	public List<Integer> getCombinesInto()
	{
		return combinesInto;
	}

	public int getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public boolean isNpc()
	{
		return npc;
	}

	/** The OSRS item/NPC id, or -1 when the card has none. */
	public int getGameId()
	{
		return gameId;
	}

	@Nullable
	public String getArt()
	{
		return art;
	}

	@Nullable
	public String getDescription()
	{
		return description;
	}

	/**
	 * The card's page on the Old School wiki. Built from the name — which is the page title for almost
	 * every card — unless the server sent a slug because this one is an exception.
	 */
	@Nullable
	public String getCardId()
	{
		return cardId;
	}

	/**
	 * The card's page on the Card Exchange website.
	 *
	 * <p>This is where the detail view's link goes now: our own page carries the card's tier, what it
	 * unlocks, its drop sources and spawn locations — the OSRS wiki has none of that, and the card is
	 * ours. The wiki is still one click away from there.
	 *
	 * <p>Null when the api did not send a slug (an older build), and the caller falls back to
	 * {@link #getWikiUrl()} rather than linking nowhere.
	 */
	@Nullable
	public String getSiteUrl(String webAppUrl)
	{
		if (cardId == null || cardId.isEmpty())
		{
			return null;
		}
		String base = webAppUrl.endsWith("/") ? webAppUrl.substring(0, webAppUrl.length() - 1) : webAppUrl;
		return base + "/tcg-online/cards/" + cardId;
	}

	/** The Old School wiki page for the real thing behind the card. */
	public String getWikiUrl()
	{
		String slug = pageSlug != null && !pageSlug.isEmpty() ? pageSlug : name.replace(' ', '_');
		return "https://oldschool.runescape.wiki/w/" + slug;
	}

	/** The gem tier, 1 (Opal) … 7 (Zenyte). */
	public int getTier()
	{
		return tier;
	}

	public Color getTierColour()
	{
		return tierColour;
	}

	/**
	 * The gem-tier ladder — the labels and colours cards are shown in.
	 *
	 * <p>Mirrors {@code GEM_TIERS} in the api's {@code src/lib/tcg/tier.ts}, which is the source of truth:
	 * a card's tier is derived there from the catalogue's own data and stored on {@code Card.tier}, and
	 * arrives here as a plain integer. <b>If that ladder's colours, names or length change, change this
	 * table with it</b> — it is the only place the plugin decides what a tier looks like.
	 */
	public static final class Tiers
	{
		private static final String[] LABELS = {
			"Opal", "Sapphire", "Emerald", "Ruby", "Diamond", "Onyx", "Zenyte",
		};
		private static final Color[] COLOURS = {
			new Color(0xBF, 0xB9, 0xAA), // 1 Opal
			new Color(0x2E, 0x6F, 0xE0), // 2 Sapphire
			new Color(0x1F, 0xA5, 0x5C), // 3 Emerald
			new Color(0xE0, 0x26, 0x3D), // 4 Ruby
			new Color(0x9A, 0xE6, 0xF0), // 5 Diamond
			new Color(0x5A, 0x5A, 0x66), // 6 Onyx
			new Color(0xF0, 0x8A, 0x1D), // 7 Zenyte
		};

		private Tiers()
		{
		}

		/** Keeps an unknown tier on the ladder rather than off the end of the table (as {@code asGemTier}). */
		public static int clamp(int tier)
		{
			return Math.max(1, Math.min(tier, LABELS.length));
		}

		/** The top rung of the ladder — Zenyte, today — for "is this a best-tier pull" checks. */
		public static int highest()
		{
			return LABELS.length;
		}

		public static Color colourOf(int tier)
		{
			return COLOURS[clamp(tier) - 1];
		}

		public static String label(int tier)
		{
			return LABELS[clamp(tier) - 1];
		}
	}
}
