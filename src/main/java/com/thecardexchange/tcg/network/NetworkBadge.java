package com.thecardexchange.tcg.network;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
	 * The icon's slot in {@link Client#getModIcons()}, or -1 until installed.
	 *
	 * <p>Read from the chat and menu decorators to build the {@code <img=N>} tag.
	 */
	@Getter
	private int iconIndex = -1;

	/** The same mark, sized for drawing over a player's head. */
	@Getter
	private BufferedImage overheadImage;

	private BufferedImage chatImage;

	@Inject
	NetworkBadge()
	{
	}

	/** The {@code <img=N>} tag for a chat or menu name, or an empty string if not installed. */
	public String tag()
	{
		return iconIndex < 0 ? "" : "<img=" + iconIndex + ">";
	}

	public boolean isInstalled()
	{
		return iconIndex >= 0;
	}

	/**
	 * Appends the badge to the game's icon table. Idempotent, and safe to call on every login: the
	 * array survives a hop, so re-registering would leak a slot per world change.
	 *
	 * <p>Must be called on the client thread.
	 */
	public void install(Client client)
	{
		if (iconIndex >= 0)
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
			BufferedImage image = chatImage();
			if (image == null)
			{
				return;
			}

			IndexedSprite[] expanded = Arrays.copyOf(modIcons, modIcons.length + 1);
			expanded[modIcons.length] = ImageUtil.getImageIndexedSprite(image, client);
			client.setModIcons(expanded);
			iconIndex = modIcons.length;
		}
		catch (RuntimeException ex)
		{
			// A badge that cannot be registered is a cosmetic loss, never a reason
			// to break a login.
			log.debug("could not install the network badge icon", ex);
		}
	}

	private BufferedImage chatImage()
	{
		if (chatImage == null)
		{
			BufferedImage source =
				ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/panel_icon.png");
			if (source == null)
			{
				return null;
			}
			chatImage = ImageUtil.resizeImage(source, CHAT_ICON_SIZE, CHAT_ICON_SIZE);
			overheadImage = ImageUtil.resizeImage(source, OVERHEAD_ICON_SIZE, OVERHEAD_ICON_SIZE);
		}
		return chatImage;
	}
}
