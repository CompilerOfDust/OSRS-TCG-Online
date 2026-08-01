package com.thecardexchange.tcg.ui;

import com.thecardexchange.tcg.FeatureGate;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The dialogue shown when the game is closed to this character.
 *
 * <p>Painted on the game canvas in the same style as the pack and trade windows, rather than a Swing
 * pop-up: a Swing dialog would steal focus from the client mid-game, and can appear on a different
 * monitor from the one being played on.
 *
 * <p>It says <em>why</em>, in the server's own words, because the three reasons need different
 * actions — link an account, re-link a character on the website, or wait for a review. "This is
 * unavailable" would leave a player with nowhere to go.
 */
@Singleton
public class BlockedNotice extends Overlay
{
	private static final int WIDTH = 320;
	private static final int PADDING = 14;
	private static final int TITLE_HEIGHT = 26;
	private static final int LINE_HEIGHT = 15;
	private static final int BUTTON_HEIGHT = 24;

	private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 14);
	private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

	private final Client client;
	private final OverlayManager overlayManager;
	private final MouseManager mouseManager;
	private final FeatureGate gate;

	private volatile boolean open;
	private volatile Rectangle bounds = new Rectangle();
	private volatile Rectangle okButton = new Rectangle();
	private volatile boolean hot;

	private final MouseAdapter mouseHandler = new MouseAdapter()
	{
		@Override
		public MouseEvent mouseMoved(MouseEvent event)
		{
			if (open)
			{
				hot = okButton.contains(event.getPoint());
			}
			return event;
		}

		@Override
		public MouseEvent mousePressed(MouseEvent event)
		{
			if (!open || !bounds.contains(event.getPoint()))
			{
				return event;
			}
			if (okButton.contains(event.getPoint()))
			{
				open = false;
			}
			// Every click inside the box is consumed, including one that misses the
			// button — otherwise it falls through and walks the player somewhere.
			event.consume();
			return event;
		}
	};

	@Inject
	BlockedNotice(Client client, OverlayManager overlayManager, MouseManager mouseManager, FeatureGate gate)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.mouseManager = mouseManager;
		this.gate = gate;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(Overlay.PRIORITY_HIGHEST);
	}

	public void start()
	{
		overlayManager.add(this);
		mouseManager.registerMouseListener(mouseHandler);
	}

	public void stop()
	{
		overlayManager.remove(this);
		mouseManager.unregisterMouseListener(mouseHandler);
		open = false;
	}

	/** Shows the current reason. No-op when nothing is blocking — never a dialogue with nothing to say. */
	public void show()
	{
		if (gate.blockedReason() != null)
		{
			open = true;
		}
	}

	public void hide()
	{
		open = false;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!open)
		{
			return null;
		}

		final String reason = gate.blockedReason();
		if (reason == null)
		{
			// Cleared while the box was up — a review passed, a character re-linked.
			// Close rather than keep insisting on something no longer true.
			open = false;
			return null;
		}

		g.setFont(BODY_FONT);
		final FontMetrics metrics = g.getFontMetrics();
		final List<String> lines = wrap(reason, metrics, WIDTH - PADDING * 2);

		final int height = TITLE_HEIGHT + PADDING + lines.size() * LINE_HEIGHT + PADDING + BUTTON_HEIGHT + PADDING;
		final Dimension canvas = client.getRealDimensions();
		final Rectangle box = new Rectangle(
			(canvas.width - WIDTH) / 2, (canvas.height - height) / 2, WIDTH, height);
		OsrsSkin.clampInto(box, canvas.width, canvas.height);
		bounds = box;

		OsrsSkin.frame(g, box);
		OsrsSkin.titleStrip(g, box, TITLE_HEIGHT);
		OsrsSkin.centred(g, gate.blockedTitle(), TITLE_FONT, OsrsSkin.ORANGE,
			box.x + box.width / 2, box.y + 18);

		int y = box.y + TITLE_HEIGHT + PADDING + 10;
		for (String line : lines)
		{
			OsrsSkin.centred(g, line, BODY_FONT, OsrsSkin.TEXT, box.x + box.width / 2, y);
			y += LINE_HEIGHT;
		}

		final Rectangle button = new Rectangle(
			box.x + box.width / 2 - 40, box.y + box.height - PADDING - BUTTON_HEIGHT, 80, BUTTON_HEIGHT);
		okButton = button;
		OsrsSkin.plate(g, button, hot);
		OsrsSkin.centred(g, "OK", BODY_FONT, OsrsSkin.TEXT,
			button.x + button.width / 2, button.y + button.height / 2 + 4);

		return null;
	}

	/** Greedy word wrap — the messages are a sentence or two, so nothing cleverer earns its keep. */
	private static List<String> wrap(String text, FontMetrics metrics, int maxWidth)
	{
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+"))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (metrics.stringWidth(candidate) > maxWidth && line.length() > 0)
			{
				lines.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}
}
