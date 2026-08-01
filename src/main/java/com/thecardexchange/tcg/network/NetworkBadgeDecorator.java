package com.thecardexchange.tcg.network;

import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.util.Text;

/**
 * Puts the network badge on names in chat and in the right-click menu.
 *
 * <p>Both work the same way the game's own clan and ironman icons do: an {@code <img=N>} tag in front
 * of the name, resolved against the mod-icon table {@link NetworkBadge} appended to. That is why it
 * reads as part of the interface rather than an overlay drawn on top of it.
 *
 * <p>Only decoration. Nothing here changes what a menu entry does, and a name is only ever prefixed —
 * never rewritten — so anything matching on names elsewhere still sees the name it expects after
 * {@link Text#removeTags}.
 */
@Singleton
public class NetworkBadgeDecorator
{
	private final Client client;
	private final NetworkPresence presence;
	private final NetworkBadge badge;
	private final TheCardExchangeTcgConfig config;

	@Inject
	NetworkBadgeDecorator(
		Client client,
		NetworkPresence presence,
		NetworkBadge badge,
		TheCardExchangeTcgConfig config)
	{
		this.client = client;
		this.presence = presence;
		this.badge = badge;
		this.config = config;
	}

	private boolean enabled()
	{
		return config.networkBadges() && badge.isInstalled();
	}

	/**
	 * Prefixes the sender's name in a chat line.
	 *
	 * <p>Returns the decorated name, or null when nothing should change — the caller writes it back
	 * onto the message node and asks the chat manager to re-render, which is the only way to change a
	 * line that has already been built.
	 */
	public String decorateChatName(ChatMessage message)
	{
		if (!enabled() || !config.networkBadgeChat())
		{
			return null;
		}

		String name = message.getName();
		if (name == null || name.isEmpty())
		{
			return null;
		}

		// Already decorated — chat lines can be rebuilt more than once.
		if (name.contains(badge.tag()))
		{
			return null;
		}
		if (!presence.isOnline(Text.removeTags(name)))
		{
			return null;
		}

		return badge.tag() + name;
	}

	/** Prefixes a player's name on the right-click menu and its hover text. */
	public void decorateMenuEntry(MenuEntryAdded event)
	{
		if (!enabled() || !config.networkBadgeMenu())
		{
			return;
		}

		MenuEntry entry = event.getMenuEntry();
		if (entry == null)
		{
			return;
		}

		// Player entries only. An NPC or an object never carries a player name, and
		// decorating one would put the badge on something that is not a member.
		final MenuAction type = entry.getType();
		if (!isPlayerEntry(type))
		{
			return;
		}

		String target = entry.getTarget();
		if (target == null || target.isEmpty() || target.contains(badge.tag()))
		{
			return;
		}

		// The target carries colour tags and a combat-level suffix, so the name is
		// what is left once both are stripped.
		String name = Text.removeTags(target);
		int levelMarker = name.indexOf('(');
		if (levelMarker > 0)
		{
			name = name.substring(0, levelMarker);
		}
		if (!presence.isOnline(name))
		{
			return;
		}

		entry.setTarget(badge.tag() + target);
	}

	private static boolean isPlayerEntry(MenuAction type)
	{
		switch (type)
		{
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			case RUNELITE_PLAYER:
				return true;
			default:
				return false;
		}
	}
}
