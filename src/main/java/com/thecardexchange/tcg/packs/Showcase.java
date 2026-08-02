package com.thecardexchange.tcg.packs;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Cards that are a bigger deal than their gem tier says.
 *
 * <p>Rarity and <em>value</em> are not the same question, and the pack ceremony only ever answered the
 * first one. Coins is the case that shows the gap: it is an Emerald and it is common, so it arrives with
 * no more ceremony than a bucket — yet it is the card that unlocks spending money at all, which makes it
 * one of the best pulls in the game. This list is where that judgement lives.
 *
 * <p><b>Deliberately not the {@code special} flag, and deliberately not a tier bump.</b> Both would have
 * been easier and both would lie. `special` is a curated register with its own index
 * (`api/cards/special_item_unlocks.md`) and its own gold framing; borrowing it would make the register
 * wrong and make a showcase card indistinguishable from a trophy. Raising the tier would change the pull
 * odds and the resale price for a card that is supposed to stay common. So a showcase card keeps its
 * tier, keeps {@code special: false}, and borrows only the two things that are pure presentation: the
 * halo and the fanfare.
 *
 * <p>Matched by <b>name</b> rather than by card id: the id is a catalogue detail that a reseed can move,
 * whereas the name is the thing the judgement was actually made about.
 */
final class Showcase
{
	/**
	 * The halo colour — bright coin gold, distinct from the antique
	 * {@link CardPacksInterface#SPECIAL_GOLD} a curated special wears, so the two read as different
	 * kinds of good news rather than as the same one.
	 */
	static final Color GLOW = new Color(255, 216, 102);

	/** Lowercased card names. Keep this short: everything on it is loud, and loud does not scale. */
	private static final Set<String> NAMES = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList("coins")));

	private Showcase()
	{
	}

	static boolean is(@Nullable CatalogueCard card)
	{
		return card != null && card.getName() != null
			&& NAMES.contains(card.getName().toLowerCase(Locale.ROOT).trim());
	}
}
