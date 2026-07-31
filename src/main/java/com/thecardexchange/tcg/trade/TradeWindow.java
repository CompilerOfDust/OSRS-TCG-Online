package com.thecardexchange.tcg.trade;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import com.thecardexchange.tcg.packs.CardArt;
import com.thecardexchange.tcg.packs.CatalogueCard;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * The trade window — painted straight onto the game canvas (no pop-out Swing frame), styled after the
 * Old School trade interface in a slightly darker palette (see {@link OsrsSkin}).
 *
 * <p>It opens <b>only once a trade is accepted on both ends</b>; the offer / incoming stages are chat
 * lines. While it is up, every mouse event inside its bounds is <b>consumed</b>, so clicking, dragging or
 * scrolling in the window never reaches the game underneath (no walking to the tile behind it, no
 * right-click menu, no camera zoom).
 *
 * <p>Painting happens on the client thread; mouse events arrive on the AWT thread — the two share only
 * volatile state ({@link #open}, the names, and the {@link Layout} snapshot the last frame painted).
 */
@Singleton
public class TradeWindow extends Overlay
{
	private static final int COLS = 4;
	private static final int ROWS = 3;
	private static final int SLOT_W = 40;
	private static final int SLOT_H = 54;
	private static final int SLOT_GAP = 4;
	private static final int PAD = 10;
	private static final int SIDE_PAD = 8;
	private static final int TITLE_H = 26;
	private static final int HEADER_H = 16;
	private static final int STATUS_H = 16;
	private static final int MID_GAP = 12;
	private static final int BUTTON_W = 104;
	private static final int BUTTON_H = 26;
	private static final int CLOSE_SIZE = 16;

	private static final int GRID_W = COLS * SLOT_W + (COLS - 1) * SLOT_GAP;
	private static final int GRID_H = ROWS * SLOT_H + (ROWS - 1) * SLOT_GAP;
	private static final int SIDE_W = GRID_W + SIDE_PAD * 2;
	private static final int SIDE_H = HEADER_H + 4 + GRID_H + 4 + STATUS_H;
	private static final int WINDOW_W = PAD * 2 + SIDE_W * 2 + MID_GAP;
	private static final int WINDOW_H = TITLE_H + 6 + SIDE_H + 10 + BUTTON_H + PAD;

	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final CardArt cardArt;
	private final InputHandler input = new InputHandler();

	private volatile boolean open;
	private volatile String other = "";
	/** What each side has put on the table, in the order it went up. */
	private volatile List<CatalogueCard> myOffer = Collections.emptyList();
	private volatile List<CatalogueCard> theirOffer = Collections.emptyList();
	/** Where the deal stands. Both true only for the instant before the server settles it. */
	private volatile boolean myAccepted;
	private volatile boolean theirAccepted;
	/** Called when this side clicks a live Accept. */
	@Nullable
	private volatile Runnable acceptListener;
	/** Where the last painted frame put everything — the hit-test the AWT thread reads. */
	@Nullable
	private volatile Layout layout;
	@Nullable
	private volatile Point mouse;
	/** How far the player has alt-dragged the window from where it would centre itself. */
	private volatile int dragX;
	private volatile int dragY;
	/** While alt-dragging: where inside the window the drag started. */
	@Nullable
	private volatile Point grab;
	/** Called when the player dismisses the window (Decline / the X). */
	@Nullable
	private volatile Runnable closeListener;
	/** Called when one of your own offered cards is clicked — that takes it back off the table. */
	@Nullable
	private volatile SlotListener slotListener;

	@Inject
	TradeWindow(Client client, OverlayManager overlayManager, MouseManager mouseManager, CardArt cardArt)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.cardArt = cardArt;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	public void start()
	{
		overlayManager.add(this);
		mouseManager.registerMouseListener(input);
		mouseManager.registerMouseWheelListener(input);
	}

	public void shutdown()
	{
		open = false;
		layout = null;
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(input);
		mouseManager.unregisterMouseWheelListener(input);
	}

	/** Runs when the player dismisses the window themselves (Decline or the X). */
	public void setCloseListener(@Nullable Runnable listener)
	{
		this.closeListener = listener;
	}

	/** What a click on one of your own offered cards does. */
	public interface SlotListener
	{
		void onSlotClicked(CatalogueCard card);
	}

	public void setSlotListener(@Nullable SlotListener listener)
	{
		this.slotListener = listener;
	}

	/** Runs when this side clicks Accept while the button is live. */
	public void setAcceptListener(@Nullable Runnable listener)
	{
		this.acceptListener = listener;
	}

	/** Where the deal stands, from the server's announcements. Any offer change resets both. */
	public void setAccepted(boolean mine, boolean theirs)
	{
		this.myAccepted = mine;
		this.theirAccepted = theirs;
	}

	/** Open (or refresh) the accepted-trade window against the player we're trading with. */
	public void showAccepted(String other)
	{
		this.other = other;
		this.myOffer = Collections.emptyList();
		this.theirOffer = Collections.emptyList();
		this.myAccepted = false;
		this.theirAccepted = false;
		this.open = true;
	}

	/** The cards on the table. Both sides come from the server, so the two clients can't disagree. */
	public void setOffers(List<CatalogueCard> mine, List<CatalogueCard> theirs)
	{
		this.myOffer = Collections.unmodifiableList(new ArrayList<>(mine));
		this.theirOffer = Collections.unmodifiableList(new ArrayList<>(theirs));
	}

	public void close()
	{
		open = false;
		layout = null;
		mouse = null;
		grab = null;
	}

	public boolean isOpen()
	{
		return open;
	}

	// ── Painting ──────────────────────────────────────────────────────────────

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!open || client.getGameState() != GameState.LOGGED_IN)
		{
			layout = null;
			return null;
		}

		Layout l = new Layout(origin());
		layout = l;
		Point hover = mouse;

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Stroke oldStroke = g.getStroke();
		Shape oldClip = g.getClip();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setStroke(new BasicStroke(1f));

		String them = OsrsSkin.trim(other, 12);
		List<CatalogueCard> mine = myOffer;
		List<CatalogueCard> theirs = theirOffer;
		OsrsSkin.frame(g, l.window);
		drawTitle(g, l, hover);
		drawSide(g, l.leftSide, "Your cards",
			mine.isEmpty() ? "You offer nothing yet" : offerLine(mine.size()), l.leftSlots, mine);
		drawSide(g, l.rightSide, possessive(them) + " cards",
			theirs.isEmpty() ? them + " offers nothing yet" : offerLine(theirs.size()), l.rightSlots, theirs);
		drawButtons(g, l, hover);

		g.setClip(oldClip);
		g.setStroke(oldStroke);
		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
		return null;
	}

	private void drawTitle(Graphics2D g, Layout l, @Nullable Point hover)
	{
		Rectangle r = l.window;
		OsrsSkin.titleStrip(g, r, TITLE_H);
		OsrsSkin.centred(g, "Trading Cards With: " + OsrsSkin.trim(other, 16),
			FontManager.getRunescapeBoldFont(), OsrsSkin.ORANGE, r.x + r.width / 2, r.y + TITLE_H - 8);
		OsrsSkin.closeButton(g, l.close, contains(l.close, hover));
	}

	private static String offerLine(int count)
	{
		return count == 1 ? "1 card offered" : count + " cards offered";
	}

	/** One player's half: header, the inset card grid holding that side's offer, and the status line. */
	private void drawSide(Graphics2D g, Rectangle side, String header, String status, Rectangle grid,
		List<CatalogueCard> offer)
	{
		OsrsSkin.centred(g, header, FontManager.getRunescapeSmallFont(), OsrsSkin.ORANGE,
			side.x + side.width / 2, side.y + HEADER_H - 4);

		OsrsSkin.well(g, new Rectangle(grid.x - SIDE_PAD / 2, grid.y - 2, grid.width + SIDE_PAD, grid.height + 4));

		for (int row = 0; row < ROWS; row++)
		{
			for (int col = 0; col < COLS; col++)
			{
				Rectangle slot = new Rectangle(grid.x + col * (SLOT_W + SLOT_GAP),
					grid.y + row * (SLOT_H + SLOT_GAP), SLOT_W, SLOT_H);
				g.setColor(OsrsSkin.SLOT_FILL);
				g.fillRect(slot.x, slot.y, slot.width, slot.height);
				OsrsSkin.inset(g, slot);

				int index = row * COLS + col;
				if (index < offer.size())
				{
					drawOfferedCard(g, slot, offer.get(index));
				}
			}
		}

		OsrsSkin.centred(g, status, FontManager.getRunescapeSmallFont(), OsrsSkin.MUTED,
			side.x + side.width / 2, side.y + side.height - 4);
	}

	/** A card sitting in a slot: its picture, framed in its gem tier, named underneath. */
	private void drawOfferedCard(Graphics2D g, Rectangle slot, CatalogueCard card)
	{
		BufferedImage art = cardArt.imageFor(card);
		if (art != null)
		{
			int maxW = slot.width - 6;
			int maxH = slot.height - 14;
			double scale = Math.min(maxW / (double) art.getWidth(), maxH / (double) art.getHeight());
			int w = Math.max(1, (int) (art.getWidth() * scale));
			int h = Math.max(1, (int) (art.getHeight() * scale));
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(art, slot.x + (slot.width - w) / 2, slot.y + 3, w, h, null);
		}
		g.setColor(card.getTierColour());
		g.drawRect(slot.x, slot.y, slot.width - 1, slot.height - 1);

		OsrsSkin.centred(g, OsrsSkin.ellipsise(g, card.getName(), FontManager.getRunescapeSmallFont(),
				slot.width - 4), FontManager.getRunescapeSmallFont(), card.getTierColour(),
			slot.x + slot.width / 2, slot.y + slot.height - 3);
	}

	private void drawButtons(Graphics2D g, Layout l, @Nullable Point hover)
	{
		// Accept goes live once anything is on the table; accepting again is meaningless, so it greys
		// back out while we wait on the other side.
		boolean canAccept = acceptEnabled();
		boolean acceptHot = canAccept && contains(l.accept, hover);
		OsrsSkin.plate(g, l.accept, acceptHot);
		OsrsSkin.centred(g, myAccepted ? "Accepted" : "Accept", FontManager.getRunescapeFont(),
			canAccept ? (acceptHot ? OsrsSkin.ORANGE : OsrsSkin.TEXT) : OsrsSkin.MUTED,
			l.accept.x + l.accept.width / 2, l.accept.y + 18);

		boolean hot = contains(l.decline, hover);
		OsrsSkin.plate(g, l.decline, hot);
		OsrsSkin.centred(g, "Decline", FontManager.getRunescapeFont(), hot ? OsrsSkin.RED_HOVER : OsrsSkin.RED,
			l.decline.x + l.decline.width / 2, l.decline.y + 18);

		// The line between the buttons narrates where the deal stands — the same job the real trade
		// screen's "Other player has accepted" line does.
		String line;
		Color colour;
		if (myAccepted && theirAccepted)
		{
			line = "Completing…";
			colour = OsrsSkin.YELLOW;
		}
		else if (myAccepted)
		{
			line = "Waiting for " + OsrsSkin.trim(other, 12) + "…";
			colour = OsrsSkin.YELLOW;
		}
		else if (theirAccepted)
		{
			line = OsrsSkin.trim(other, 12) + " has accepted!";
			colour = OsrsSkin.ORANGE;
		}
		else if (myOffer.isEmpty() && theirOffer.isEmpty())
		{
			line = "Click duplicates to offer them";
			colour = OsrsSkin.YELLOW;
		}
		else
		{
			line = "Accept to trade";
			colour = OsrsSkin.YELLOW;
		}
		OsrsSkin.centred(g, line, FontManager.getRunescapeSmallFont(), colour,
			l.window.x + l.window.width / 2, l.accept.y + 18);
	}

	private boolean acceptEnabled()
	{
		return !myAccepted && (!myOffer.isEmpty() || !theirOffer.isEmpty());
	}

	// ── Placement ─────────────────────────────────────────────────────────────

	/**
	 * Where the window sits: centred over the game scene (the viewport, so the side panel doesn't skew it)
	 * plus however far the player has alt-dragged it, kept inside the game area.
	 */
	private Rectangle origin()
	{
		Dimension game = client.getRealDimensions();
		int gameW = game != null ? game.width : client.getCanvasWidth();
		int gameH = game != null ? game.height : client.getCanvasHeight();

		int width = client.getViewportWidth() > 0 ? client.getViewportWidth() : gameW;
		int height = client.getViewportHeight() > 0 ? client.getViewportHeight() : gameH;
		int offsetX = client.getViewportWidth() > 0 ? client.getViewportXOffset() : 0;
		int offsetY = client.getViewportHeight() > 0 ? client.getViewportYOffset() : 0;

		int naturalX = offsetX + (width - WINDOW_W) / 2;
		int naturalY = offsetY + (height - WINDOW_H) / 2;
		Rectangle window = new Rectangle(naturalX + dragX, naturalY + dragY, WINDOW_W, WINDOW_H);
		OsrsSkin.clampInto(window, gameW, gameH);
		// Absorb the clamp into the offset, so dragging back off an edge responds immediately.
		dragX = window.x - naturalX;
		dragY = window.y - naturalY;
		return window;
	}

	/** Everything the last painted frame laid out, in canvas coordinates. */
	private static final class Layout
	{
		private final Rectangle window;
		private final Rectangle close;
		private final Rectangle leftSide;
		private final Rectangle rightSide;
		private final Rectangle leftSlots;
		private final Rectangle rightSlots;
		private final Rectangle accept;
		private final Rectangle decline;

		private Layout(Rectangle bounds)
		{
			int x = bounds.x;
			int y = bounds.y;
			window = bounds;
			close = new Rectangle(x + WINDOW_W - PAD - CLOSE_SIZE, y + (TITLE_H - CLOSE_SIZE) / 2,
				CLOSE_SIZE, CLOSE_SIZE);

			int sidesY = y + TITLE_H + 6;
			leftSide = new Rectangle(x + PAD, sidesY, SIDE_W, SIDE_H);
			rightSide = new Rectangle(x + PAD + SIDE_W + MID_GAP, sidesY, SIDE_W, SIDE_H);
			leftSlots = new Rectangle(leftSide.x + SIDE_PAD, sidesY + HEADER_H + 4, GRID_W, GRID_H);
			rightSlots = new Rectangle(rightSide.x + SIDE_PAD, sidesY + HEADER_H + 4, GRID_W, GRID_H);

			int buttonsY = sidesY + SIDE_H + 10;
			accept = new Rectangle(x + PAD + 6, buttonsY, BUTTON_W, BUTTON_H);
			decline = new Rectangle(x + WINDOW_W - PAD - 6 - BUTTON_W, buttonsY, BUTTON_W, BUTTON_H);
		}
	}

	// ── Input: everything inside the window is swallowed ───────────────────────

	/**
	 * Eats mouse input over the window so it never reaches the game: presses (which is what opens the
	 * right-click menu and walks the player), releases, clicks, drags, moves (so nothing under the window
	 * highlights) and the wheel (camera zoom).
	 */
	private final class InputHandler extends MouseAdapter implements MouseWheelListener
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (!inside(event))
			{
				return event;
			}
			mouse = event.getPoint();
			Layout l = layout;
			if (event.isAltDown() && l != null)
			{
				// Alt-drag moves the window; the controls under the cursor stay untouched.
				grab = new Point(event.getPoint().x - l.window.x, event.getPoint().y - l.window.y);
			}
			else
			{
				hit(event.getPoint());
			}
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			grab = null;
			return swallow(event);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent event)
		{
			return swallow(event);
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			if (grab != null)
			{
				mouse = event.getPoint();
				dragTo(event.getPoint());
				event.consume();
				return event;
			}
			return swallow(event);
		}

		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			Layout l = layout;
			if (!open || l == null)
			{
				mouse = null;
				return event;
			}
			boolean in = l.window.contains(event.getPoint());
			mouse = in ? event.getPoint() : null;
			if (in)
			{
				event.consume();
			}
			return event;
		}

		@Override
		public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
		{
			Layout l = layout;
			if (open && l != null && l.window.contains(event.getPoint()))
			{
				event.consume();
			}
			return event;
		}

		private MouseEvent swallow(MouseEvent event)
		{
			if (inside(event))
			{
				event.consume();
			}
			return event;
		}

		private boolean inside(MouseEvent event)
		{
			Layout l = layout;
			return open && l != null && l.window.contains(event.getPoint());
		}
	}

	/**
	 * Alt-drag: shift the window by however far the cursor has moved from where it grabbed. The offset is
	 * kept relative to the window's natural (centred) position, so it survives the game being resized.
	 */
	private void dragTo(Point point)
	{
		Layout l = layout;
		Point held = grab;
		if (l == null || held == null)
		{
			return;
		}
		dragX += point.x - held.x - l.window.x;
		dragY += point.y - held.y - l.window.y;
	}

	/** A press landed in the window — act on it if it hit a control. */
	private void hit(Point point)
	{
		Layout l = layout;
		if (l == null)
		{
			return;
		}
		if (l.close.contains(point) || l.decline.contains(point))
		{
			close();
			Runnable listener = closeListener;
			if (listener != null)
			{
				listener.run();
			}
			return;
		}

		if (l.accept.contains(point))
		{
			Runnable accept = acceptListener;
			if (acceptEnabled() && accept != null)
			{
				// Optimistic: the button reads Accepted at once; the server's push is what settles it.
				myAccepted = true;
				accept.run();
			}
			return;
		}

		// Your own side only: clicking a card you've put up takes it back off. Theirs isn't yours to move.
		SlotListener slots = slotListener;
		if (slots == null)
		{
			return;
		}
		List<CatalogueCard> mine = myOffer;
		for (int row = 0; row < ROWS; row++)
		{
			for (int col = 0; col < COLS; col++)
			{
				int index = row * COLS + col;
				if (index >= mine.size())
				{
					return;
				}
				Rectangle slot = new Rectangle(l.leftSlots.x + col * (SLOT_W + SLOT_GAP),
					l.leftSlots.y + row * (SLOT_H + SLOT_GAP), SLOT_W, SLOT_H);
				if (slot.contains(point))
				{
					slots.onSlotClicked(mine.get(index));
					return;
				}
			}
		}
	}

	private static boolean contains(Rectangle r, @Nullable Point p)
	{
		return p != null && r.contains(p);
	}

	private static String possessive(String name)
	{
		if (name.isEmpty())
		{
			return "Their";
		}
		return name.endsWith("s") ? name + "'" : name + "'s";
	}
}
