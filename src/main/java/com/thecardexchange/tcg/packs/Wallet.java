package com.thecardexchange.tcg.packs;

import javax.inject.Singleton;

/**
 * The character's credit balance, in one place.
 *
 * <p>Before this existed the number lived in two private fields in two windows, and both were filled in
 * only when their window opened. That was fine while the only thing that changed a balance was the player
 * spending it — they were looking at the window at the time. It stopped being fine once the <em>server</em>
 * grants credits: a reward earned by levelling up would not have been visible until the next time somebody
 * opened the pack interface, which is exactly when it no longer matters.
 *
 * <p>So the balance is held here instead, written by everything that learns one — the collection fetch, the
 * pack response, the item-lock refresh, and (the important one) <b>every character heartbeat</b>, which the
 * plugin already sends every five minutes and within a minute of any level-up. The orb and both windows read
 * it, so they cannot disagree about what the player can afford.
 *
 * <p>Nothing here decides anything. The server owns the economy; this is a cache of its last word, and
 * {@link #canOpenPack()} only exists so the answer is phrased once instead of three times.
 *
 * <p>Every field is {@code volatile} because the writers are on the scheduler and the readers are on the
 * render thread. There is no invariant spanning two fields, so no lock is needed: the worst a torn read can
 * produce is one frame of a stale pip.
 */
@Singleton
public class Wallet
{
	/** Nobody has told us yet. Deliberately not 0 — "unknown" and "broke" are different states. */
	public static final int UNKNOWN = -1;

	private volatile int credits = UNKNOWN;
	private volatile int packPrice = 0;

	public void apply(Holdings holdings)
	{
		if (holdings == null)
		{
			return;
		}
		set(holdings.getCredits(), holdings.getPackPrice());
	}

	/**
	 * The balance the server reported on a character response (bind, heartbeat, logout).
	 *
	 * <p>An older server sends neither field, in which case {@link CharacterState} passes the unknown
	 * sentinels through and this leaves whatever we already had alone — a stale balance beats wiping a
	 * correct one because the other end is out of date.
	 */
	public void apply(int credits, int packPrice)
	{
		if (credits < 0)
		{
			return;
		}
		set(credits, packPrice);
	}

	private void set(int credits, int packPrice)
	{
		this.credits = credits;
		// A zero price means the server didn't say. Keep the last real one rather than forgetting how
		// much a pack costs the moment one response omits it.
		if (packPrice > 0)
		{
			this.packPrice = packPrice;
		}
	}

	/**
	 * Forgets the balance. Called when the tracked character goes away — hopping to an alt must not leave
	 * the main's wallet on screen, which would be the same class of bug the character binding exists to
	 * prevent: one character's holdings shown against another.
	 */
	public void clear()
	{
		credits = UNKNOWN;
		packPrice = 0;
	}

	public boolean isKnown()
	{
		return credits >= 0;
	}

	public int getCredits()
	{
		return credits;
	}

	public int getPackPrice()
	{
		return packPrice;
	}

	/**
	 * Whether the player can afford a pack right now.
	 *
	 * <p><b>Unknown reads as "no", which is the opposite of what the pack window does</b>, and both are
	 * right. The window is asking "should I disable this button?", where refusing a click that would have
	 * worked is worse than letting it through. This is asking "should I put a badge on the orb telling
	 * them to come and open one?", where a promise the click might break is worse than staying quiet — the
	 * player was going to click the orb anyway, and the pack screen explains itself.
	 *
	 * <p>Do not unify the two.
	 */
	public boolean canOpenPack()
	{
		return credits >= 0 && packPrice > 0 && credits >= packPrice;
	}
}
