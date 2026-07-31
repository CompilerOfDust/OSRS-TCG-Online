package com.thecardexchange.tcg.packs;

import java.util.List;

/** What came out of a pack, and what the wallet looks like afterwards. */
public final class PackResult
{
	private final List<PulledCard> cards;
	private final int credits;
	private final int openedPacks;

	public PackResult(List<PulledCard> cards, int credits, int openedPacks)
	{
		this.cards = cards;
		this.credits = credits;
		this.openedPacks = openedPacks;
	}

	public List<PulledCard> getCards()
	{
		return cards;
	}

	public int getCredits()
	{
		return credits;
	}

	public int getOpenedPacks()
	{
		return openedPacks;
	}

	/**
	 * A card as it was pulled: the catalogue entry plus what the pull did to the collection.
	 *
	 * <p>There is no foil flag: the gem-tier pack model doesn't mint them. If foils return, they come back
	 * as a field on the pack response and a badge in the reveal.
	 */
	public static final class PulledCard
	{
		private final CatalogueCard card;
		private final int quantity;
		private final boolean fresh;

		public PulledCard(CatalogueCard card, int quantity, boolean fresh)
		{
			this.card = card;
			this.quantity = quantity;
			this.fresh = fresh;
		}

		public CatalogueCard getCard()
		{
			return card;
		}

		public int getQuantity()
		{
			return quantity;
		}

		/** First copy the player has ever held. */
		public boolean isFresh()
		{
			return fresh;
		}
	}
}
