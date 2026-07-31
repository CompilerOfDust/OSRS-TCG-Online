package com.thecardexchange.tcg.packs;

import java.util.Map;
import java.util.Set;

/** What {@code GET /api/v1/plugin/collection} says the character has: the wallet, the cards, the unlocks. */
public final class Holdings
{
	private final int credits;
	private final int openedPacks;
	private final int packPrice;
	/** Card id → copies held. Only owned cards appear; everything else is greyed out in the grid. */
	private final Map<Integer, Integer> owned;
	/** Game item ids these cards unlock — what the item locking runs on. */
	private final Set<Integer> unlocked;
	/** NPC ids these cards unlock. Its own space: id 1 is a bronze axe *and* a chicken. */
	private final Set<Integer> unlockedNpcs;

	public Holdings(int credits, int openedPacks, int packPrice, Map<Integer, Integer> owned,
		Set<Integer> unlocked, Set<Integer> unlockedNpcs)
	{
		this.unlockedNpcs = unlockedNpcs;
		this.credits = credits;
		this.openedPacks = openedPacks;
		this.packPrice = packPrice;
		this.owned = owned;
		this.unlocked = unlocked;
	}

	public int getCredits()
	{
		return credits;
	}

	public int getOpenedPacks()
	{
		return openedPacks;
	}

	public int getPackPrice()
	{
		return packPrice;
	}

	public Map<Integer, Integer> getOwned()
	{
		return owned;
	}

	public Set<Integer> getUnlocked()
	{
		return unlocked;
	}

	public Set<Integer> getUnlockedNpcs()
	{
		return unlockedNpcs;
	}
}
