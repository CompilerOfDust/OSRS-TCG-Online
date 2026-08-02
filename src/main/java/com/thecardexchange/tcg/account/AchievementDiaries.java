package com.thecardexchange.tcg.account;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Varbits;

/**
 * Reading achievement diary completion out of the client.
 *
 * Twelve regions, four tiers each. The server pays credits per tier completed
 * and identifies each one by an integer — {@code region * TIERS + tier} — so
 * this class is really one thing: <b>the ordering</b>.
 *
 * <p><b>{@link #VARBITS} must never be reordered.</b> Its indices *are* the ids
 * stored in the server's {@code paidDiaries} ratchet. Shuffling it would silently
 * repoint every id already banked at a different diary, so a character would be
 * paid a second time for diaries it had and never for the ones the ids now mean.
 * It matches {@code DIARY_REGIONS} in {@code api/src/lib/osrs/diaries.ts}; the two
 * are one contract written twice, and they change together or not at all.
 * Append-only if Jagex ever adds a region.
 */
final class AchievementDiaries
{
	static final int TIERS = 4;

	/**
	 * Region-major, easiest tier first — RuneLite's own declaration order.
	 *
	 * @see #VARBITS the ordering warning above
	 */
	private static final int[] VARBITS = {
		Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE,
		Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE,
		Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE,
		Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE,
		Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE,
		Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE,
		Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE,
		Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE,
		Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE,
		Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE,
		Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE,
		Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE,
	};

	/** Index of the Karamja block in {@link #VARBITS} — see {@link #isComplete}. */
	private static final int KARAMJA_REGION = 5;

	private AchievementDiaries()
	{
	}

	static int count()
	{
		return VARBITS.length;
	}

	/**
	 * Diary ids this character has completed. <b>Client thread only.</b>
	 *
	 * <p>Returns an empty list before the varbits are populated, which is a normal
	 * state for the first tick or two after login and costs nothing: the server's
	 * ratchet only ever adds ids, so an empty claim pays nothing and is corrected
	 * on the next heartbeat.
	 */
	static List<Integer> completed(Client client)
	{
		List<Integer> ids = new ArrayList<>();
		for (int id = 0; id < VARBITS.length; id++)
		{
			if (isComplete(id, client.getVarbitValue(VARBITS[id])))
			{
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * Whether a diary varbit's value means "done".
	 *
	 * <p>Every region but one is a plain flag. <b>Karamja is not</b>: its varbits
	 * carry a third state for whether the rewards have been claimed, so the values
	 * run past 1 and the naive {@code == 1} test is wrong there.
	 *
	 * <p>Karamja therefore demands 2 rather than 1. That is the deliberately
	 * cautious reading of an ambiguity worth resolving against a real account:
	 * paying <i>late</i> costs nothing, because the ratchet pays whenever the
	 * value finally arrives, whereas paying <i>early</i> hands out credits for a
	 * diary that was never finished and cannot be taken back.
	 */
	private static boolean isComplete(int id, int value)
	{
		int required = (id / TIERS) == KARAMJA_REGION ? 2 : 1;
		return value >= required;
	}
}
