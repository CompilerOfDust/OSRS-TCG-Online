package com.thecardexchange.tcg.packs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import com.thecardexchange.tcg.FeatureGate;
import com.thecardexchange.tcg.ui.BlockedNotice;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * The <b>Card Collection</b> orb — a painted extra in the minimap's orb column, carrying the Mystic cards
 * item icon on a violet plate. Hovering lifts the background and shows a tooltip; clicking opens (or puts
 * away) the {@link CardPacksInterface} collection view over the inventory, and the click is consumed so
 * it never reaches the game behind. Its twin on the left, {@link PackOpeningOrb}, is where packs open.
 *
 * <p>It sits in the <b>top-right corner of the client</b>, clear of the game's own orb column and of the
 * tab row underneath it, so nothing it covers is clickable game furniture.
 */
@Singleton
public class CardPacksOrb extends Overlay
{
	/** "Mystic cards" — the item whose icon the orb wears. */
	private static final int MYSTIC_CARDS_ITEM_ID = 27645;
	private static final int SIZE = 40;
	/** Inset from the top-right corner of the canvas. */
	private static final int MARGIN = 6;
	private static final Color GLOW = new Color(96, 68, 134);
	private static final Color GLOW_HOVER = new Color(146, 104, 198);
	private static final Color GLOW_EDGE = new Color(52, 36, 74);
	private static final Color EDGE = new Color(18, 13, 25);

	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final FeatureGate gate;
	private final BlockedNotice notice;
	private final TooltipManager tooltipManager;
	private final ItemManager itemManager;
	private final TheCardExchangeTcgConfig config;
	private final CardPacksInterface packs;
	private final MouseHandler mouseHandler = new MouseHandler();

	/** Where the last painted frame put the orb — the hit-test the AWT thread reads. */
	@Nullable
	private volatile Rectangle bounds;
	private volatile boolean hovered;
	@Nullable
	private BufferedImage icon;

	@Inject
	CardPacksOrb(
		Client client,
		OverlayManager overlayManager,
		MouseManager mouseManager,
		FeatureGate gate,
		BlockedNotice notice,
		TooltipManager tooltipManager,
		ItemManager itemManager,
		TheCardExchangeTcgConfig config,
		CardPacksInterface packs)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.gate = gate;
		this.notice = notice;
		this.tooltipManager = tooltipManager;
		this.itemManager = itemManager;
		this.config = config;
		this.packs = packs;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGH);
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	void start()
	{
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouseHandler);
	}

	void shutdown()
	{
		bounds = null;
		hovered = false;
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouseHandler);
	}

	// ── Painting ──────────────────────────────────────────────────────────────

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.cardPacksOrb() || client.getGameState() != GameState.LOGGED_IN)
		{
			bounds = null;
			return null;
		}
		Rectangle orb = place();
		if (orb == null)
		{
			bounds = null;
			return null;
		}
		bounds = orb;

		boolean hot = hovered || packs.isOpen();
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Dark rim, then a lit violet well the icon sits in — the hover state simply burns brighter.
		int size = orb.width;
		g.setColor(EDGE);
		g.fillOval(orb.x, orb.y, size, size);
		g.setPaint(new RadialGradientPaint(
			new Point2D.Float(orb.x + size / 2f, orb.y + size / 2f - 2f),
			size / 2f,
			new float[]{0f, 0.62f, 1f},
			new Color[]{hot ? GLOW_HOVER : GLOW, hot ? GLOW : GLOW_EDGE, EDGE}));
		g.fillOval(orb.x + 2, orb.y + 2, size - 4, size - 4);
		g.setPaint(null);
		g.setColor(hot ? OsrsSkin.ORANGE : OsrsSkin.BORDER_LIGHT);
		g.drawOval(orb.x + 1, orb.y + 1, size - 3, size - 3);
		g.setColor(OsrsSkin.BORDER_DARK);
		g.drawOval(orb.x, orb.y, size - 1, size - 1);

		BufferedImage image = icon();
		if (image != null)
		{
			int w = size - 6;
			int h = Math.max(1, image.getHeight() * w / Math.max(1, image.getWidth()));
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, orb.x + (size - w) / 2, orb.y + (size - h) / 2, w, h, null);
		}

		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}

		if (hovered)
		{
			tooltipManager.add(new Tooltip("Card Collection"));
		}
		return null;
	}

	/** The item icon, fetched once. It arrives asynchronously, so a null here just means "not yet". */
	@Nullable
	private BufferedImage icon()
	{
		if (icon == null)
		{
			icon = itemManager.getImage(MYSTIC_CARDS_ITEM_ID);
		}
		return icon;
	}

	/**
	 * Pins the orb to the top-right corner of the game area, or null before there is one to measure. The
	 * width comes from the client's <em>real</em> dimensions rather than the AWT canvas: in stretched mode
	 * the canvas is the blown-up size, while overlays paint in the game's own coordinates, so measuring the
	 * canvas would fling the orb off the right-hand edge.
	 */
	@Nullable
	private Rectangle place()
	{
		Dimension game = client.getRealDimensions();
		int width = game != null && game.width > 0 ? game.width : client.getCanvasWidth();
		if (width <= 0)
		{
			return null;
		}
		return new Rectangle(width - SIZE - MARGIN, MARGIN, SIZE, SIZE);
	}

	// ── Input ─────────────────────────────────────────────────────────────────

	/** Tracks hover, and swallows a click on the orb so the game doesn't also act on it. */
	private final class MouseHandler extends MouseAdapter
	{
		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			hovered = over(event.getPoint());
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			hovered = over(event.getPoint());
			return event;
		}

		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (!over(event.getPoint()))
			{
				return event;
			}
			hovered = true;
			if (event.getButton() == MouseEvent.BUTTON1)
			{
				// Blocked characters get the reason, not the window. Showing the
				// interface and refusing inside it would look like a bug; refusing
				// at the door and saying why is the honest version.
				if (!gate.isPlayable())
				{
					notice.show();
					event.consume();
					return event;
				}
				packs.toggle();
			}
			// Consume either button: a right-click on the orb shouldn't open the game's menu behind it.
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			return swallow(event);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent event)
		{
			return swallow(event);
		}

		@Override
		public MouseEvent mouseExited(MouseEvent event)
		{
			hovered = false;
			return event;
		}

		private MouseEvent swallow(MouseEvent event)
		{
			if (over(event.getPoint()))
			{
				event.consume();
			}
			return event;
		}

		private boolean over(Point point)
		{
			Rectangle orb = bounds;
			// Round, so hit-test the inscribed circle — the corners belong to the game behind.
			return orb != null && orb.contains(point)
				&& Point.distance(point.x, point.y, orb.getCenterX(), orb.getCenterY()) <= orb.width / 2f;
		}
	}
}
