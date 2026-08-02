package com.thecardexchange.tcg.packs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the expanded card view's sort and filter actually do.
 *
 * <p>Pure list logic, deliberately kept free of {@code Client} so it can be tested at all — the drawing
 * around it is plates and text and a test could only restate it. The case that matters most is the last
 * one: the trade picker's duplicates-only rule is a <em>correctness</em> constraint, not a preference,
 * and generalising the filter is exactly the change that could quietly drop it.
 */
public class CardGridTest
{
	/** Sale prices as the server sends them, so VALUE ordering is checked against real numbers. */
	private static final IntUnaryOperator SALE = tier ->
	{
		switch (tier)
		{
			case 7: return 1000;
			case 6: return 300;
			case 3: return 150;
			default: return 37;
		}
	};

	private static CatalogueCard card(int id, String name, int tier)
	{
		return new CatalogueCard(id, name, false, 1000 + id, null, null, null, tier, false,
			Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
	}

	private static Map<Integer, Integer> held(int... pairs)
	{
		Map<Integer, Integer> out = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2)
		{
			out.put(pairs[i], pairs[i + 1]);
		}
		return out;
	}

	private static List<Integer> order(List<CatalogueCard> cards)
	{
		List<Integer> ids = new ArrayList<>();
		for (CatalogueCard card : cards)
		{
			ids.add(card.getId());
		}
		return ids;
	}

	private static List<CatalogueCard> sorted(CardPacksInterface.SortMode mode,
		Map<Integer, Integer> owned, CatalogueCard... cards)
	{
		List<CatalogueCard> list = new ArrayList<>(Arrays.asList(cards));
		list.sort(CardPacksInterface.comparator(mode, owned, SALE));
		return list;
	}

	// ── Filters ───────────────────────────────────────────────────────────────

	@Test
	public void allShowsEverything()
	{
		for (int quantity : new int[]{0, 1, 5})
		{
			assertTrue(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.ALL, quantity));
		}
	}

	@Test
	public void collectedIsAnythingHeld()
	{
		assertFalse(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.COLLECTED, 0));
		assertTrue(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.COLLECTED, 1));
		assertTrue(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.COLLECTED, 9));
	}

	@Test
	public void duplicatesNeedsASpare()
	{
		// One copy is not a duplicate: it is the copy that can never be sold or traded.
		assertFalse(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.DUPLICATES, 1));
		assertTrue(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.DUPLICATES, 2));
	}

	@Test
	public void missingIsNoneAtAll()
	{
		assertTrue(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.MISSING, 0));
		assertFalse(CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.MISSING, 1));
	}

	// ── Sorting ───────────────────────────────────────────────────────────────

	@Test
	public void collectedFirstPutsOwnedCardsUpFrontBestGemFirst()
	{
		Map<Integer, Integer> owned = held(1, 1, 3, 1);
		List<CatalogueCard> result = sorted(CardPacksInterface.SortMode.COLLECTED_FIRST, owned,
			card(1, "Opal thing", 1), card(2, "Zenyte thing", 7), card(3, "Onyx thing", 6));

		// 3 and 1 are owned (Onyx before Opal); 2 is not, however good it is.
		assertEquals(Arrays.asList(3, 1, 2), order(result));
	}

	@Test
	public void duplicatesSortsBySpareCountNotTotal()
	{
		Map<Integer, Integer> owned = held(1, 9, 2, 1, 3, 3);
		List<CatalogueCard> result = sorted(CardPacksInterface.SortMode.DUPLICATES, owned,
			card(1, "Eight spare", 1), card(2, "No spare", 7), card(3, "Two spare", 1));

		assertEquals(Arrays.asList(1, 3, 2), order(result));
	}

	@Test
	public void valueBeatsCountWhenTheSparesAreWorthMore()
	{
		// Twenty spare Opals (19 x 37 = 703) against one spare Zenyte (1,000). The
		// count order and the value order disagree, which is the whole reason both exist.
		Map<Integer, Integer> owned = held(1, 20, 2, 2);
		CatalogueCard opal = card(1, "Opal thing", 1);
		CatalogueCard zenyte = card(2, "Zenyte thing", 7);

		assertEquals(Arrays.asList(1, 2), order(sorted(CardPacksInterface.SortMode.DUPLICATES, owned, opal, zenyte)));
		assertEquals(Arrays.asList(2, 1), order(sorted(CardPacksInterface.SortMode.VALUE, owned, opal, zenyte)));
	}

	@Test
	public void nameIsCaseInsensitive()
	{
		List<CatalogueCard> result = sorted(CardPacksInterface.SortMode.NAME, Collections.emptyMap(),
			card(1, "zamorak brew", 3), card(2, "Abyssal whip", 6), card(3, "Bandos chestplate", 6));

		assertEquals(Arrays.asList(2, 3, 1), order(result));
	}

	@Test
	public void everySortIsTotal()
	{
		// Cards a mode cannot tell apart must still have a fixed order, or the grid
		// reshuffles between frames.
		Map<Integer, Integer> owned = held(1, 2, 2, 2, 3, 2);
		for (CardPacksInterface.SortMode mode : CardPacksInterface.SortMode.values())
		{
			List<CatalogueCard> a = sorted(mode, owned,
				card(1, "Same", 3), card(2, "Same", 3), card(3, "Same", 3));
			List<CatalogueCard> b = sorted(mode, owned,
				card(3, "Same", 3), card(2, "Same", 3), card(1, "Same", 3));
			assertEquals("tie-break for " + mode, order(a), order(b));
		}
	}

	// ── The rule that outranks the player's choice ────────────────────────────

	@Test
	public void tradePickerKeepsItsDuplicatesOnlyRuleWhateverTheFilterSays()
	{
		// `applyFilter` ANDs the picker's constraint with the user's filter. Modelled
		// here the way the loop does it, because the failure this guards against is
		// somebody "simplifying" the two conditions into one and letting FilterMode.ALL
		// put a single-copy card in front of a trade — a card the player would be
		// giving away whole.
		Map<Integer, Integer> owned = held(1, 1, 2, 3);
		boolean duplicatesOnly = true;

		List<Integer> offered = new ArrayList<>();
		for (CatalogueCard card : Arrays.asList(card(1, "Last copy", 3), card(2, "Has spares", 3)))
		{
			int quantity = owned.getOrDefault(card.getId(), 0);
			if (duplicatesOnly && quantity < 2)
			{
				continue;
			}
			if (!CardPacksInterface.matchesFilter(CardPacksInterface.FilterMode.ALL, quantity))
			{
				continue;
			}
			offered.add(card.getId());
		}

		assertEquals(Collections.singletonList(2), offered);
	}
}
