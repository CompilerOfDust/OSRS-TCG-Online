package com.thecardexchange.tcg.items;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import com.thecardexchange.tcg.packs.CatalogueCard;

/**
 * Which items this character has earned the right to use.
 *
 * <p>The rule is the collection's: an item is <b>unlocked</b> when you hold a card that <i>is</i> that
 * item, or a master card that unlocks it as part of a family. The server works that set out from the
 * catalogue (see the api's {@code playerHoldings}) and sends the finished game-id list; this class holds
 * it and answers one question — {@link #isLocked(int)}.
 *
 * <p>Two deliberate rules live here:
 *
 * <ul>
 *   <li><b>Ids are canonicalised first.</b> A noted stack, a bank placeholder and the item itself are
 *       three different ids for one thing, and a lock that only knew one of them would be trivially
 *       sidestepped by noting the item.</li>
 *   <li><b>An item no card covers is not locked.</b> The catalogue can't mint a card for it, so locking
 *       it would put the item permanently out of reach rather than behind a pack.</li>
 * </ul>
 *
 * <p>Written from the collection fetch (a background thread) and read by the render and client threads,
 * so the set is swapped wholesale rather than mutated.
 */
@Singleton
public class ItemLocks
{
	/** The base coins id — owning a card that covers it is what opens shop buying (see ItemLockManager). */
	public static final int COINS_ID = 995;

	private final ItemManager itemManager;

	/** Game ids the collection unlocks. Empty until the first fetch lands. */
	private volatile Set<Integer> unlocked = Collections.emptySet();
	/** Every game id any card covers — what "this item is even collectable" means. */
	private volatile Set<Integer> collectable = Collections.emptySet();
	/** The same two questions in the NPC id space, which shares no numbering with items. */
	private volatile Set<Integer> unlockedNpcs = Collections.emptySet();
	private volatile Set<Integer> collectableNpcs = Collections.emptySet();
	/** Item cards as (lowercased name → game id), for name-based tool requirements. */
	private volatile List<Map.Entry<String, Integer>> itemNames = Collections.emptyList();
	/** Memoised {@link #ownsItemNamed} answers, dropped whenever the unlocked set moves. */
	private final Map<String, Boolean> toolCache = new ConcurrentHashMap<>();
	private volatile boolean loaded;

	@Inject
	ItemLocks(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/** The character's unlocked game ids, straight from {@code GET /api/v1/plugin/collection}. */
	public void setUnlocked(Set<Integer> ids, Set<Integer> npcIds)
	{
		this.unlocked = Collections.unmodifiableSet(new HashSet<>(ids));
		this.unlockedNpcs = Collections.unmodifiableSet(new HashSet<>(npcIds));
		this.loaded = true;
		toolCache.clear();
	}

	/**
	 * The catalogue's item cards as (lowercased name → game id), so a tool requirement can be answered by
	 * name — "do I own anything called a pickaxe" — instead of by a hard-coded id list.
	 */
	public void setItemNames(List<CatalogueCard> cards)
	{
		List<Map.Entry<String, Integer>> named = new ArrayList<>(cards.size());
		for (CatalogueCard card : cards)
		{
			if (!card.isNpc() && card.getGameId() > 0)
			{
				named.add(new AbstractMap.SimpleImmutableEntry<>(
					card.getName().toLowerCase(Locale.ROOT), card.getGameId()));
			}
		}
		this.itemNames = Collections.unmodifiableList(named);
		toolCache.clear();
	}

	/**
	 * True when the player owns at least one card whose name satisfies {@code nameMatches} — "any axe",
	 * "a lobster pot". Answers are cached under {@code key} until the unlocked set changes, because this
	 * runs off menu events and the catalogue is ten thousand rows.
	 */
	public boolean ownsItemNamed(String key, Predicate<String> nameMatches)
	{
		Boolean cached = toolCache.get(key);
		if (cached != null)
		{
			return cached;
		}
		boolean owned = false;
		for (Map.Entry<String, Integer> entry : itemNames)
		{
			if (unlocked.contains(entry.getValue()) && nameMatches.test(entry.getKey()))
			{
				owned = true;
				break;
			}
		}
		toolCache.put(key, owned);
		return owned;
	}

	/** True once the catalogue's names are in hand — tool requirements can't be judged before that. */
	public boolean hasItemNames()
	{
		return !itemNames.isEmpty();
	}

	/**
	 * Every game id the catalogue knows about. Anything outside this has no card to earn, so it is never
	 * locked — see the class note.
	 */
	public void setCollectable(Set<Integer> ids, Set<Integer> npcIds)
	{
		this.collectable = Collections.unmodifiableSet(new HashSet<>(ids));
		this.collectableNpcs = Collections.unmodifiableSet(new HashSet<>(npcIds));
	}

	/** False until the collection has been fetched at least once — nothing is locked before then. */
	public boolean isLoaded()
	{
		return loaded;
	}

	/** Clears everything, so a logout can't leave the next character wearing someone else's locks. */
	public void clear()
	{
		unlocked = Collections.emptySet();
		unlockedNpcs = Collections.emptySet();
		toolCache.clear();
		loaded = false;
	}

	/**
	 * True when this NPC exists as a card the character hasn't earned yet. Same rule as items, in the NPC
	 * id space: a monster no card covers is never locked.
	 */
	public boolean isNpcLocked(int npcId)
	{
		if (!loaded || npcId < 0 || unlockedNpcs.contains(npcId))
		{
			return false;
		}
		return !collectableNpcs.isEmpty() && collectableNpcs.contains(npcId);
	}

	/** True when this item exists as a card the character hasn't earned yet. */
	public boolean isLocked(int itemId)
	{
		if (!loaded || itemId <= 0)
		{
			return false;
		}
		int canonical = canonical(itemId);
		if (unlocked.contains(canonical) || unlocked.contains(itemId))
		{
			return false;
		}
		// Nothing outside the catalogue is lockable, and an empty catalogue locks nothing at all.
		return !collectable.isEmpty() && (collectable.contains(canonical) || collectable.contains(itemId));
	}

	/** Notes, placeholders and the item itself are one thing as far as a lock is concerned. */
	private int canonical(int itemId)
	{
		try
		{
			return itemManager.canonicalize(itemId);
		}
		catch (RuntimeException ex)
		{
			// Called off the client thread in places; an unknown id is just itself.
			return itemId;
		}
	}
}
