package com.thecardexchange.tcg;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The clashing-plugin matcher.
 *
 * <p>The case that actually matters is the false positive: without the own-package exclusion this
 * plugin could open a dialogue accusing the player of running a conflicting copy of the plugin they
 * are running. The old name, "OSRS TCG Online", contained the "osrs tcg" needle outright and is
 * still tested, because the exclusion has to hold whatever we are called — a rename is not a fix.
 * A missed match costs a warning nobody sees; a false one is a bug report.
 */
public class ClashingPluginsTest
{
	private static ClashingPlugins.Known known(String label)
	{
		for (ClashingPlugins.Known k : ClashingPlugins.known())
		{
			if (k.label.equals(label))
			{
				return k;
			}
		}
		throw new IllegalArgumentException("no such entry: " + label);
	}

	@Test
	public void doesNotMatchItself()
	{
		for (ClashingPlugins.Known k : ClashingPlugins.known())
		{
			assertFalse(
				"TCG Online must never report itself as a conflict (" + k.label + ")",
				ClashingPlugins.matches(k, "TCG Online (TheCardExchange)", "com.thecardexchange.tcg"));
			// The name we shipped under before, which did contain a needle: the package is the guard,
			// not the wording of the descriptor.
			assertFalse(
				"the exclusion must not depend on the display name (" + k.label + ")",
				ClashingPlugins.matches(k, "OSRS TCG Online", "com.thecardexchange.tcg"));
		}
	}

	@Test
	public void matchesTheOtherTcgPluginByNameOrPackage()
	{
		ClashingPlugins.Known k = known("OSRS TCG");
		assertTrue(ClashingPlugins.matches(k, "OSRS TCG", "com.osrstcg"));
		assertTrue(ClashingPlugins.matches(k, "Something Else", "com.azderi.osrstcg"));
		// Case and spacing in a third-party descriptor are not ours to rely on.
		assertTrue(ClashingPlugins.matches(k, "osrs tcg", "com.example"));
	}

	@Test
	public void matchesBronzemanVariants()
	{
		ClashingPlugins.Known k = known("Bronzeman TCG");
		assertTrue(ClashingPlugins.matches(k, "Bronzeman Mode", "com.example"));
		assertTrue(ClashingPlugins.matches(k, "Bronze Man Mode", "com.example"));
		assertTrue(ClashingPlugins.matches(k, "Whatever", "com.bronzeman.tcg"));
	}

	@Test
	public void leavesUnrelatedPluginsAlone()
	{
		for (ClashingPlugins.Known k : ClashingPlugins.known())
		{
			assertFalse(ClashingPlugins.matches(k, "Wise Old Man", "net.wiseoldman"));
			assertFalse(ClashingPlugins.matches(k, "Ground Items", "net.runelite.client.plugins.grounditems"));
			assertFalse(ClashingPlugins.matches(k, "Bank Tags", "net.runelite.client.plugins.banktags"));
		}
	}

	@Test
	public void toleratesAPluginWithNoDescriptor()
	{
		ClashingPlugins.Known k = known("OSRS TCG");
		assertFalse(ClashingPlugins.matches(k, null, "com.example"));
		assertFalse(ClashingPlugins.matches(k, null, null));
	}

	@Test
	public void readsNaturallyForOneConflictAndForTwo()
	{
		// The player reads this, so it has to agree with itself in number.
		assertTrue(ClashingPlugins.message(List.of("OSRS TCG")).startsWith("OSRS TCG is also running."));
		assertTrue(ClashingPlugins.message(List.of("OSRS TCG", "Bronzeman TCG"))
			.startsWith("OSRS TCG and Bronzeman TCG are also running."));
		assertTrue(ClashingPlugins.message(List.of("OSRS TCG")).endsWith("Turning it off is recommended."));
		assertTrue(ClashingPlugins.message(List.of("A", "B")).endsWith("Turning them off is recommended."));
	}

	@Test
	public void theTableIsNotAccidentallyEmpty()
	{
		assertEquals(2, ClashingPlugins.known().size());
	}
}
