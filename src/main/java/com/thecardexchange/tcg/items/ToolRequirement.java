package com.thecardexchange.tcg.items;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Skilling actions that need a tool, and which cards count as that tool.
 *
 * <p>The lock is about items, but a gathering action <em>is</em> its tool: fishing a cage spot is using a
 * lobster pot, mining is using a pickaxe. Letting those through unchecked meant you could cage-fish
 * lobsters without ever having earned the pot, so an action listed here is blocked until you own a card
 * for something that could perform it.
 *
 * <p>Deliberately <b>not</b> listed: anything that takes no tool at all — thieving stalls, picking flax,
 * filling a bucket. Those need no input item, and the item they produce is not a requirement to start
 * (you can't earn a card for a thing you're not allowed to obtain).
 *
 * <p>Requirements are matched by <b>card name</b> against the catalogue rather than by hard-coded item
 * ids: the ids for "any axe" change every time Jagex adds one, the naming doesn't. Any single matching
 * card you own satisfies the requirement — a bronze axe is enough to chop.
 */
final class ToolRequirement
{
	/** What the player is clicking, and what they need to own to be allowed to. */
	private final String label;
	private final Predicate<String> optionMatches;
	@Nullable
	private final Predicate<String> targetMatches;
	private final Predicate<String> cardMatches;

	private ToolRequirement(String label, Predicate<String> optionMatches,
		@Nullable Predicate<String> targetMatches, Predicate<String> cardMatches)
	{
		this.label = label;
		this.optionMatches = optionMatches;
		this.targetMatches = targetMatches;
		this.cardMatches = cardMatches;
	}

	String getLabel()
	{
		return label;
	}

	Predicate<String> getCardMatches()
	{
		return cardMatches;
	}

	private static boolean isFishingSpot(String target)
	{
		return target.contains("fishing spot") || target.contains("rowboat");
	}

	private static boolean nameIs(String name, String... exact)
	{
		for (String candidate : exact)
		{
			if (name.equals(candidate))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The ladder of tool-gated actions. Fishing is the fiddly one: the spot's option names the gear, so
	 * "Cage" and "Harpoon" on the same spot want different cards.
	 */
	private static final List<ToolRequirement> ALL = Arrays.asList(
		new ToolRequirement("a lobster pot",
			option -> option.equals("cage"), ToolRequirement::isFishingSpot,
			name -> nameIs(name, "lobster pot")),
		new ToolRequirement("a harpoon",
			option -> option.equals("harpoon"), ToolRequirement::isFishingSpot,
			name -> name.endsWith("harpoon")),
		new ToolRequirement("a big fishing net",
			option -> option.equals("big net"), ToolRequirement::isFishingSpot,
			name -> nameIs(name, "big fishing net")),
		new ToolRequirement("a small fishing net",
			option -> option.equals("net") || option.equals("small net"), ToolRequirement::isFishingSpot,
			name -> nameIs(name, "small fishing net", "net")),
		new ToolRequirement("a fishing rod",
			option -> option.equals("bait"), ToolRequirement::isFishingSpot,
			name -> nameIs(name, "fishing rod", "oily fishing rod", "pearl fishing rod")),
		new ToolRequirement("a fly fishing rod",
			option -> option.equals("lure"), ToolRequirement::isFishingSpot,
			name -> nameIs(name, "fly fishing rod", "pearl fly fishing rod")),
		new ToolRequirement("a barbarian rod",
			option -> option.equals("use-rod") || option.equals("barbarian"), ToolRequirement::isFishingSpot,
			name -> nameIs(name, "barbarian rod", "pearl barbarian rod")),
		new ToolRequirement("a pickaxe",
			option -> option.equals("mine"), null,
			name -> name.endsWith("pickaxe")),
		new ToolRequirement("an axe",
			option -> option.equals("chop down") || option.equals("chop") || option.equals("cut down")
				|| option.equals("cut"), null,
			// "Battleaxe" and "pickaxe" both end in axe and neither fells a tree.
			name -> name.endsWith(" axe") && !name.contains("battleaxe") && !name.contains("pickaxe")),
		new ToolRequirement("a butterfly net",
			option -> option.equals("catch"), target -> target.contains("butterfly")
				|| target.contains("implings") || target.contains("impling"),
			name -> nameIs(name, "butterfly net", "magic butterfly net")));

	/** The requirement for this click, or null when the action needs no tool card. */
	@Nullable
	static ToolRequirement forAction(@Nullable String option, @Nullable String target)
	{
		if (option == null)
		{
			return null;
		}
		String plainOption = option.toLowerCase(Locale.ROOT).trim();
		String plainTarget = target == null ? "" : target.toLowerCase(Locale.ROOT).trim();

		for (ToolRequirement requirement : ALL)
		{
			if (!requirement.optionMatches.test(plainOption))
			{
				continue;
			}
			if (requirement.targetMatches != null && !requirement.targetMatches.test(plainTarget))
			{
				continue;
			}
			return requirement;
		}
		return null;
	}
}
