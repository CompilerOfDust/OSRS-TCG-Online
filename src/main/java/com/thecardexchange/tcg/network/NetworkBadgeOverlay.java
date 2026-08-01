package com.thecardexchange.tcg.network;

import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the network badge above the heads of Card Exchange players who are online.
 *
 * <p>Nothing about who is nearby leaves this machine: {@link NetworkPresence} holds the online list,
 * downloaded whole, and the comparison happens here.
 *
 * <p>Bounded on purpose. A crowded bank can hold hundreds of players, and drawing an icon for each of
 * them every frame is both a performance cost and an unreadable mess, so the overlay stops after
 * {@link #MAX_BADGES}. The nearest players are the ones a badge is useful for anyway.
 */
@Singleton
public class NetworkBadgeOverlay extends Overlay
{
	/** How many overhead badges may be drawn in one frame. */
	private static final int MAX_BADGES = 30;

	/** Clear of the name and any overhead prayer/skull icons. */
	private static final int Y_OFFSET = 24;

	private final Client client;
	private final NetworkPresence presence;
	private final NetworkBadge badge;
	private final TheCardExchangeTcgConfig config;

	@Inject
	NetworkBadgeOverlay(
		Client client,
		NetworkPresence presence,
		NetworkBadge badge,
		TheCardExchangeTcgConfig config)
	{
		this.client = client;
		this.presence = presence;
		this.badge = badge;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(Overlay.PRIORITY_LOW);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.networkBadges()
			|| !config.networkBadgeOverhead()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		BufferedImage image = badge.getOverheadImage();
		if (image == null)
		{
			return null;
		}

		int drawn = 0;
		for (Player player : client.getPlayers())
		{
			if (drawn >= MAX_BADGES)
			{
				break;
			}
			if (player == null || player.getName() == null)
			{
				continue;
			}
			if (!presence.isOnline(player.getName()))
			{
				continue;
			}

			Point location = player.getCanvasImageLocation(image, player.getLogicalHeight() + Y_OFFSET);
			if (location == null)
			{
				// Off screen or behind the camera — nothing to draw.
				continue;
			}

			g.drawImage(image, location.getX(), location.getY(), null);
			drawn++;
		}

		return null;
	}
}
