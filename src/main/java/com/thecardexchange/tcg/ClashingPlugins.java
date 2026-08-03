package com.thecardexchange.tcg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

/**
 * Detects other installed plugins that do the same job as this one.
 *
 * <p>OSRS TCG Online is a single unified plugin: it owns the collection, the item lock, pack opening
 * and trading together, because they are one ruleset rather than four features. A second plugin that
 * also locks items or tracks a collection does not add to that — the two fight over the same menu
 * entries and the same idea of what the player has unlocked, and the result reads as this plugin
 * being broken.
 *
 * <p><b>This warns, and does nothing else.</b> No disabling, no refusing to start. Which plugins a
 * player runs is theirs to decide, and a plugin that switches off its neighbours is worse than the
 * clash it was avoiding.
 *
 * <p><b>The identifiers below are best-effort and expected to need tuning.</b> A plugin is matched on
 * its {@link PluginDescriptor} name or its package, both case-insensitive substrings, because the
 * exact strings a third-party plugin ships are not something this repo can verify — they change with
 * its releases and are not part of any contract. A missed match costs a warning nobody sees; a
 * false match would accuse an innocent plugin, which is why {@link #OWN_PACKAGE} is excluded
 * explicitly rather than relying on the name test. **Our own name contains "OSRS TCG"**, so without
 * that exclusion this plugin would report itself.
 */
@Slf4j
@Singleton
public class ClashingPlugins
{
	/** Ours. Excluded first, because "OSRS TCG Online" contains "OSRS TCG". */
	private static final String OWN_PACKAGE = "com.thecardexchange";

	/** A plugin we know covers the same ground. */
	static final class Known
	{
		final String label;
		final List<String> needles;

		Known(String label, String... needles)
		{
			this.label = label;
			this.needles = Arrays.asList(needles);
		}
	}

	private static final List<Known> KNOWN = List.of(
		// github.com/Azderi/osrs-tcg — the other OSRS card-game plugin.
		new Known("OSRS TCG", "osrs tcg", "osrstcg", "azderi"),
		// Bronzeman-style collection/unlock plugins: same item-lock idea, different ruleset.
		new Known("Bronzeman TCG", "bronzeman", "bronze man")
	);

	private final PluginManager pluginManager;

	@Inject
	ClashingPlugins(PluginManager pluginManager)
	{
		this.pluginManager = pluginManager;
	}

	/**
	 * The display names of clashing plugins that are installed <b>and enabled</b>, in the order they
	 * are listed above. Empty when there is nothing to say — the caller must not open a dialogue for
	 * an empty list.
	 *
	 * <p>Enabled matters: a player who installed one of these and turned it off has already resolved
	 * the clash, and telling them again is noise.
	 */
	public List<String> enabledClashes()
	{
		final List<String> found = new ArrayList<>();
		for (Known known : KNOWN)
		{
			if (isEnabled(known))
			{
				found.add(known.label);
			}
		}
		return found;
	}

	private boolean isEnabled(Known known)
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			final Class<?> type = plugin.getClass();
			final PluginDescriptor descriptor = type.getAnnotation(PluginDescriptor.class);
			final String name = descriptor == null ? "" : descriptor.name();
			if (!matches(known, name, type.getPackageName()))
			{
				continue;
			}
			// A disabled plugin is not a clash — the player already dealt with it.
			if (pluginManager.isPluginEnabled(plugin))
			{
				log.debug("Clashing plugin enabled: {} ({})", name, type.getPackageName());
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a plugin's descriptor name or package identifies it as {@code known}.
	 *
	 * <p>Package-private and static so the matching can be tested without a {@link PluginManager} —
	 * and the case worth testing is the exclusion: <b>our own name, "OSRS TCG Online", contains the
	 * "osrs tcg" needle</b>, so this plugin would report itself as its own conflict if the package
	 * check were dropped.
	 */
	static boolean matches(Known known, String descriptorName, String packageName)
	{
		final String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
		if (pkg.startsWith(OWN_PACKAGE))
		{
			return false;
		}
		final String haystack =
			(descriptorName == null ? "" : descriptorName.toLowerCase(Locale.ROOT)) + " " + pkg;
		for (String needle : known.needles)
		{
			if (haystack.contains(needle))
			{
				return true;
			}
		}
		return false;
	}

	/** The table this matches against, for tests. */
	static List<Known> known()
	{
		return KNOWN;
	}

	/** The warning text for a non-empty result from {@link #enabledClashes()}. */
	public static String message(List<String> clashes)
	{
		final boolean one = clashes.size() == 1;
		final String names = one ? clashes.get(0) : String.join(" and ", clashes);
		return names + (one ? " is" : " are") + " also running. OSRS TCG Online already does all of "
			+ "that in one plugin — running them together makes them fight over item locks and menu "
			+ "options, which usually looks like this plugin misbehaving. Turning "
			+ (one ? "it" : "them") + " off is recommended.";
	}
}
