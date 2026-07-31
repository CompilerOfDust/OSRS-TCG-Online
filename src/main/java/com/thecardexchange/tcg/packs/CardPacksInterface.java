package com.thecardexchange.tcg.packs;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.CharacterTracker;
import com.thecardexchange.tcg.account.ExchangeApiClient;
import com.thecardexchange.tcg.items.ItemLockManager;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * The card collection interface — painted over the inventory, opened from the orb ({@link CardPacksOrb})
 * or alongside a trade. Two things in one panel:
 *
 * <ul>
 *   <li><b>Search.</b> Filters the grid by name, locally — the whole catalogue is already here.</li>
 *   <li><b>The collection.</b> Every card in the catalogue, ordered by card id, four to a row and
 *       scrolling by whole rows. Cards you own are drawn at full strength; the ones you don't are the
 *       same face dimmed, their artwork faded. Double-clicking any card — collected or not — opens its
 *       detail view.</li>
 * </ul>
 *
 * <p>Opening packs lives in its own ceremony, {@link PackOpeningInterface}, behind the orb on the other
 * side of the screen — this view just shows what the packs have built.
 *
 * <p>Item cards use the client's own item icons; NPC cards use wiki art fetched in the background (see
 * {@link CardArt}). Like the trade window it consumes every mouse event inside its bounds, and while it's
 * open the search field owns the keyboard ({@code Escape} hands it back).
 */
@Slf4j
@Singleton
public class CardPacksInterface extends Overlay
{
	private static final int TITLE_H = 20;
	private static final int SEARCH_H = 18;
	private static final int FOOTER_H = 18;
	private static final int SCROLLBAR_W = 6;
	private static final int CLOSE_SIZE = 14;
	private static final int PAD = 5;
	private static final int COLUMNS = 4;
	private static final int TILE_GAP = 3;
	/** Old School card proportions (180×260), so a tile is a card rather than a square. */
	private static final double CARD_RATIO = 260.0 / 180.0;
	private static final int INVENTORY_MARGIN_X = 10;
	private static final int INVENTORY_MARGIN_Y = 8;
	private static final int MAX_QUERY = 40;
	/** Two clicks on the same card inside this window open the detail view. */
	private static final long DOUBLE_CLICK_MS = 400L;
	private static final Color DIM = new Color(0, 0, 0, 150);
	/** The gold curated specials wear (CARDS.md §4). */
	static final Color SPECIAL_GOLD = new Color(0xD4, 0xAF, 0x37);

	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final ExchangeApiClient api;
	private final AccountLinkManager linkManager;
	private final CharacterTracker characterTracker;
	private final CardArt cardArt;
	private final CardDetailWindow detail;
	private final ItemLockManager itemLocks;
	private final ScheduledExecutorService scheduler;

	private final MouseHandler mouseHandler = new MouseHandler();
	private final KeyHandler keyHandler = new KeyHandler();

	private volatile boolean open;
	private volatile String query = "";
	private volatile List<CatalogueCard> catalogue = Collections.emptyList();
	private volatile List<CatalogueCard> shown = Collections.emptyList();
	private volatile Map<Integer, Integer> owned = Collections.emptyMap();
	private volatile int credits;
	private volatile String status = "Loading cards…";
	private volatile int scroll;
	@Nullable
	private volatile CatalogueCard selected;
	/** Set while a trade window is open: clicks become offers and the grid shows duplicates only. */
	@Nullable
	private volatile TradePicker tradePicker;
	private volatile Set<Integer> offered = Collections.emptySet();
	/** Last card clicked and when, so a second click on the same tile reads as a double-click. */
	private volatile int lastClickedId;
	private volatile long lastClickMs;
	/** Card id → catalogue entry, for anything that holds ids (the trade window's offers). */
	private volatile Map<Integer, CatalogueCard> byId = Collections.emptyMap();

	@Nullable
	private volatile Layout layout;
	@Nullable
	private volatile Point mouse;
	private volatile int thumbGrabDy = -1;
	private volatile int dragX;
	private volatile int dragY;
	@Nullable
	private volatile Point grab;
	@Nullable
	private volatile Rectangle lastInventory;
	@Nullable
	private BufferedImage cardFront;

	@Inject
	CardPacksInterface(
		Client client,
		OverlayManager overlayManager,
		MouseManager mouseManager,
		KeyManager keyManager,
		ExchangeApiClient api,
		AccountLinkManager linkManager,
		CharacterTracker characterTracker,
		CardArt cardArt,
		CardDetailWindow detail,
		ItemLockManager itemLocks,
		ScheduledExecutorService scheduler)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.keyManager = keyManager;
		this.api = api;
		this.linkManager = linkManager;
		this.characterTracker = characterTracker;
		this.cardArt = cardArt;
		this.detail = detail;
		this.itemLocks = itemLocks;
		this.scheduler = scheduler;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	void start()
	{
		cardFront = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/card_front.png");
		detail.start();
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouseHandler);
		mouseManager.registerMouseWheelListener(mouseHandler);
		keyManager.registerKeyListener(keyHandler);
	}

	void shutdown()
	{
		detail.shutdown();
		close();
		catalogue = Collections.emptyList();
		shown = Collections.emptyList();
		owned = Collections.emptyMap();
		cardArt.clear();
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouseHandler);
		mouseManager.unregisterMouseWheelListener(mouseHandler);
		keyManager.unregisterKeyListener(keyHandler);
	}

	public boolean isOpen()
	{
		return open;
	}

	/** What a click on a card does while a trade is up. */
	public interface TradePicker
	{
		void onPick(CatalogueCard card);
	}

	/**
	 * Trade mode: the grid narrows to cards you hold <b>two or more</b> of — your last copy of anything is
	 * never tradeable — and clicking one offers it instead of just selecting it. Null puts it back to the
	 * normal collection view.
	 */
	public void setTradePicker(@Nullable TradePicker picker)
	{
		this.tradePicker = picker;
		this.offered = Collections.emptySet();
		this.selected = null;
		applyFilter();
	}

	/** The cards currently on the table from this side, so the grid can mark them. */
	public void setOffered(Set<Integer> ids)
	{
		this.offered = Collections.unmodifiableSet(new HashSet<>(ids));
	}

	@Nullable
	public CatalogueCard cardById(int id)
	{
		return byId.get(id);
	}

	/**
	 * Re-reads the collection from the server — after a settled trade, when the counts and unlocks have
	 * changed under us. Works whether or not the interface is showing.
	 */
	public void refreshData()
	{
		refresh();
	}

	/** Orb click: open the interface or put it away. */
	public void toggle()
	{
		if (open)
		{
			close();
		}
		else
		{
			open();
		}
	}

	/** Show the interface — also how a trade brings the card list up alongside its window. */
	public void open()
	{
		open = true;
		refresh();
	}

	public void close()
	{
		open = false;
		layout = null;
		mouse = null;
		thumbGrabDy = -1;
		grab = null;
	}

	/** This window and the detail view it opens on top — nothing left consuming input. */
	public void closeAll()
	{
		detail.close();
		close();
	}

	// ── Data ──────────────────────────────────────────────────────────────────

	/** Pulls the catalogue (once a session) and the character's holdings (every time it opens). */
	private void refresh()
	{
		String token = linkManager.getToken();
		if (token == null)
		{
			status = "Link your account in the side panel";
			return;
		}
		scheduler.execute(() ->
		{
			try
			{
				if (catalogue.isEmpty())
				{
					CardCatalogue loaded = api.cards(token);
					catalogue = Collections.unmodifiableList(loaded.getCards());
					Map<Integer, CatalogueCard> index = new HashMap<>(loaded.getCards().size());
					for (CatalogueCard card : loaded.getCards())
					{
						index.put(card.getId(), card);
					}
					byId = Collections.unmodifiableMap(index);
					// The item locking wants the same catalogue — hand it over rather than fetch it twice.
					itemLocks.applyCatalogue(loaded);
					applyFilter();
				}
				Holdings holdings = api.collection(token, characterTracker.getCurrentRsn());
				owned = holdings.getOwned();
				credits = holdings.getCredits();
				itemLocks.apply(holdings);
				// The trade view filters on what's owned, so it has to be rebuilt once that lands.
				applyFilter();
				status = catalogue.isEmpty() ? "No cards in the catalogue" : "";
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Could not load the card view", ex);
				status = "Couldn't reach the Card Exchange";
			}
		});
	}

	/**
	 * Rebuilds the grid: what's in it, and in what order.
	 *
	 * <p><b>Order.</b> Cards you own come first, best gem first — that's the part of a ten-thousand-card
	 * catalogue you actually care about, and it puts a new pull's best card near the top. Everything you
	 * haven't collected follows in catalogue order, so the unowned run stays a stable, browsable list
	 * rather than reshuffling as you collect.
	 *
	 * <p><b>Contents.</b> The search filters by name; in a trade the grid narrows to cards you hold two or
	 * more of, so the picker can never show a card you'd be giving away whole.
	 */
	private void applyFilter()
	{
		String needle = query.trim().toLowerCase();
		boolean duplicatesOnly = tradePicker != null;
		Map<Integer, Integer> held = owned;

		List<CatalogueCard> filtered = new ArrayList<>();
		for (CatalogueCard card : catalogue)
		{
			if (duplicatesOnly && held.getOrDefault(card.getId(), 0) < 2)
			{
				continue;
			}
			if (!needle.isEmpty() && !card.getName().toLowerCase().contains(needle))
			{
				continue;
			}
			filtered.add(card);
		}

		filtered.sort((a, b) ->
		{
			boolean aOwned = held.getOrDefault(a.getId(), 0) > 0;
			boolean bOwned = held.getOrDefault(b.getId(), 0) > 0;
			if (aOwned != bOwned)
			{
				return aOwned ? -1 : 1;
			}
			if (aOwned && a.getTier() != b.getTier())
			{
				return Integer.compare(b.getTier(), a.getTier());
			}
			return Integer.compare(a.getId(), b.getId());
		});

		shown = Collections.unmodifiableList(filtered);
		scroll = 0;
	}

	// ── Painting ──────────────────────────────────────────────────────────────

	@Override
	public Dimension render(Graphics2D g)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			// Never leave the keyboard captured across a logout.
			close();
			return null;
		}
		if (!open)
		{
			layout = null;
			return null;
		}

		Layout l = new Layout(panelBounds());
		layout = l;
		Point hover = mouse;

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Stroke oldStroke = g.getStroke();
		Shape oldClip = g.getClip();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setStroke(new BasicStroke(1f));

		OsrsSkin.frame(g, l.window);
		OsrsSkin.titleStrip(g, l.window, TITLE_H);
		OsrsSkin.centred(g, "Card Collection", FontManager.getRunescapeBoldFont(), OsrsSkin.ORANGE,
			l.window.x + l.window.width / 2, l.window.y + TITLE_H - 6);
		// No close button during a trade: the view belongs to the trade and goes away with it.
		if (tradePicker == null)
		{
			OsrsSkin.closeButton(g, l.close, contains(l.close, hover));
		}

		drawSearch(g, l);
		drawGrid(g, l, hover);
		drawFooter(g, l);

		g.setClip(oldClip);
		g.setStroke(oldStroke);
		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
		return null;
	}

	private void drawSearch(Graphics2D g, Layout l)
	{
		OsrsSkin.well(g, l.search);
		Font font = FontManager.getRunescapeSmallFont();
		int textX = l.search.x + 4;
		int baseline = l.search.y + SEARCH_H - 5;
		String typed = query;

		if (typed.isEmpty())
		{
			// Nudged past the caret, which always sits at the start of an empty field.
			OsrsSkin.text(g, "Search cards…", font, OsrsSkin.MUTED, textX + 5, baseline);
		}
		else
		{
			g.setFont(font);
			String visible = typed;
			int room = l.search.width - 10;
			while (visible.length() > 1 && g.getFontMetrics().stringWidth(visible) > room)
			{
				visible = visible.substring(1);
			}
			OsrsSkin.text(g, visible, font, OsrsSkin.TEXT, textX, baseline);
			textX += g.getFontMetrics().stringWidth(visible);
		}
		if ((System.currentTimeMillis() / 450) % 2 == 0)
		{
			g.setColor(OsrsSkin.ORANGE);
			g.drawLine(textX + 1, l.search.y + 3, textX + 1, l.search.y + SEARCH_H - 4);
		}
	}

	/** The collection grid: four cards a row, ordered by card id, scrolling by whole rows. */
	private void drawGrid(Graphics2D g, Layout l, @Nullable Point hover)
	{
		OsrsSkin.well(g, l.list);
		List<CatalogueCard> cards = shown;
		if (cards.isEmpty())
		{
			OsrsSkin.centred(g, status.isEmpty() ? "No cards match" : status,
				FontManager.getRunescapeSmallFont(), OsrsSkin.MUTED,
				l.list.x + l.list.width / 2, l.list.y + l.list.height / 2);
			return;
		}

		int rows = (cards.size() + COLUMNS - 1) / COLUMNS;
		int rowH = l.tileH + TILE_GAP;
		int viewH = l.list.height - 2;
		int contentH = rows * rowH;
		int offset = clampScroll(scroll, contentH, viewH, rowH);
		scroll = offset;

		Shape outer = g.getClip();
		g.setClip(l.list.x + 1, l.list.y + 1, l.list.width - 2, viewH);

		Map<Integer, Integer> held = owned;
		CatalogueCard chosen = selected;
		int firstRow = Math.max(0, offset / rowH);
		int lastRow = Math.min(rows - 1, (offset + viewH) / rowH);

		for (int row = firstRow; row <= lastRow; row++)
		{
			for (int col = 0; col < COLUMNS; col++)
			{
				int index = row * COLUMNS + col;
				if (index >= cards.size())
				{
					break;
				}
				CatalogueCard card = cards.get(index);
				Rectangle tile = new Rectangle(
					l.list.x + 2 + col * (l.tileW + TILE_GAP),
					l.list.y + 2 + row * rowH - offset,
					l.tileW, l.tileH);
				int quantity = held.getOrDefault(card.getId(), 0);
				boolean onTable = offered.contains(card.getId());
				// In a trade the count is *tradeable copies*, not copies held: the last copy is never
				// tradeable, so it isn't shown — a player should never feel they can trade all their
				// cards. An offered copy is spoken for and comes off the count too, so a pair reads
				// x1 → (offered) committed, dimmed with its UP tag.
				int shownQuantity = tradePicker != null
					? Math.max(0, quantity - 1 - (onTable ? 1 : 0))
					: quantity;
				drawTile(g, tile, card, shownQuantity, contains(tile, hover),
					onTable || (chosen != null && chosen.getId() == card.getId()));
				if (onTable)
				{
					// Already up for trade — say so, since the tile otherwise looks like any other.
					OsrsSkin.text(g, "UP", FontManager.getRunescapeSmallFont(), OsrsSkin.ORANGE,
						tile.x + 3, tile.y + 10);
				}
			}
		}

		g.setClip(outer);

		if (contentH > viewH)
		{
			Rectangle track = l.scrollTrack();
			g.setColor(OsrsSkin.BORDER_DARK);
			g.fillRect(track.x, track.y, track.width, track.height);
			Rectangle thumb = thumbBounds(l, contentH, viewH, offset);
			g.setColor(thumbGrabDy >= 0 ? OsrsSkin.ORANGE : OsrsSkin.PLATE_HOVER);
			g.fillRect(thumb.x, thumb.y, thumb.width, thumb.height);
			OsrsSkin.bevel(g, thumb);
		}
	}

	/**
	 * One card tile: the card-front template over its gem-tier backdrop, the card's own picture in the
	 * window, and a tier-coloured edge. A card you don't have is the same face, dimmed, its art faded.
	 * The template's text layers are skipped — a forty-pixel tile has no room for them.
	 */
	private void drawTile(Graphics2D g, Rectangle tile, CatalogueCard card, int quantity,
		boolean hovered, boolean chosen)
	{
		CardFace.backdrop(g, tile, card);
		BufferedImage front = cardFront;
		if (front != null)
		{
			g.drawImage(front, tile.x, tile.y, tile.width, tile.height, null);
		}
		else
		{
			g.setColor(OsrsSkin.SLOT_FILL);
			g.fillRect(tile.x, tile.y, tile.width, tile.height);
		}

		boolean has = quantity > 0;
		if (!has)
		{
			// The dim wash goes on the card face, UNDER the artwork — over it, the faded art would be
			// multiplied into invisibility, which is exactly a wall of identical faces again.
			g.setColor(DIM);
			g.fillRect(tile.x, tile.y, tile.width, tile.height);
		}

		BufferedImage art = cardArt.imageFor(card);
		if (art != null)
		{
			// A card you haven't collected still shows what it is, faded — you can see what you're
			// hunting rather than a wall of identical faces.
			Composite oldComposite = g.getComposite();
			if (!has)
			{
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
			}
			// Fit inside the template's art window, keeping the artwork's own proportions.
			int maxW = tile.width - 8;
			int maxH = Math.max(1, (int) (tile.height * CardFace.WINDOW_HEIGHT) - 4);
			int w = art.getWidth();
			int h = art.getHeight();
			double scale = Math.min(maxW / (double) w, maxH / (double) h);
			int drawW = Math.max(1, (int) (w * scale));
			int drawH = Math.max(1, (int) (h * scale));
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(art, tile.x + (tile.width - drawW) / 2,
				tile.y + (int) (tile.height * CardFace.WINDOW_CENTRE_Y) - drawH / 2,
				drawW, drawH, null);
			g.setComposite(oldComposite);
		}
		if (quantity > 1)
		{
			OsrsSkin.text(g, "x" + quantity, FontManager.getRunescapeSmallFont(), OsrsSkin.YELLOW,
				tile.x + 3, tile.y + tile.height - 3);
		}

		g.setColor(has ? card.getTierColour() : OsrsSkin.BORDER_DARK);
		g.drawRect(tile.x, tile.y, tile.width - 1, tile.height - 1);
		if (card.isSpecial())
		{
			// Curated specials wear gold over their tier frame — a corner notch, kept small so the
			// artwork stays the loudest thing on the tile.
			g.setColor(SPECIAL_GOLD);
			g.drawRect(tile.x + 1, tile.y + 1, tile.width - 3, tile.height - 3);
			g.fillPolygon(
				new int[]{tile.x + tile.width - 9, tile.x + tile.width - 2, tile.x + tile.width - 2},
				new int[]{tile.y + 2, tile.y + 2, tile.y + 9}, 3);
		}
		if (chosen)
		{
			g.setColor(OsrsSkin.ORANGE);
			g.drawRect(tile.x + 1, tile.y + 1, tile.width - 3, tile.height - 3);
		}
		else if (hovered)
		{
			// Uncollected cards highlight too — they open their detail view like any other.
			g.setColor(new Color(255, 255, 255, 40));
			g.fillRect(tile.x + 1, tile.y + 1, tile.width - 2, tile.height - 2);
		}
	}

	private void drawFooter(Graphics2D g, Layout l)
	{
		Font font = FontManager.getRunescapeSmallFont();
		CatalogueCard chosen = selected;
		String line;
		Color colour = OsrsSkin.MUTED;

		if (!status.isEmpty())
		{
			line = status;
		}
		else if (chosen != null)
		{
			int quantity = owned.getOrDefault(chosen.getId(), 0);
			line = chosen.getName() + " · " + CatalogueCard.Tiers.label(chosen.getTier())
				+ (quantity > 1 ? " ×" + quantity : "");
			colour = chosen.getTierColour();
		}
		else if (tradePicker != null)
		{
			line = shown.size() + " duplicates to trade";
		}
		else
		{
			line = owned.size() + " / " + catalogue.size() + " cards · " + formatCredits(credits) + " credits";
		}
		OsrsSkin.centred(g, OsrsSkin.ellipsise(g, line, font, l.window.width - 12), font, colour,
			l.window.x + l.window.width / 2, l.footerBaseline());
	}

	// ── Placement ─────────────────────────────────────────────────────────────

	/**
	 * Where to sit: over the inventory / skills tab area, in whatever layout the client is in — fixed,
	 * resizable, resizable-with-the-bottom-line, stretched or scaled. The item container is the tightest
	 * anchor but only exists while the inventory tab is up, so this walks outwards — item container, then
	 * the tab panel that holds whichever tab is open, then the last place we sat, and finally a slot
	 * against the right edge of the game area so the interface is never stranded off-screen.
	 *
	 * <p>All of these are widget bounds in game space, which is the same space overlays paint in, so the
	 * anchor holds when the client is stretched or scaled. The player's alt-drag offset is added last.
	 */
	private Rectangle panelBounds()
	{
		Rectangle anchor = null;

		for (int component : new int[]{
			ComponentID.INVENTORY_CONTAINER,
			ComponentID.FIXED_VIEWPORT_INVENTORY_CONTAINER,
			ComponentID.RESIZABLE_VIEWPORT_INVENTORY_CONTAINER,
			ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_CONTAINER})
		{
			Rectangle bounds = visibleBounds(component);
			if (bounds != null)
			{
				anchor = new Rectangle(
					bounds.x - INVENTORY_MARGIN_X,
					bounds.y - INVENTORY_MARGIN_Y,
					bounds.width + INVENTORY_MARGIN_X * 2,
					bounds.height + INVENTORY_MARGIN_Y * 2);
				break;
			}
		}

		if (anchor == null)
		{
			// The tab panel itself — present whichever tab is open, so switching to Skills keeps us put.
			for (int component : new int[]{
				ComponentID.FIXED_VIEWPORT_INTERFACE_CONTAINER,
				ComponentID.RESIZABLE_VIEWPORT_INVENTORY_PARENT,
				ComponentID.RESIZABLE_VIEWPORT_BOTTOM_LINE_INVENTORY_PARENT})
			{
				Rectangle bounds = visibleBounds(component);
				if (bounds != null && looksLikeTabPanel(bounds))
				{
					anchor = bounds;
					break;
				}
			}
		}

		if (anchor != null)
		{
			lastInventory = anchor;
		}
		else
		{
			anchor = lastInventory != null ? new Rectangle(lastInventory) : rightEdgeFallback();
		}

		Rectangle panel = new Rectangle(anchor.x + dragX, anchor.y + dragY, anchor.width, anchor.height);
		Dimension game = client.getRealDimensions();
		int gameW = game != null ? game.width : client.getCanvasWidth();
		int gameH = game != null ? game.height : client.getCanvasHeight();
		OsrsSkin.clampInto(panel, gameW, gameH);
		// Absorb the clamp, so dragging back off an edge responds immediately.
		dragX = panel.x - anchor.x;
		dragY = panel.y - anchor.y;
		return panel;
	}

	/** Guard against anchoring to something that clearly isn't the side panel (a full-screen container). */
	private static boolean looksLikeTabPanel(Rectangle bounds)
	{
		return bounds.width >= 120 && bounds.width <= 340 && bounds.height >= 150 && bounds.height <= 460;
	}

	/** Last resort: an inventory-sized slot against the right edge of the game area. */
	private Rectangle rightEdgeFallback()
	{
		Dimension game = client.getRealDimensions();
		int gameW = game != null ? game.width : client.getCanvasWidth();
		int gameH = game != null ? game.height : client.getCanvasHeight();
		int width = 190;
		int height = 261;
		return new Rectangle(
			Math.max(0, gameW - width - 8),
			Math.max(0, (gameH - height) / 2),
			width, height);
	}

	@Nullable
	private Rectangle visibleBounds(int component)
	{
		Widget widget = client.getWidget(component);
		if (widget == null || widget.isHidden())
		{
			return null;
		}
		Rectangle bounds = widget.getBounds();
		return bounds == null || bounds.width <= 0 || bounds.height <= 0 ? null : bounds;
	}

	/** Where the last painted frame put everything, in canvas coordinates. */
	private static final class Layout
	{
		private final Rectangle window;
		private final Rectangle close;
		private final Rectangle search;
		private final Rectangle list;
		private final int tileW;
		private final int tileH;

		private Layout(Rectangle bounds)
		{
			window = bounds;
			close = new Rectangle(window.x + window.width - PAD - CLOSE_SIZE,
				window.y + (TITLE_H - CLOSE_SIZE) / 2 + 1, CLOSE_SIZE, CLOSE_SIZE);

			search = new Rectangle(window.x + PAD, window.y + TITLE_H + 3, window.width - PAD * 2, SEARCH_H);

			// Four to a row, at Old School card proportions, with room for the scrollbar.
			int usable = window.width - PAD * 2 - 4 - SCROLLBAR_W - (COLUMNS - 1) * TILE_GAP;
			tileW = Math.max(12, usable / COLUMNS);
			tileH = (int) Math.round(tileW * CARD_RATIO);

			// Hold whole rows: with scrolling snapped to a row, no card is ever sliced in half.
			int listY = search.y + SEARCH_H + 3;
			int available = window.y + window.height - PAD - FOOTER_H - listY;
			int visibleRows = Math.max(1, (available - 4) / (tileH + TILE_GAP));
			list = new Rectangle(window.x + PAD, listY, window.width - PAD * 2,
				Math.min(available, visibleRows * (tileH + TILE_GAP) - TILE_GAP + 4));
		}

		private Rectangle scrollTrack()
		{
			return new Rectangle(list.x + list.width - 1 - SCROLLBAR_W, list.y + 1, SCROLLBAR_W, list.height - 2);
		}

		private int footerBaseline()
		{
			return list.y + list.height + FOOTER_H - 5;
		}
	}

	private static Rectangle thumbBounds(Layout l, int contentH, int viewH, int offset)
	{
		Rectangle track = l.scrollTrack();
		int thumbH = Math.max(16, (int) ((long) track.height * viewH / contentH));
		int span = track.height - thumbH;
		int max = contentH - viewH;
		int y = track.y + (max <= 0 ? 0 : (int) ((long) span * offset / max));
		return new Rectangle(track.x, y, track.width, thumbH);
	}

	/** Clamps to the scrollable range and snaps to a row, so rows always land whole. */
	private static int clampScroll(int value, int contentH, int viewH, int rowH)
	{
		int snapped = Math.round(value / (float) rowH) * rowH;
		return Math.max(0, Math.min(snapped, Math.max(0, contentH - viewH)));
	}

	private int rowHeight()
	{
		Layout l = layout;
		// Without a layout there is nothing to scroll; nudgeScroll bails on the same null anyway.
		return l != null ? l.tileH + TILE_GAP : 0;
	}

	// ── Input ─────────────────────────────────────────────────────────────────

	/** Mouse over the interface: drives the controls and is then swallowed, never reaching the game. */
	private final class MouseHandler extends MouseAdapter implements MouseWheelListener
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
				// Alt-drag moves the interface; the controls under the cursor stay untouched.
				grab = new Point(event.getPoint().x - l.window.x, event.getPoint().y - l.window.y);
			}
			else
			{
				press(event.getPoint());
			}
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			if (grab != null)
			{
				mouse = event.getPoint();
				dragWindow(event.getPoint());
				event.consume();
				return event;
			}
			if (thumbGrabDy >= 0)
			{
				mouse = event.getPoint();
				dragThumb(event.getPoint());
				event.consume();
				return event;
			}
			return swallow(event);
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent event)
		{
			thumbGrabDy = -1;
			grab = null;
			return swallow(event);
		}

		@Override
		public MouseEvent mouseClicked(MouseEvent event)
		{
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
			if (!open || l == null || !l.window.contains(event.getPoint()))
			{
				return event;
			}
			int rowH = l.tileH + TILE_GAP;
			int rows = (shown.size() + COLUMNS - 1) / COLUMNS;
			scroll = clampScroll(scroll + event.getWheelRotation() * rowH, rows * rowH,
				l.list.height - 2, rowH);
			event.consume();
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

	private void press(Point point)
	{
		Layout l = layout;
		if (l == null)
		{
			return;
		}
		if (tradePicker == null && l.close.contains(point))
		{
			close();
			return;
		}

		List<CatalogueCard> cards = shown;
		int rowH = l.tileH + TILE_GAP;
		int rows = (cards.size() + COLUMNS - 1) / COLUMNS;
		int viewH = l.list.height - 2;
		int contentH = rows * rowH;

		if (contentH > viewH && l.scrollTrack().contains(point))
		{
			Rectangle thumb = thumbBounds(l, contentH, viewH, scroll);
			thumbGrabDy = thumb.contains(point) ? point.y - thumb.y : thumb.height / 2;
			dragThumb(point);
			return;
		}
		if (l.list.contains(point) && !cards.isEmpty())
		{
			int row = (point.y - l.list.y - 2 + scroll) / rowH;
			int col = (point.x - l.list.x - 2) / (l.tileW + TILE_GAP);
			if (col < 0 || col >= COLUMNS)
			{
				return;
			}
			int index = row * COLUMNS + col;
			if (index < 0 || index >= cards.size())
			{
				return;
			}
			CatalogueCard card = cards.get(index);
			TradePicker picker = tradePicker;
			log.debug("Card {} clicked (trade mode: {})", card.getId(), picker != null);
			if (picker != null)
			{
				// In a trade only cards you actually hold can go on the table.
				if (owned.getOrDefault(card.getId(), 0) <= 0)
				{
					return;
				}
				// A click puts the card up (or takes it back down) rather than selecting it.
				picker.onPick(card);
				return;
			}
			// Outside a trade every card opens, collected or not: a card you're still hunting is worth
			// reading up on, and the detail view says "Not collected" rather than a count.

			long now = System.currentTimeMillis();
			if (lastClickedId == card.getId() && now - lastClickMs <= DOUBLE_CLICK_MS)
			{
				// Second click on the same card: show it properly, at a size you can actually read.
				lastClickedId = 0;
				detail.show(card, owned.getOrDefault(card.getId(), 0), combineInfo(card));
				return;
			}
			lastClickedId = card.getId();
			lastClickMs = now;

			CatalogueCard chosen = selected;
			selected = chosen != null && chosen.getId() == card.getId() ? null : card;
		}
	}

	/**
	 * Alt-drag: shift the interface by however far the cursor has moved from where it grabbed. The offset
	 * is kept relative to the inventory anchor, so it holds when the client is resized.
	 */
	private void dragWindow(Point point)
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

	private void dragThumb(Point point)
	{
		Layout l = layout;
		if (l == null || thumbGrabDy < 0)
		{
			return;
		}
		int rowH = l.tileH + TILE_GAP;
		int viewH = l.list.height - 2;
		int contentH = ((shown.size() + COLUMNS - 1) / COLUMNS) * rowH;
		if (contentH <= viewH)
		{
			return;
		}
		Rectangle track = l.scrollTrack();
		int thumbH = thumbBounds(l, contentH, viewH, scroll).height;
		int span = track.height - thumbH;
		if (span <= 0)
		{
			return;
		}
		int thumbY = Math.max(track.y, Math.min(point.y - thumbGrabDy, track.y + span));
		scroll = clampScroll((int) ((long) (thumbY - track.y) * (contentH - viewH) / span),
			contentH, viewH, rowH);
	}

	/**
	 * While the interface is open the search field owns the keyboard — every key is consumed so typing a
	 * card name can't walk the camera or land in the chat box. {@code Escape} hands control back.
	 */
	private final class KeyHandler implements KeyListener
	{
		@Override
		public void keyTyped(KeyEvent event)
		{
			if (!open)
			{
				return;
			}
			char typed = event.getKeyChar();
			if (typed >= ' ' && typed != KeyEvent.CHAR_UNDEFINED && query.length() < MAX_QUERY)
			{
				query = query + typed;
				applyFilter();
			}
			event.consume();
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			if (!open)
			{
				return;
			}
			switch (event.getKeyCode())
			{
				case KeyEvent.VK_ESCAPE:
					close();
					break;
				case KeyEvent.VK_BACK_SPACE:
					if (!query.isEmpty())
					{
						query = query.substring(0, query.length() - 1);
						applyFilter();
					}
					break;
				case KeyEvent.VK_UP:
					nudgeScroll(-rowHeight());
					break;
				case KeyEvent.VK_DOWN:
					nudgeScroll(rowHeight());
					break;
				default:
					break;
			}
			event.consume();
		}

		@Override
		public void keyReleased(KeyEvent event)
		{
			if (open)
			{
				event.consume();
			}
		}

		@Override
		public void focusLost()
		{
			thumbGrabDy = -1;
			grab = null;
		}
	}

	private void nudgeScroll(int delta)
	{
		Layout l = layout;
		if (l == null)
		{
			return;
		}
		int rowH = l.tileH + TILE_GAP;
		int rows = (shown.size() + COLUMNS - 1) / COLUMNS;
		scroll = clampScroll(scroll + delta, rows * rowH, l.list.height - 2, rowH);
	}

	/**
	 * The crafting story for the detail view (CARDS.md §8), resolved to names and ownership. The craft
	 * action itself is deliberately absent — whether crafting consumes the components is an open design
	 * decision — so this is the read-only half: what it's made of, and what it makes. Package-visible so
	 * the pack-opening ceremony can hand the same story to the detail view.
	 */
	@Nullable
	CardDetailWindow.CombineInfo combineInfo(CatalogueCard card)
	{
		if (card.getCraftedFrom().isEmpty() && card.getCombinesInto().isEmpty())
		{
			return null;
		}
		Map<Integer, Integer> held = owned;
		List<String> components = new ArrayList<>();
		int ownedComponents = 0;
		for (Integer id : card.getCraftedFrom())
		{
			CatalogueCard component = byId.get(id);
			if (component == null)
			{
				continue;
			}
			components.add(component.getName());
			if (held.getOrDefault(id, 0) > 0)
			{
				ownedComponents++;
			}
		}
		List<String> results = new ArrayList<>();
		for (Integer id : card.getCombinesInto())
		{
			CatalogueCard result = byId.get(id);
			if (result != null)
			{
				results.add(result.getName());
			}
		}
		return new CardDetailWindow.CombineInfo(components, ownedComponents, results);
	}

	private static boolean contains(Rectangle r, @Nullable Point p)
	{
		return p != null && r.contains(p);
	}

	/** Thousands separators — a five-figure balance is unreadable as a run of digits. */
	private static String formatCredits(int amount)
	{
		return String.format("%,d", amount);
	}
}
