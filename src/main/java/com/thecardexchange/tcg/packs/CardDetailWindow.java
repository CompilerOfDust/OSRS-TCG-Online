package com.thecardexchange.tcg.packs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPCComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import lombok.extern.slf4j.Slf4j;
import com.thecardexchange.tcg.FeatureGate;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.CharacterTracker;
import com.thecardexchange.tcg.account.ExchangeApiClient;
import com.thecardexchange.tcg.mode.GameMode;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * One card, big — opened by double-clicking it in the collection grid or in a pack reveal.
 *
 * <p>The grid's tiles are barely forty pixels across, so this is where a card is actually readable: its
 * artwork at size on the card-front template, its name in its gem tier, what it is, how many you hold,
 * and a link out to the Old School wiki page for the item or monster behind it.
 *
 * <p>Centred on the game scene and alt-draggable like the other painted windows, and it swallows the
 * mouse inside its bounds so clicking it never reaches the game.
 */
@Slf4j
@Singleton
public class CardDetailWindow extends Overlay
{
	private static final int WINDOW_W = 232;
	/** Tall enough that the card face carries its description at readable size (see {@link CardFace}). */
	private static final int WINDOW_H = 420;
	/** How many unlock names to resolve for the summary line — the line ellipsises anyway. */
	private static final int MAX_UNLOCK_NAMES = 10;
	private static final int TITLE_H = 24;
	private static final int PAD = 10;
	private static final int CLOSE_SIZE = 16;
	private static final int BUTTON_H = 22;

	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final CardArt cardArt;
	private final ItemManager itemManager;
	private final InputHandler input = new InputHandler();

	private volatile boolean open;
	@Nullable
	private volatile CatalogueCard card;
	private volatile int quantity;
	@Nullable
	private volatile CombineInfo combine;
	@Nullable
	private volatile Layout layout;
	@Nullable
	private volatile Point mouse;
	private volatile int dragX;
	private volatile int dragY;
	@Nullable
	private volatile Point grab;
	@Nullable
	private BufferedImage cardFront;

	private final CharacterTracker characterTracker;
	private final ExchangeApiClient api;
	private final AccountLinkManager linkManager;
	private final FeatureGate gate;
	private final Wallet wallet;
	private final ScheduledExecutorService scheduler;

	/** Set by {@link CardPacksInterface} so a sale can refresh the grid behind this window. */
	private volatile Runnable onCollectionChanged;
	/** True while a sale is in flight, so a double-click can't sell twice. */
	private final AtomicBoolean selling = new AtomicBoolean();
	/** What the last sale said, shown in place of the button's price for a moment. */
	private volatile String saleNotice;

	@Inject
	CardDetailWindow(Client client, OverlayManager overlayManager, MouseManager mouseManager,
		CardArt cardArt, ItemManager itemManager, CharacterTracker characterTracker,
		ExchangeApiClient api, AccountLinkManager linkManager, FeatureGate gate, Wallet wallet,
		ScheduledExecutorService scheduler)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.cardArt = cardArt;
		this.itemManager = itemManager;
		this.characterTracker = characterTracker;
		this.api = api;
		this.linkManager = linkManager;
		this.gate = gate;
		this.wallet = wallet;
		this.scheduler = scheduler;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// A shade above HIGHEST: this window opens over the plugin's other HIGHEST overlays (the
		// pack-opening ceremony, the collection view), and equal priority would leave the draw order to
		// registration order — which paints this one underneath.
		setPriority(Overlay.PRIORITY_HIGHEST + 0.1f);
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	void start()
	{
		cardFront = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/card_front.png");
		overlayManager.add(this);
		mouseManager.registerMouseListener(input);
	}

	void shutdown()
	{
		close();
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(input);
	}

	/**
	 * The crafting story for one card (CARDS.md §8), pre-resolved to names by the caller: what it is
	 * crafted from (and how many of those components are owned), and what it combines into.
	 */
	public static final class CombineInfo
	{
		private final java.util.List<String> components;
		private final int ownedComponents;
		private final java.util.List<String> results;

		public CombineInfo(java.util.List<String> components, int ownedComponents,
			java.util.List<String> results)
		{
			this.components = components;
			this.ownedComponents = ownedComponents;
			this.results = results;
		}
	}

	/** Show a card. Opening a different one while this is up just swaps the contents. */
	public void show(CatalogueCard card, int quantity, @Nullable CombineInfo combine)
	{
		this.card = card;
		this.quantity = quantity;
		this.combine = combine;
		this.open = true;
	}

	/**
	 * What to run once a sale has changed the collection — set by {@link CardPacksInterface} so the
	 * grid behind this window follows a sale.
	 *
	 * <p>A setter rather than constructor injection because the two windows own each other: the grid
	 * opens this one, and this one has to tell the grid when it changed something.
	 */
	public void setOnCollectionChanged(@Nullable Runnable listener)
	{
		this.onCollectionChanged = listener;
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
		CatalogueCard shown = card;
		if (!open || shown == null || client.getGameState() != GameState.LOGGED_IN)
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

		OsrsSkin.frame(g, l.window);
		OsrsSkin.titleStrip(g, l.window, TITLE_H);
		OsrsSkin.centred(g, OsrsSkin.ellipsise(g, shown.getName(), FontManager.getRunescapeBoldFont(),
				l.window.width - 40), FontManager.getRunescapeBoldFont(), shown.getTierColour(),
			l.window.x + l.window.width / 2, l.window.y + TITLE_H - 7);
		OsrsSkin.closeButton(g, l.close, contains(l.close, hover));

		drawFace(g, l, shown);
		drawFacts(g, l, shown);

		drawSell(g, l, shown, hover);

		boolean hot = contains(l.wiki, hover);
		OsrsSkin.plate(g, l.wiki, hot);
		OsrsSkin.centred(g, "Open Wiki", FontManager.getRunescapeFont(),
			hot ? OsrsSkin.ORANGE : OsrsSkin.TEXT, l.wiki.x + l.wiki.width / 2, l.wiki.y + 15);

		g.setClip(oldClip);
		g.setStroke(oldStroke);
		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
		return null;
	}

	/**
	 * The "Sell spare" button — drawn only when there is genuinely a spare to sell.
	 *
	 * <p>Hidden rather than greyed at one copy, because the answer is never going to be yes for a card
	 * you own one of, and a permanently dead control on a window most players open to read flavour text
	 * is just noise. It is also hidden while the character can't play at all, for the same reason the
	 * pack orb is: offering an action the server will refuse makes the hold look unreal.
	 */
	private void drawSell(Graphics2D g, Layout l, CatalogueCard shown, @Nullable Point hover)
	{
		String notice = saleNotice;
		if (notice != null)
		{
			OsrsSkin.plate(g, l.sell, false);
			OsrsSkin.centred(g, notice, FontManager.getRunescapeFont(), OsrsSkin.GOOD,
				l.sell.x + l.sell.width / 2, l.sell.y + 15);
			return;
		}
		if (!isSellable(shown))
		{
			return;
		}
		boolean hot = contains(l.sell, hover);
		OsrsSkin.plate(g, l.sell, hot);
		int value = wallet.saleValue(shown.getTier());
		OsrsSkin.centred(g, "Sell spare  (" + String.format("%,d", value) + ")",
			FontManager.getRunescapeFont(), hot ? OsrsSkin.ORANGE : OsrsSkin.TEXT,
			l.sell.x + l.sell.width / 2, l.sell.y + 15);
	}

	/**
	 * Whether this card has a copy to spare.
	 *
	 * <p>The floor of one copy is the server's rule and the server enforces it — the item lock is
	 * derived from cards held, so selling out of a card would take away a game item. This is only what
	 * decides whether to draw the button.
	 */
	private boolean isSellable(CatalogueCard shown)
	{
		return shown != null
			&& quantity >= 2
			&& wallet.saleValue(shown.getTier()) > 0
			&& gate.isPlayable()
			&& linkManager.getToken() != null;
	}

	/** Sells one spare copy, off the render thread, and refreshes what the sale changed. */
	private void sellOne()
	{
		CatalogueCard shown = card;
		String token = linkManager.getToken();
		if (shown == null || token == null || !selling.compareAndSet(false, true))
		{
			return;
		}
		scheduler.execute(() ->
		{
			try
			{
				ExchangeApiClient.SaleResult sale =
					api.sellCard(token, characterTracker.getCurrentRsn(), shown.getId(), 1);
				// Straight off the response, so the orb's readiness pip and the footer follow the
				// sale without waiting for the next heartbeat.
				wallet.apply(sale.getBalance(), wallet.getPackPrice());
				quantity = sale.getRemaining();
				saleNotice = "+" + String.format("%,d", sale.getCredits()) + " credits";
				scheduler.schedule(() -> saleNotice = null, 2, TimeUnit.SECONDS);
				Runnable listener = onCollectionChanged;
				if (listener != null)
				{
					listener.run();
				}
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Could not sell a card", ex);
				saleNotice = "Couldn't sell that";
				scheduler.schedule(() -> saleNotice = null, 2, TimeUnit.SECONDS);
			}
			finally
			{
				selling.set(false);
			}
		});
	}

	/**
	 * The card itself, painted by {@link CardFace}: the front template over a tier-coloured backdrop,
	 * with the artwork in its window. The template frame replaces the old painted tier border — the
	 * tier now reads from the window's backdrop colour and the label on the parchment box.
	 */
	private void drawFace(Graphics2D g, Layout l, CatalogueCard shown)
	{
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		CardFace.paint(g, l.face, cardFront, cardArt.imageFor(shown), shown, isCardman());
	}

	private void drawFacts(Graphics2D g, Layout l, CatalogueCard shown)
	{
		Font small = FontManager.getRunescapeSmallFont();
		int x = l.window.x + PAD;
		int y = l.face.y + l.face.height + 13;

		OsrsSkin.text(g, CatalogueCard.Tiers.label(shown.getTier()), small, shown.getTierColour(), x, y);
		OsrsSkin.text(g, shown.isNpc() ? "Monster" : "Item", small, OsrsSkin.MUTED,
			x + 70, y);
		if (shown.isSpecial())
		{
			OsrsSkin.text(g, "Special", small, CardPacksInterface.SPECIAL_GOLD, x + 130, y);
		}

		int held = quantity;
		String owned = held <= 0 ? "Not collected" : held == 1 ? "1 copy" : held + " copies";
		OsrsSkin.text(g, owned, small, held > 1 ? OsrsSkin.YELLOW : OsrsSkin.TEXT, x, y + 12);
		if (held > 1)
		{
			OsrsSkin.text(g, "tradeable", small, new Color(120, 220, 120), x + 70, y + 12);
		}

		OsrsSkin.text(g, "Card #" + shown.getId(), small, OsrsSkin.MUTED, x, y + 24);

		// The crafting story, when the card has one. Read-only: the craft action itself is a pending
		// design decision (does crafting consume the components?), so nothing here is clickable.
		CombineInfo info = combine;
		int lineY = y + 38;
		int width = l.window.width - PAD * 2;
		if (info != null && !info.components.isEmpty())
		{
			boolean complete = info.ownedComponents >= info.components.size();
			OsrsSkin.text(g, "Craft from " + info.components.size() + " cards — "
					+ info.ownedComponents + " owned", small,
				complete ? new Color(120, 220, 120) : OsrsSkin.YELLOW, x, lineY);
			OsrsSkin.text(g, OsrsSkin.ellipsise(g, String.join(", ", info.components), small, width),
				small, OsrsSkin.MUTED, x, lineY + 11);
			lineY += 24;
		}
		if (info != null && !info.results.isEmpty())
		{
			OsrsSkin.text(g, OsrsSkin.ellipsise(g, "Combines into: " + String.join(", ", info.results),
				small, width), small, OsrsSkin.ORANGE, x, lineY);
			lineY += 12;
		}

		// What owning this card unlocks in game — the haul of a cluster master (big_unlocks.md).
		// Names come from the client's own definitions, resolved here because render runs on the
		// client thread; most cards unlock nothing beyond themselves and skip this entirely.
		List<Integer> unlockIds = shown.getUnlocksItems();
		if (!unlockIds.isEmpty())
		{
			OsrsSkin.text(g, "Unlocks " + unlockIds.size() + (shown.isNpc() ? " monsters" : " items"),
				small, new Color(120, 220, 120), x, lineY);
			OsrsSkin.text(g, OsrsSkin.ellipsise(g, unlockNames(shown, unlockIds), small, width),
				small, OsrsSkin.MUTED, x, lineY + 11);
		}
	}

	/**
	 * The first few unlock names, deduplicated — an id range like Crawling Hand 448-457 is one name ten
	 * times over. Capped: the line ellipsises anyway, so resolving hundreds of definitions per frame
	 * would buy nothing.
	 */
	private String unlockNames(CatalogueCard card, List<Integer> ids)
	{
		Set<String> names = new LinkedHashSet<>();
		for (int i = 0; i < ids.size() && names.size() < MAX_UNLOCK_NAMES; i++)
		{
			int id = ids.get(i);
			String name;
			if (card.isNpc())
			{
				NPCComposition npc = client.getNpcDefinition(id);
				name = npc != null ? npc.getName() : null;
			}
			else
			{
				name = itemManager.getItemComposition(id).getName();
			}
			if (name != null && !name.isEmpty() && !"null".equals(name))
			{
				names.add(name);
			}
		}
		String joined = String.join(", ", names);
		return ids.size() > MAX_UNLOCK_NAMES ? joined + ", …" : joined;
	}

	// ── Placement ─────────────────────────────────────────────────────────────

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
		dragX = window.x - naturalX;
		dragY = window.y - naturalY;
		return window;
	}

	private static final class Layout
	{
		private final Rectangle window;
		private final Rectangle close;
		private final Rectangle face;
		private final Rectangle wiki;
		private final Rectangle sell;

		private Layout(Rectangle bounds)
		{
			window = bounds;
			close = new Rectangle(window.x + window.width - PAD - CLOSE_SIZE,
				window.y + (TITLE_H - CLOSE_SIZE) / 2, CLOSE_SIZE, CLOSE_SIZE);
			// The front template's own proportions, as tall as the space above the facts and the button
			// allows — the facts get 132px, enough for the crafting story and the unlocks line together.
			//
			// The sell row is reserved whether or not it is drawn. A card becoming
			// sellable — a duplicate arriving from a pack while this window is open —
			// must not resize the artwork underneath it.
			int faceH = window.height - TITLE_H - (BUTTON_H * 2 + 4) - 132;
			int faceW = (int) Math.round(faceH / CardFace.RATIO);
			face = new Rectangle(window.x + (window.width - faceW) / 2, window.y + TITLE_H + 8, faceW, faceH);
			wiki = new Rectangle(window.x + PAD, window.y + window.height - PAD - BUTTON_H,
				window.width - PAD * 2, BUTTON_H);
			sell = new Rectangle(wiki.x, wiki.y - BUTTON_H - 4, wiki.width, BUTTON_H);
		}
	}

	// ── Input ─────────────────────────────────────────────────────────────────

	private final class InputHandler extends MouseAdapter
	{
		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			Layout l = layout;
			if (!open || l == null || !l.window.contains(event.getPoint()))
			{
				return event;
			}
			mouse = event.getPoint();
			if (event.isAltDown())
			{
				grab = new Point(event.getPoint().x - l.window.x, event.getPoint().y - l.window.y);
			}
			else if (l.close.contains(event.getPoint()))
			{
				close();
			}
			else if (l.sell.contains(event.getPoint()) && saleNotice == null
				&& card != null && isSellable(card))
			{
				sellOne();
			}
			else if (l.wiki.contains(event.getPoint()))
			{
				CatalogueCard shown = card;
				if (shown != null)
				{
					LinkBrowser.browse(shown.getWikiUrl());
				}
			}
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			Layout l = layout;
			Point held = grab;
			if (l != null && held != null)
			{
				mouse = event.getPoint();
				dragX += event.getPoint().x - held.x - l.window.x;
				dragY += event.getPoint().y - held.y - l.window.y;
				event.consume();
				return event;
			}
			return swallow(event);
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

		private MouseEvent swallow(MouseEvent event)
		{
			Layout l = layout;
			if (open && l != null && l.window.contains(event.getPoint()))
			{
				event.consume();
			}
			return event;
		}
	}

	private static boolean contains(Rectangle r, @Nullable Point p)
	{
		return p != null && r.contains(p);
	}

	/** True when this character plays CardMan — its cards carry the silvery-rose wash. */
	private boolean isCardman()
	{
		return characterTracker.activeGameMode() == GameMode.CARDMAN;
	}
}
