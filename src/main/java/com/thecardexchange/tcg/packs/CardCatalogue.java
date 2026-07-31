package com.thecardexchange.tcg.packs;

import java.util.List;
import java.util.Set;

/**
 * The catalogue as {@code GET /api/v1/plugin/cards} sends it: every card, and every game id the
 * catalogue covers.
 *
 * <p>The id set is separate from the cards because it answers a different question — not "which card is
 * this" but "is this item collectable at all", which is what stops the item locking from locking things
 * no card could ever unlock.
 */
public final class CardCatalogue
{
	private final List<CatalogueCard> cards;
	private final Set<Integer> collectable;
	private final Set<Integer> collectableNpcs;

	public CardCatalogue(List<CatalogueCard> cards, Set<Integer> collectable, Set<Integer> collectableNpcs)
	{
		this.cards = cards;
		this.collectable = collectable;
		this.collectableNpcs = collectableNpcs;
	}

	public List<CatalogueCard> getCards()
	{
		return cards;
	}

	public Set<Integer> getCollectable()
	{
		return collectable;
	}

	/** NPC ids the catalogue covers — kept apart from item ids, which are a different id space. */
	public Set<Integer> getCollectableNpcs()
	{
		return collectableNpcs;
	}
}
