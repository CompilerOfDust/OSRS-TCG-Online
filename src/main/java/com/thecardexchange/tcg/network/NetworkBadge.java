package com.thecardexchange.tcg.network;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import com.thecardexchange.tcg.mode.GameMode;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.client.util.ImageUtil;

/**
 * The network badge sprite, and its slot in the game's icon table.
 *
 * <p>Chat names cannot carry an arbitrary image — the game renders {@code <img=N>} against its own
 * mod-icon array, so an icon has to be appended to that array first and referred to by index. This is
 * the same mechanism the clan-rank and ironman icons use, which is why the badge looks native rather
 * than pasted on top.
 *
 * <p>The array only exists once the game has loaded, and appending to it touches client state, so
 * {@link #install(Client)} must run on the client thread and does nothing until the icons are there.
 */
@Slf4j
@Singleton
public class NetworkBadge
{
	/** Chat-line height. Bigger reflows the line; smaller is unreadable at 1x. */
	private static final int CHAT_ICON_SIZE = 13;

	/** The overhead badge is read at a glance from further away, so it gets a little more room. */
	private static final int OVERHEAD_ICON_SIZE = 16;

	/**
	 * The two faces of the badge: the same card, coloured by ruleset.
	 *
	 * <p>CardMan and Normal cannot trade with each other, so which one somebody is on is worth
	 * answering on sight rather than after an offer is refused. Same shape and same green "online"
	 * dot, so they read as one badge in two colours rather than two unrelated icons.
	 */
	private static final String NORMAL_ICON = "/com/thecardexchange/tcg/panel_icon.png";
	private static final String CARDMAN_ICON = "/com/thecardexchange/tcg/panel_icon_cardman.png";

	/** Slot in {@link Client#getModIcons()} per variant, or -1 until installed. */
	private int normalIndex = -1;
	private int cardmanIndex = -1;

	private BufferedImage normalOverhead;
	private BufferedImage cardmanOverhead;

	@Inject
	NetworkBadge()
	{
	}

	/** The {@code <img=N>} tag for a name on this ruleset, or an empty string if not installed. */
	public String tag(GameMode mode)
	{
		int index = mode == GameMode.CARDMAN ? cardmanIndex : normalIndex;
		return index < 0 ? "" : "<img=" + index + ">";
	}

	/**
	 * Any badge tag, for spotting a name this plugin has already decorated.
	 *
	 * <p>A player can change ruleset between one chat line and the next, so matching only the tag for
	 * their *current* mode would decorate an already-decorated name twice.
	 */
	public boolean isDecorated(String text)
	{
		return (normalIndex >= 0 && text.contains("<img=" + normalIndex + ">"))
			|| (cardmanIndex >= 0 && text.contains("<img=" + cardmanIndex + ">"));
	}

	/** The mark sized for drawing over a player's head, for this ruleset. */
	public BufferedImage overheadImage(GameMode mode)
	{
		return mode == GameMode.CARDMAN ? cardmanOverhead : normalOverhead;
	}

	public boolean isInstalled()
	{
		return normalIndex >= 0 && cardmanIndex >= 0;
	}

	/**
	 * Appends the badge to the game's icon table. Idempotent, and safe to call on every login: the
	 * array survives a hop, so re-registering would leak a slot per world change.
	 *
	 * <p>Must be called on the client thread.
	 */
	public void install(Client client)
	{
		if (isInstalled())
		{
			return;
		}

		final IndexedSprite[] modIcons = client.getModIcons();
		if (modIcons == null)
		{
			// The table is built as the game loads; the caller retries on the next login state change.
			return;
		}

		try
		{
			BufferedImage normal = ImageUtil.loadImageResource(getClass(), NORMAL_ICON);
			BufferedImage cardman = ImageUtil.loadImageResource(getClass(), CARDMAN_ICON);
			if (normal == null || cardman == null)
			{
				return;
			}

			// Both appended in one pass, so a half-installed pair can never leave
			// one ruleset with a badge and the other without.
			IndexedSprite[] expanded = Arrays.copyOf(modIcons, modIcons.length + 2);
			expanded[modIcons.length] = ImageUtil.getImageIndexedSprite(
				ImageUtil.resizeImage(normal, CHAT_ICON_SIZE, CHAT_ICON_SIZE), client);
			expanded[modIcons.length + 1] = ImageUtil.getImageIndexedSprite(
				ImageUtil.resizeImage(cardman, CHAT_ICON_SIZE, CHAT_ICON_SIZE), client);
			client.setModIcons(expanded);

			normalIndex = modIcons.length;
			cardmanIndex = modIcons.length + 1;
			normalOverhead = ImageUtil.resizeImage(normal, OVERHEAD_ICON_SIZE, OVERHEAD_ICON_SIZE);
			cardmanOverhead = ImageUtil.resizeImage(cardman, OVERHEAD_ICON_SIZE, OVERHEAD_ICON_SIZE);
		}
		catch (RuntimeException ex)
		{
			// A badge that cannot be registered is a cosmetic loss, never a reason
			// to break a login.
			log.debug("could not install the network badge icons", ex);
		}
	}
}
