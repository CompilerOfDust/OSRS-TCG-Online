package com.thecardexchange.tcg.items;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;

/**
 * Draws the lock over items the character hasn't earned: a dark wash across the item's slot and a small
 * padlock in its corner, wherever items are shown — inventory, worn equipment, the bank, and the bank's
 * inventory side.
 *
 * <p>Purely a display: {@link ItemLockManager} is what actually stops a locked item being used. The two
 * read the same {@link ItemLocks}, so what looks locked and what behaves locked can't disagree.
 */
@Singleton
public class LockedItemOverlay extends Overlay
{
	/** Item slots live under these containers; each child with an item id is one slot. */
	private static final int[] ITEM_CONTAINERS = {
		ComponentID.INVENTORY_CONTAINER,
		ComponentID.FIXED_VIEWPORT_INVENTORY_CONTAINER,
		ComponentID.RESIZABLE_VIEWPORT_INVENTORY_CONTAINER,
		ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_CONTAINER,
		ComponentID.BANK_ITEM_CONTAINER,
		ComponentID.BANK_INVENTORY_ITEM_CONTAINER,
		ComponentID.EQUIPMENT_INVENTORY_ITEM_CONTAINER,
	};

	private static final Color WASH = new Color(0, 0, 0, 145);

	private final Client client;
	private final ItemLocks locks;
	private final TheCardExchangeTcgConfig config;

	@Nullable
	private BufferedImage padlock;

	@Inject
	LockedItemOverlay(Client client, ItemLocks locks, TheCardExchangeTcgConfig config)
	{
		this.client = client;
		this.locks = locks;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_LOW);
	}

	void loadIcon()
	{
		padlock = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/lock.png");
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.lockUncollectedItems() || !locks.isLoaded()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}

		Shape outerClip = g.getClip();
		for (int component : ITEM_CONTAINERS)
		{
			Widget container = client.getWidget(component);
			if (container == null || container.isHidden())
			{
				continue;
			}
			Widget[] children = container.getDynamicChildren();
			if (children == null)
			{
				continue;
			}

			// The bank's item container is as tall as its *contents*, not its window, so slots scrolled out
			// of view still report bounds. Clip to what's actually visible — the container intersected with
			// every ancestor that frames it — or the padlocks spill across the screen.
			Rectangle visible = visibleBounds(container);
			if (visible == null || visible.isEmpty())
			{
				continue;
			}
			g.setClip(visible);

			for (Widget slot : children)
			{
				if (slot == null || slot.isHidden() || slot.getItemId() <= 0)
				{
					continue;
				}
				Rectangle bounds = slot.getBounds();
				if (bounds == null || !visible.intersects(bounds))
				{
					continue;
				}
				if (locks.isLocked(slot.getItemId()))
				{
					drawLock(g, bounds);
				}
			}
			g.setClip(outerClip);
		}
		return null;
	}

	/** A widget's on-screen area: its own bounds, cropped by every ancestor that clips it. */
	@Nullable
	private static Rectangle visibleBounds(Widget widget)
	{
		Rectangle bounds = widget.getBounds();
		if (bounds == null)
		{
			return null;
		}
		Rectangle visible = new Rectangle(bounds);
		for (Widget parent = widget.getParent(); parent != null; parent = parent.getParent())
		{
			Rectangle parentBounds = parent.getBounds();
			if (parentBounds != null && !parentBounds.isEmpty())
			{
				visible = visible.intersection(parentBounds);
				if (visible.isEmpty())
				{
					return null;
				}
			}
		}
		return visible;
	}

	private void drawLock(Graphics2D g, @Nullable Rectangle slot)
	{
		if (slot == null || slot.width <= 0 || slot.height <= 0)
		{
			return;
		}
		g.setColor(WASH);
		g.fillRect(slot.x, slot.y, slot.width, slot.height);

		BufferedImage icon = padlock;
		if (icon == null)
		{
			return;
		}
		// Bottom-right, small: the item underneath still has to be recognisable.
		int size = Math.max(8, Math.min(slot.width, slot.height) / 2);
		g.drawImage(icon, slot.x + slot.width - size, slot.y + slot.height - size, size, size, null);
	}
}
