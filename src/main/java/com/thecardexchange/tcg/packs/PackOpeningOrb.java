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
import net.runelite.client.util.ImageUtil;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * The <b>Open Packs</b> orb — the {@link CardPacksOrb}'s twin on the other side of the screen, wearing the
 * pack artwork itself. Hovering lifts the background and shows a tooltip; clicking opens (or puts away)
 * the {@link PackOpeningInterface} ceremony, and the click is consumed so it never reaches the game.
 *
 * <p>It sits in the <b>top-left corner of the client</b>, mirroring the Card Packs orb on the top-right,
 * so nothing it covers is clickable game furniture.
 */
@Singleton
public class PackOpeningOrb extends Overlay
{
	private static final int SIZE = 40;
	/** Inset from the top-left corner of the canvas. */
	private static final int MARGIN = 6;
	private static final Color GLOW = new Color(96, 68, 134);
	private static final Color GLOW_HOVER = new Color(146, 104, 198);
	private static final Color GLOW_EDGE = new Color(52, 36, 74);
	private static final Color EDGE = new Color(18, 13, 25);
	/** The little specular highlight that makes the readiness pip read as a lamp, not a dot. */
	private static final Color PIP_SHEEN = new Color(180, 245, 180);

	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final FeatureGate gate;
	private final BlockedNotice notice;
	private final TooltipManager tooltipManager;
	private final TheCardExchangeTcgConfig config;
	private final PackOpeningInterface opening;
	private final Wallet wallet;
	private final MouseHandler mouseHandler = new MouseHandler();

	/** Where the last painted frame put the orb — the hit-test the AWT thread reads. */
	@Nullable
	private volatile Rectangle bounds;
	private volatile boolean hovered;
	@Nullable
	private BufferedImage icon;

	@Inject
	PackOpeningOrb(
		Client client,
		OverlayManager overlayManager,
		MouseManager mouseManager,
		FeatureGate gate,
		BlockedNotice notice,
		TooltipManager tooltipManager,
		TheCardExchangeTcgConfig config,
		PackOpeningInterface opening,
		Wallet wallet)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.gate = gate;
		this.notice = notice;
		this.tooltipManager = tooltipManager;
		this.config = config;
		this.opening = opening;
		this.wallet = wallet;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGH);
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	void start()
	{
		icon = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/pack_standard.png");
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
		if (!config.openPacksOrb() || client.getGameState() != GameState.LOGGED_IN)
		{
			bounds = null;
			return null;
		}
		Rectangle orb = place();
		bounds = orb;

		boolean hot = hovered || opening.isOpen();
		// "You can afford a pack right now." Gated on the feature gate as well as the wallet: a green
		// invitation over an orb that answers with a hold notice would read as the hold not being
		// real, which is the exact failure FeatureGate exists to prevent.
		boolean ready = gate.isPlayable() && wallet.canOpenPack();
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Dark rim, then a lit violet well the pack sits in — the hover state simply burns brighter.
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
		g.setColor(hot ? OsrsSkin.ORANGE : ready ? breathingRim() : OsrsSkin.BORDER_LIGHT);
		g.drawOval(orb.x + 1, orb.y + 1, size - 3, size - 3);
		g.setColor(OsrsSkin.BORDER_DARK);
		g.drawOval(orb.x, orb.y, size - 1, size - 1);

		BufferedImage image = icon;
		if (image != null)
		{
			// The pack is a tall rectangle in a round well: fit it by height and let the well frame it.
			int h = size - 8;
			int w = Math.max(1, image.getWidth() * h / Math.max(1, image.getHeight()));
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, orb.x + (size - w) / 2, orb.y + (size - h) / 2, w, h, null);
		}

		if (ready)
		{
			drawReadyPip(g, orb);
		}

		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}

		if (hovered)
		{
			tooltipManager.add(new Tooltip(ready
				? "Open Packs</br>" + String.format("%,d", wallet.getCredits()) + " credits"
				: "Open Packs"));
		}
		return null;
	}

	/**
	 * The readiness pip: a small green light in the orb's bottom-right.
	 *
	 * <p><b>Bottom-right specifically.</b> The orb is inset only {@value #MARGIN}px from the corner of
	 * the canvas, so anything hung off its top or left edge would be clipped. Down and right there is
	 * the whole viewport, and the pip still lands inside the inscribed circle the click test uses — so
	 * it adds a signal without adding an unclickable limb. It is also where {@code LockedItemOverlay}
	 * puts its padlock, so the placement already means "a note about this thing" in this plugin.
	 *
	 * <p>Drawn as a socket, a light and a highlight rather than a flat dot: a plain circle over the
	 * violet well reads as a rendering artefact, whereas a seated lamp reads as deliberate.
	 */
	private void drawReadyPip(Graphics2D g, Rectangle orb)
	{
		g.setColor(EDGE);
		g.fillOval(orb.x + 25, orb.y + 25, 14, 14);
		g.setColor(OsrsSkin.GOOD);
		g.fillOval(orb.x + 26, orb.y + 26, 12, 12);
		g.setColor(PIP_SHEEN);
		g.fillOval(orb.x + 28, orb.y + 27, 4, 4);
	}

	/**
	 * The rim, breathing between its resting colour and green while a pack is affordable.
	 *
	 * <p>The obvious move was to reuse the pulsing halo the pack window draws around a rare pull — the
	 * plugin's established "look here" gesture. It does not fit: that halo bleeds 9px, and the orb has
	 * {@value #MARGIN}px of canvas margin, so it would be clipped against two edges. Colouring the ring
	 * that is already drawn costs no geometry, cannot clip, and adds no new hit-test surface.
	 *
	 * <p>Same breathing curve as that halo, deliberately — one plugin, one heartbeat.
	 */
	private static Color breathingRim()
	{
		float t = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 350.0);
		return blend(OsrsSkin.BORDER_LIGHT, OsrsSkin.GOOD, t);
	}

	private static Color blend(Color from, Color to, float amount)
	{
		float a = Math.max(0f, Math.min(1f, amount));
		return new Color(
			Math.round(from.getRed() + (to.getRed() - from.getRed()) * a),
			Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * a),
			Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * a));
	}

	/** Pins the orb to the top-left corner of the game area — no measuring needed on this side. */
	private Rectangle place()
	{
		return new Rectangle(MARGIN, MARGIN, SIZE, SIZE);
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
				opening.toggle();
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
