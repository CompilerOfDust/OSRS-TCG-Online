package com.thecardexchange.tcg.packs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * The credit balance, on screen, all the time.
 *
 * <p>Credits used to be visible only inside the two pack windows, which was fine while spending was the
 * only thing that moved them — you were looking at the window at the time. It stopped being fine once
 * the server started <em>granting</em> them: levels, quests, diaries, boss kills and pack milestones all
 * arrive while the player is doing something else entirely, and a reward nobody sees is not a reward.
 * The orb's readiness pip answers "can I afford a pack"; this answers "how much do I have", which is the
 * question the pip cannot.
 *
 * <p><b>Top centre</b>, between the two orbs rather than beside either. The corners are taken, and the
 * middle of the top edge is the one place in an Old School client with nothing in it — no tab row, no
 * minimap, no chat.
 *
 * <p><b>Hidden until the balance is known.</b> {@link Wallet} distinguishes "unknown" from "zero" and so
 * does this: showing 0 to a player whose wallet simply has not arrived yet would be a lie, and the gap
 * only lasts until the first heartbeat. Nothing here decides anything — it is a view of the server's
 * last word.
 */
@Singleton
public class CreditsBanner extends Overlay
{
	private static final int HEIGHT = 22;
	private static final int PAD_X = 10;
	private static final int ICON = 15;
	private static final int TOP_MARGIN = 6;
	/** Old School's coin gold, so the number reads as currency without a label saying so. */
	private static final Color COIN = new Color(240, 200, 90);
	private static final int COINS_ITEM_ID = 995;

	private final Client client;
	private final OverlayManager overlayManager;
	private final ItemManager itemManager;
	private final TheCardExchangeTcgConfig config;
	private final Wallet wallet;

	private BufferedImage coins;

	@Inject
	CreditsBanner(Client client, OverlayManager overlayManager, ItemManager itemManager,
		TheCardExchangeTcgConfig config, Wallet wallet)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.itemManager = itemManager;
		this.config = config;
		this.wallet = wallet;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		// Below the windows and the orbs: this is a readout, and it must never paint
		// over the pack ceremony or a card grid that opens across the top of the screen.
		setPriority(Overlay.PRIORITY_LOW);
	}

	void start()
	{
		overlayManager.add(this);
	}

	void shutdown()
	{
		overlayManager.remove(this);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.creditsBanner() || client.getGameState() != GameState.LOGGED_IN || !wallet.isKnown())
		{
			return null;
		}

		Font font = FontManager.getRunescapeBoldFont();
		String text = String.format("%,d", wallet.getCredits());

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		FontMetrics metrics = g.getFontMetrics(font);
		int textW = metrics.stringWidth(text);
		int width = PAD_X * 2 + ICON + 5 + textW;

		Rectangle plate = place(width);
		OsrsSkin.frame(g, plate);

		BufferedImage icon = coins();
		int contentX = plate.x + PAD_X;
		if (icon != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(icon, contentX, plate.y + (HEIGHT - ICON) / 2, ICON, ICON, null);
		}

		OsrsSkin.text(g, text, font, COIN, contentX + ICON + 5, plate.y + HEIGHT - 7);

		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
		return null;
	}

	/**
	 * Centred on the top edge of the game area.
	 *
	 * <p>Measured from the client's <em>real</em> dimensions, not the AWT canvas: overlays paint in game
	 * coordinates, and under stretched mode the canvas is the blown-up size — measuring it would push
	 * this off to the right by however much the client is scaled.
	 */
	private Rectangle place(int width)
	{
		Dimension game = client.getRealDimensions();
		int gameW = game != null ? game.width : client.getCanvasWidth();
		return new Rectangle(Math.max(0, (gameW - width) / 2), TOP_MARGIN, width, HEIGHT);
	}

	/** The coin stack from the client's own item icons. Arrives asynchronously; null just means "not yet". */
	@Nullable
	private BufferedImage coins()
	{
		if (coins == null)
		{
			coins = itemManager.getImage(COINS_ITEM_ID);
		}
		return coins;
	}
}
