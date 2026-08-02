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
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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
import com.thecardexchange.tcg.mode.GameMode;
import com.thecardexchange.tcg.account.ExchangeApiClient;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * The pack-opening ceremony — opened from the {@link PackOpeningOrb} on the left of the screen. A dark
 * scrim settles over the viewport and the pack sits in the middle at full size; clicking it asks the api
 * to open one (the server charges and decides the contents), and the pulls land face down in two rows,
 * two cards over three. Clicking a card turns it over onto the card-front template (see
 * {@link CardFace}) — its artwork over a tier-coloured backdrop, its name in the banner, its description
 * on the text box, its tier on the bottom strip, NEW or the copy count hanging under the card.
 * Double-clicking a revealed card opens the {@link CardDetailWindow} over the ceremony, and once every
 * card is up, Continue puts the pack back on the table for another go.
 *
 * <p>Modal while it's up: every mouse and key event is swallowed so a click can't reach the game through
 * the scrim ({@code Escape} closes). The collection view and the item locks are refreshed after each
 * pack, since both change under a new pull.
 */
@Slf4j
@Singleton
public class PackOpeningInterface extends Overlay
{
	private static final int CLOSE_SIZE = 22;
	private static final int CARD_GAP = 14;
	/** Height over width of the card assets (the front template and the back are both 2:3). */
	private static final double CARD_RATIO = CardFace.RATIO;
	/** How far a face-down card lifts under the cursor. */
	private static final int HOVER_LIFT = 5;
	/** How much the pack swells under the cursor, as a fraction of its size. */
	private static final double PACK_HOVER_GROW = 0.05;
	/** Two clicks on the same revealed card inside this window open the detail view. */
	private static final long DOUBLE_CLICK_MS = 400L;
	/** How far each halo ring of a radiance steps out from the card edge. */
	private static final int GLOW_STEP = 3;

	private static final Color SCRIM = new Color(0, 0, 0, 140);
	private static final Color NEW_BADGE = new Color(120, 220, 120);

	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final ExchangeApiClient api;
	private final AccountLinkManager linkManager;
	private final CharacterTracker characterTracker;
	private final CardArt cardArt;
	private final CardPacksInterface packs;
	private final CardDetailWindow detail;
	private final CardSounds sounds;
	private final Wallet wallet;
	private final ScheduledExecutorService scheduler;

	private final MouseHandler mouseHandler = new MouseHandler();
	private final KeyHandler keyHandler = new KeyHandler();
	/** One request at a time: no double-charging by double-clicking the pack. */
	private final AtomicBoolean busy = new AtomicBoolean();

	private volatile boolean open;
	private volatile String status = "";
	/** The pack being revealed, or null while the unopened pack is on the table. */
	@Nullable
	private volatile List<PackResult.PulledCard> pull;
	/** One flag per pulled card: turned over yet? Replaced whole with each pack. */
	@Nullable
	private volatile boolean[] revealed;
	/** Last revealed card clicked and when, so a second click reads as a double-click. */
	private volatile int lastClickedIndex = -1;
	private volatile long lastClickMs;
	/** Which card the cursor is on, so the hover tick fires once on arrival rather than every frame. */
	private volatile int hoveredIndex = -1;

	@Nullable
	private volatile Layout layout;
	@Nullable
	private volatile Point mouse;
	@Nullable
	private BufferedImage packImage;
	@Nullable
	private BufferedImage cardBack;
	@Nullable
	private BufferedImage cardFront;

	@Inject
	PackOpeningInterface(
		Client client,
		OverlayManager overlayManager,
		MouseManager mouseManager,
		KeyManager keyManager,
		ExchangeApiClient api,
		AccountLinkManager linkManager,
		CharacterTracker characterTracker,
		CardArt cardArt,
		CardPacksInterface packs,
		CardDetailWindow detail,
		CardSounds sounds,
		Wallet wallet,
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
		this.packs = packs;
		this.detail = detail;
		this.sounds = sounds;
		this.wallet = wallet;
		this.scheduler = scheduler;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	void start()
	{
		packImage = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/pack_standard.png");
		cardBack = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/card_back.png");
		cardFront = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/card_front.png");
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouseHandler);
		mouseManager.registerMouseWheelListener(mouseHandler);
		keyManager.registerKeyListener(keyHandler);
	}

	void shutdown()
	{
		close();
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouseHandler);
		mouseManager.unregisterMouseWheelListener(mouseHandler);
		keyManager.unregisterKeyListener(keyHandler);
	}

	public boolean isOpen()
	{
		return open;
	}

	/** Orb click: bring the pack out or put it away. */
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

	public void open()
	{
		open = true;
		status = "";
		refreshWallet();
		// Ask the server for a fresh balance too, not just the cached one: this is
		// the moment a stale number is actually seen, and boss-kill credits are
		// paid hourly server-side with no client event to notice them.
		characterTracker.onWalletViewed();
	}

	public void close()
	{
		open = false;
		layout = null;
		mouse = null;
		pull = null;
		revealed = null;
		lastClickedIndex = -1;
		hoveredIndex = -1;
	}

	// ── Data ──────────────────────────────────────────────────────────────────

	/** The wallet and the price, so the pack stage can say what a pack costs before the first open. */
	private void refreshWallet()
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
				Holdings holdings = api.collection(token, characterTracker.getCurrentRsn());
				wallet.apply(holdings);
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Could not load the wallet", ex);
				status = "Couldn't reach the Card Exchange";
			}
		});
	}

	/** Buys and opens one pack. The server charges and decides; this just deals the cards face down. */
	private void openPack()
	{
		String token = linkManager.getToken();
		if (token == null || !busy.compareAndSet(false, true))
		{
			return;
		}
		// On the click, not on the server's answer — the pack should tear the moment you touch it.
		sounds.packOpening();
		status = "";
		scheduler.execute(() ->
		{
			try
			{
				PackResult result = api.openPack(token, characterTracker.getCurrentRsn());
				// The charged balance, straight off the response — so the orb's readiness pip goes
				// out the instant the last affordable pack is bought, with no extra round trip.
				wallet.apply(result.getCredits(), wallet.getPackPrice());
				// The daily milestone, on the open that crossed it. Said in chat rather than
				// drawn in the window: the window is about to fill with cards, and the bonus
				// should still be there to read once it closes.
				if (result.getBonusCredits() > 0)
				{
					characterTracker.announce("+" + String.format("%,d", result.getBonusCredits())
						+ " credits — " + result.getBonusMilestone() + " packs today.");
				}
				// The flags go in before the pull: a frame that sees the cards must see their state too.
				revealed = new boolean[result.getCards().size()];
				pull = Collections.unmodifiableList(new ArrayList<>(result.getCards()));
				// The cursor is wherever it was left; forget it so arriving on a card ticks afresh.
				hoveredIndex = -1;
				// Now that the contents are known, the pack can announce what it was hiding.
				sounds.packContents(result.getCards());
				// New cards mean a changed collection and newly unlocked items — the collection view
				// re-reads both from the server.
				packs.refreshData();
			}
			catch (ExchangeApiClient.NotEnoughCredits ex)
			{
				wallet.apply(ex.getCredits(), ex.getPackPrice());
				status = "Not enough credits";
			}
			catch (ExchangeApiClient.CharacterUnderReview ex)
			{
				// Say why, rather than a generic failure — the panel carries the
				// full explanation, this is the one-line version at the point of use.
				status = "Under review — don't play this account";
			}
			catch (ExchangeApiClient.CharacterNotBound ex)
			{
				status = "Linking this character…";
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Could not open a pack", ex);
				status = "Couldn't open a pack";
			}
			finally
			{
				busy.set(false);
			}
		});
	}

	private boolean allRevealed()
	{
		boolean[] flags = revealed;
		if (flags == null)
		{
			return false;
		}
		for (boolean flag : flags)
		{
			if (!flag)
			{
				return false;
			}
		}
		return true;
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

		Layout l = new Layout(viewport(), pull, packImage, aspectCorrection());
		layout = l;
		Point hover = mouse;

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		Stroke oldStroke = g.getStroke();
		Shape oldClip = g.getClip();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setStroke(new BasicStroke(1f));

		g.setColor(SCRIM);
		g.fillRect(l.viewport.x, l.viewport.y, l.viewport.width, l.viewport.height);
		OsrsSkin.closeButton(g, l.close, contains(l.close, hover));

		List<PackResult.PulledCard> cards = pull;
		if (cards == null)
		{
			drawPackStage(g, l, hover);
		}
		else
		{
			drawReveal(g, l, cards, hover);
		}

		g.setClip(oldClip);
		g.setStroke(oldStroke);
		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
		return null;
	}

	/** The unopened pack, centred and full size, with the wallet underneath. */
	private void drawPackStage(Graphics2D g, Layout l, @Nullable Point hover)
	{
		int centreX = l.viewport.x + l.viewport.width / 2;
		OsrsSkin.centred(g, "Standard Pack", FontManager.getRunescapeBoldFont(), OsrsSkin.ORANGE,
			centreX, l.pack.y - 12);

		boolean hot = contains(l.pack, hover) && !busy.get();
		// Under the cursor the pack swells a touch from its centre — reaching for you, not sliding away.
		Rectangle drawn = l.pack;
		if (hot)
		{
			int growW = (int) (l.pack.width * PACK_HOVER_GROW);
			int growH = (int) (l.pack.height * PACK_HOVER_GROW);
			drawn = new Rectangle(l.pack.x - growW / 2, l.pack.y - growH / 2,
				l.pack.width + growW, l.pack.height + growH);
		}
		BufferedImage image = packImage;
		if (image != null)
		{
			g.drawImage(image, drawn.x, drawn.y, drawn.width, drawn.height, null);
			if (hot)
			{
				// A warm wash rather than a border: the artwork already has its own edge.
				g.setColor(new Color(255, 152, 31, 40));
				g.fillRect(drawn.x, drawn.y, drawn.width, drawn.height);
			}
		}
		else
		{
			OsrsSkin.plate(g, drawn, hot);
		}

		Font small = FontManager.getRunescapeSmallFont();
		int y = l.pack.y + l.pack.height + 18;
		int credits = wallet.getCredits();
		int packPrice = wallet.getPackPrice();
		if (credits >= 0)
		{
			String line = formatCredits(credits) + " credits"
				+ (packPrice > 0 ? " · " + formatCredits(packPrice) + " a pack" : "");
			OsrsSkin.centred(g, line, small, OsrsSkin.YELLOW, centreX, y);
		}
		// Unknown reads as affordable here, deliberately the opposite of Wallet.canOpenPack():
		// greying out a button that would have worked is worse than letting the click through,
		// whereas a badge promising a pack you cannot buy is worse than no badge. See that method.
		boolean affordable = packPrice <= 0 || credits < 0 || credits >= packPrice;
		String hint = busy.get() ? "Opening…" : affordable ? "Click the pack to open it" : "Not enough credits";
		OsrsSkin.centred(g, hint, small, affordable ? OsrsSkin.TEXT : OsrsSkin.MUTED, centreX, y + 14);
		if (!status.isEmpty())
		{
			OsrsSkin.centred(g, status, small, OsrsSkin.ORANGE, centreX, y + 28);
		}
	}

	/** The dealt pack: each card face down until clicked, and Continue once every card is up. */
	private void drawReveal(Graphics2D g, Layout l, List<PackResult.PulledCard> cards, @Nullable Point hover)
	{
		boolean[] flags = revealed;
		boolean all = true;
		for (int i = 0; i < l.cards.size() && i < cards.size(); i++)
		{
			Rectangle rect = l.cards.get(i);
			boolean up = flags != null && i < flags.length && flags[i];
			if (up)
			{
				drawFace(g, rect, cards.get(i), contains(rect, hover));
			}
			else
			{
				all = false;
				drawBack(g, rect, cards.get(i).getCard().getTierColour(), contains(rect, hover));
			}
		}

		if (!l.cards.isEmpty())
		{
			int centreX = l.viewport.x + l.viewport.width / 2;
			OsrsSkin.centred(g, all ? "Your pull" : "Click each card to turn it over",
				FontManager.getRunescapeBoldFont(), OsrsSkin.ORANGE,
				centreX, l.cards.get(0).y - 14);
		}
		if (all)
		{
			boolean hot = contains(l.continueButton, hover);
			OsrsSkin.plate(g, l.continueButton, hot);
			// "Continue" means "deal me another", so it has to stop saying that the moment
			// another is unaffordable — otherwise the button puts the pack back on the
			// table only for the click on it to be refused, which reads as a broken
			// button rather than as an empty wallet.
			OsrsSkin.centred(g, canBuyAnother() ? "Continue" : "Close",
				FontManager.getRunescapeSmallFont(),
				hot ? OsrsSkin.ORANGE : OsrsSkin.TEXT,
				l.continueButton.x + l.continueButton.width / 2, l.continueButton.y + 14);
		}
	}

	/**
	 * Whether another pack is affordable right now.
	 *
	 * <p>Unknown counts as affordable, matching the click check above and deliberately unlike
	 * {@link Wallet#canOpenPack()}: turning the button into "Close" because a balance had not arrived
	 * yet would end the ceremony on a guess. The balance is never unknown here in practice — the pack
	 * response that produced these cards carried it.
	 */
	private boolean canBuyAnother()
	{
		int credits = wallet.getCredits();
		int packPrice = wallet.getPackPrice();
		return packPrice <= 0 || credits < 0 || credits >= packPrice;
	}

	/**
	 * A face-down card — the back, lifting a touch under the cursor to say it's clickable. The hover
	 * highlight is the card's own gem-tier colour around the edges: a glimpse of what's underneath
	 * before it's turned.
	 */
	private void drawBack(Graphics2D g, Rectangle rect, Color tierColour, boolean hovered)
	{
		int lift = hovered ? HOVER_LIFT : 0;
		Rectangle drawn = new Rectangle(rect.x, rect.y - lift, rect.width, rect.height);
		BufferedImage back = cardBack;
		if (back != null)
		{
			g.drawImage(back, drawn.x, drawn.y, drawn.width, drawn.height, null);
		}
		else
		{
			g.setColor(OsrsSkin.SLOT_FILL);
			g.fillRect(drawn.x, drawn.y, drawn.width, drawn.height);
			OsrsSkin.bevel(g, drawn);
		}
		if (hovered)
		{
			tierEdge(g, drawn, tierColour);
		}
	}

	/**
	 * A turned card, painted by {@link CardFace} — the front template over a tier-coloured backdrop —
	 * with the NEW/copy-count badge hanging under it on the scrim, where NEW can burn bright green.
	 */
	private void drawFace(Graphics2D g, Rectangle rect, PackResult.PulledCard pulled, boolean hovered)
	{
		CatalogueCard card = pulled.getCard();
		// The radiance goes down first, so the card covers its centre and only the halo shows. Every
		// best-tier pull radiates in its gem colour; a curated special outshines it in gold; a showcase
		// card (see Showcase) burns coin-gold despite a common tier, because what it is worth and how
		// rare it is are different questions.
		if (card.isSpecial())
		{
			radiance(g, rect, CardPacksInterface.SPECIAL_GOLD, 8, 52);
		}
		else if (Showcase.is(card))
		{
			radiance(g, rect, Showcase.GLOW, 7, 46);
		}
		else if (card.getTier() >= CatalogueCard.Tiers.highest())
		{
			radiance(g, rect, card.getTierColour(), 5, 30);
		}
		CardFace.paint(g, rect, cardFront, cardArt.imageFor(card), card, isCardman());

		String badge = pulled.isFresh() ? "NEW" : "×" + pulled.getQuantity();
		OsrsSkin.centred(g, badge, FontManager.getRunescapeSmallFont(),
			pulled.isFresh() ? NEW_BADGE : OsrsSkin.TEXT,
			rect.x + rect.width / 2, rect.y + rect.height + 14);

		if (hovered)
		{
			tierEdge(g, rect, card.getTierColour());
		}
	}

	/**
	 * A radiating background behind a card in the reveal: translucent halos stacked outwards from the
	 * card edge, so the light falls off with distance, breathing on a slow pulse. Painted before the
	 * face, which covers the centre. {@code rings} sets the reach and {@code maxAlpha} the brightness —
	 * the intensity dials between a Zenyte's gem-coloured shine and a special's gold blaze.
	 */
	private static void radiance(Graphics2D g, Rectangle rect, Color colour, int rings, int maxAlpha)
	{
		float pulse = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() / 350.0);
		for (int ring = rings; ring >= 1; ring--)
		{
			int spread = ring * GLOW_STEP;
			// Inner rings glow brighter, and the stacked fills brighten the edge further still.
			float falloff = (rings - ring + 1) / (float) rings;
			g.setColor(new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
				(int) (maxAlpha * falloff * pulse)));
			g.fillRoundRect(rect.x - spread, rect.y - spread,
				rect.width + spread * 2, rect.height + spread * 2, spread * 2, spread * 2);
		}
	}

	/** The gem-tier hover highlight: a two-line edge in the card's tier colour. */
	private static void tierEdge(Graphics2D g, Rectangle rect, Color tierColour)
	{
		g.setColor(tierColour);
		g.drawRect(rect.x, rect.y, rect.width - 1, rect.height - 1);
		g.drawRect(rect.x + 1, rect.y + 1, rect.width - 3, rect.height - 3);
	}

	// ── Placement ─────────────────────────────────────────────────────────────

	/**
	 * Stretched mode scales the two axes independently, warping anything drawn square to the game's own
	 * coordinates. This is the multiplier that pre-shrinks (or pre-widens) a width so the drawn shape
	 * comes out at its true proportions after the client stretches it; 1 when not stretched.
	 */
	private double aspectCorrection()
	{
		if (!client.isStretchedEnabled())
		{
			return 1.0;
		}
		Dimension real = client.getRealDimensions();
		Dimension stretched = client.getStretchedDimensions();
		if (real == null || stretched == null || real.width <= 0 || real.height <= 0
			|| stretched.width <= 0 || stretched.height <= 0)
		{
			return 1.0;
		}
		double scaleX = stretched.width / (double) real.width;
		double scaleY = stretched.height / (double) real.height;
		return scaleY / scaleX;
	}

	/** The game viewport in overlay coordinates — what the scrim covers and everything centres in. */
	private Rectangle viewport()
	{
		Dimension game = client.getRealDimensions();
		int gameW = game != null ? game.width : client.getCanvasWidth();
		int gameH = game != null ? game.height : client.getCanvasHeight();
		int width = client.getViewportWidth() > 0 ? client.getViewportWidth() : gameW;
		int height = client.getViewportHeight() > 0 ? client.getViewportHeight() : gameH;
		int offsetX = client.getViewportWidth() > 0 ? client.getViewportXOffset() : 0;
		int offsetY = client.getViewportHeight() > 0 ? client.getViewportYOffset() : 0;
		return new Rectangle(offsetX, offsetY, width, height);
	}

	/** Where the last painted frame put everything, in canvas coordinates. */
	private static final class Layout
	{
		private final Rectangle viewport;
		private final Rectangle close;
		private final Rectangle pack;
		private final List<Rectangle> cards;
		private final Rectangle continueButton;

		/**
		 * {@code aspect} is the horizontal correction for stretched mode — widths are multiplied by it so
		 * the pack and the cards keep their true proportions on screen when the client scales the two axes
		 * differently.
		 */
		private Layout(Rectangle viewport, @Nullable List<PackResult.PulledCard> pull,
			@Nullable BufferedImage packImage, double aspect)
		{
			this.viewport = viewport;

			// The pack at full size: a bit over half the viewport tall, at the artwork's own proportions.
			int packH = Math.max(120, (int) (viewport.height * 0.52));
			int packW = packImage != null
				? Math.max(1, (int) (packImage.getWidth() * packH * aspect / Math.max(1, packImage.getHeight())))
				: (int) (packH / CARD_RATIO * aspect);
			pack = new Rectangle(viewport.x + (viewport.width - packW) / 2,
				viewport.y + (viewport.height - packH) / 2 - 10, packW, packH);

			// The pull in two centred rows, the smaller on top (a five-card pack sits 2 over 3) — a pack
			// is however many cards it is, so the split is the floor half over the rest.
			int count = pull != null ? pull.size() : 0;
			List<Rectangle> dealt = new ArrayList<>(count);
			Rectangle button = new Rectangle();
			if (count > 0)
			{
				int topCount = count / 2;
				int bottomCount = count - topCount;
				int rows = topCount > 0 ? 2 : 1;
				int cardH = (int) (viewport.height * 0.36);
				int cardW = (int) (cardH / CARD_RATIO * aspect);
				int maxW = (viewport.width - CARD_GAP * (bottomCount + 1)) / bottomCount;
				if (cardW > maxW)
				{
					cardW = Math.max(40, maxW);
					cardH = (int) (cardW * CARD_RATIO / aspect);
				}
				int totalH = rows * cardH + (rows - 1) * CARD_GAP;
				int y = viewport.y + (viewport.height - totalH) / 2 - 12;
				addRow(dealt, viewport, topCount, cardW, cardH, y);
				addRow(dealt, viewport, bottomCount, cardW, cardH, topCount > 0 ? y + cardH + CARD_GAP : y);
				// Below the badge line that hangs under the bottom row.
				button = new Rectangle(viewport.x + (viewport.width - 140) / 2, y + totalH + 26, 140, 22);
			}
			cards = Collections.unmodifiableList(dealt);
			continueButton = button;

			// The close sits with what it closes — at the top-right of the pack, or of the card grid —
			// rather than off in a corner of the screen where it disappears.
			int contentRight = pack.x + pack.width;
			int contentTop = pack.y;
			if (!dealt.isEmpty())
			{
				Rectangle bottomRight = dealt.get(dealt.size() - 1);
				contentRight = bottomRight.x + bottomRight.width;
				contentTop = dealt.get(0).y;
			}
			close = new Rectangle(
				Math.min(contentRight + 12, viewport.x + viewport.width - CLOSE_SIZE - 4),
				contentTop, CLOSE_SIZE, CLOSE_SIZE);
		}

		/** One centred row of card slots, appended in deal order. */
		private static void addRow(List<Rectangle> dealt, Rectangle viewport, int count,
			int cardW, int cardH, int y)
		{
			int totalW = count * cardW + (count - 1) * CARD_GAP;
			int x = viewport.x + (viewport.width - totalW) / 2;
			for (int i = 0; i < count; i++)
			{
				dealt.add(new Rectangle(x + i * (cardW + CARD_GAP), y, cardW, cardH));
			}
		}
	}

	// ── Input ─────────────────────────────────────────────────────────────────

	/**
	 * Which dealt card is under {@code point}, or -1. The hit box reaches up to where a hovered card has
	 * lifted to, so neither a click nor the hover tick can miss the card the cursor is visibly on.
	 */
	private static int cardAt(Layout l, @Nullable Point point)
	{
		if (point == null)
		{
			return -1;
		}
		for (int i = 0; i < l.cards.size(); i++)
		{
			Rectangle rect = l.cards.get(i);
			if (new Rectangle(rect.x, rect.y - HOVER_LIFT, rect.width, rect.height + HOVER_LIFT)
				.contains(point))
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Ticks as the cursor arrives on a card. Edge-triggered on the card index, so sweeping a row taps
	 * once per card and sitting still is silent.
	 */
	private void trackHover(@Nullable Point point)
	{
		Layout l = layout;
		int over = l == null || pull == null ? -1 : cardAt(l, point);
		if (over == hoveredIndex)
		{
			return;
		}
		hoveredIndex = over;
		if (over >= 0)
		{
			sounds.tick();
		}
	}

	/** Mouse over the ceremony: drives the controls and is then swallowed, never reaching the game. */
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
			press(event.getPoint());
			event.consume();
			return event;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent event)
		{
			return swallow(event);
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
		public MouseEvent mouseMoved(MouseEvent event)
		{
			Layout l = layout;
			if (!open || l == null)
			{
				mouse = null;
				hoveredIndex = -1;
				return event;
			}
			boolean in = l.viewport.contains(event.getPoint());
			mouse = in ? event.getPoint() : null;
			trackHover(in ? event.getPoint() : null);
			if (in)
			{
				event.consume();
			}
			return event;
		}

		@Override
		public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
		{
			if (inside(event))
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
			return open && l != null && l.viewport.contains(event.getPoint());
		}
	}

	private void press(Point point)
	{
		Layout l = layout;
		if (l == null)
		{
			return;
		}
		if (l.close.contains(point))
		{
			close();
			return;
		}
		List<PackResult.PulledCard> cards = pull;
		if (cards == null)
		{
			if (l.pack.contains(point))
			{
				openPack();
			}
			return;
		}

		boolean[] flags = revealed;
		int i = cardAt(l, point);
		if (i >= 0 && i < cards.size() && flags != null && i < flags.length)
		{
			if (!flags[i])
			{
				// First click turns the card over; the flip itself never counts toward a double-click,
				// so revealing quickly can't accidentally pop the detail view.
				flags[i] = true;
				lastClickedIndex = -1;
				sounds.reveal(cards.get(i).getCard().getTier());
				return;
			}
			long now = System.currentTimeMillis();
			if (lastClickedIndex == i && now - lastClickMs <= DOUBLE_CLICK_MS)
			{
				// Second click on the same revealed card: show it properly, over the ceremony. The pack
				// wire sends a slim card — no unlocks or recipes — so swap in the catalogue's full entry.
				lastClickedIndex = -1;
				PackResult.PulledCard pulled = cards.get(i);
				CatalogueCard card = pulled.getCard();
				CatalogueCard full = packs.cardById(card.getId());
				if (full != null)
				{
					card = full;
				}
				detail.show(card, pulled.getQuantity(), packs.combineInfo(card));
				return;
			}
			lastClickedIndex = i;
			lastClickMs = now;
			return;
		}
		if (allRevealed() && l.continueButton.contains(point))
		{
			if (!canBuyAnother())
			{
				// Nothing left to buy: the honest end of the ceremony is the way out,
				// not an empty table with a pack the player cannot click.
				close();
				return;
			}
			// Back to the table, wallet already updated — ready to open another.
			pull = null;
			revealed = null;
			lastClickedIndex = -1;
			hoveredIndex = -1;
		}
	}

	/**
	 * While the ceremony is up it owns the keyboard — a stray keypress shouldn't walk the character away
	 * mid-open. {@code Escape} puts everything away.
	 */
	private final class KeyHandler implements KeyListener
	{
		@Override
		public void keyTyped(KeyEvent event)
		{
			if (open)
			{
				event.consume();
			}
		}

		@Override
		public void keyPressed(KeyEvent event)
		{
			if (!open)
			{
				return;
			}
			if (event.getKeyCode() == KeyEvent.VK_ESCAPE)
			{
				// The detail view sits over the ceremony, so Escape peels one layer at a time.
				if (detail.isOpen())
				{
					detail.close();
				}
				else
				{
					close();
				}
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

	/** True when this character plays CardMan — its cards carry the silvery-rose wash. */
	private boolean isCardman()
	{
		return characterTracker.activeGameMode() == GameMode.CARDMAN;
	}
}
