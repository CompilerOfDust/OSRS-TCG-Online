package com.thecardexchange.tcg.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * The painted Old School look shared by the plugin's in-game interfaces — the palette and the handful of
 * primitives (plate, bevel, well, shadowed text) they are all built from. One place so the trade window
 * and the card-packs interface can't drift apart.
 *
 * <p>The tones are the game's own interface browns taken a shade darker, which is what keeps our windows
 * legible against the scene without looking like a different game.
 */
public final class OsrsSkin
{
	public static final Color BACKDROP_BASE = new Color(45, 38, 29);
	public static final Color BORDER_DARK = new Color(14, 11, 8);
	public static final Color BORDER_LIGHT = new Color(88, 76, 56);
	public static final Color INSET_FILL = new Color(28, 23, 17);
	public static final Color SLOT_FILL = new Color(35, 29, 22);
	public static final Color DIVIDER = new Color(70, 60, 44);
	public static final Color ORANGE = new Color(255, 152, 31);
	public static final Color TEXT = new Color(213, 201, 176);
	public static final Color MUTED = new Color(138, 126, 105);
	public static final Color SHADOW = new Color(0, 0, 0, 190);
	public static final Color PLATE = new Color(58, 49, 37);
	public static final Color PLATE_HOVER = new Color(76, 65, 48);
	public static final Color PLATE_DISABLED = new Color(43, 37, 28);
	public static final Color RED = new Color(198, 66, 52);
	public static final Color RED_HOVER = new Color(232, 96, 78);
	public static final Color YELLOW = new Color(214, 197, 62);
	/**
	 * Good news. The plugin's one green, borrowed from the NEW badge on a freshly pulled card and
	 * matching both the brand mark's centre dot and the network "online" badge — so a green mark
	 * always means the same thing wherever it appears.
	 */
	public static final Color GOOD = new Color(120, 220, 120);
	/** The recessed strip a window title sits on. */
	public static final Color TITLE_STRIP = new Color(0, 0, 0, 60);
	/** Row highlight inside a list. */
	public static final Color ROW_HOVER = new Color(255, 255, 255, 22);
	public static final Color ROW_SELECTED = new Color(255, 152, 31, 40);

	/** Faint speckle so a panel reads as an Old School stone plate rather than flat fill. */
	public static final TexturePaint BACKDROP = createBackdrop();

	private OsrsSkin()
	{
	}

	/** A window plate: drop shadow, speckled backdrop, black outline with a brown bevel inside it. */
	public static void frame(Graphics2D g, Rectangle r)
	{
		g.setColor(new Color(0, 0, 0, 120));
		g.fillRect(r.x + 3, r.y + 4, r.width, r.height);

		g.setPaint(BACKDROP);
		g.fillRect(r.x, r.y, r.width, r.height);
		g.setPaint(null);

		g.setColor(BORDER_DARK);
		g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
		g.setColor(BORDER_LIGHT);
		g.drawRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3);
		g.setColor(BORDER_DARK);
		g.drawRect(r.x + 2, r.y + 2, r.width - 5, r.height - 5);
	}

	/** The recessed strip behind a window title, and the divider that closes it off. */
	public static void titleStrip(Graphics2D g, Rectangle window, int titleHeight)
	{
		g.setColor(TITLE_STRIP);
		g.fillRect(window.x + 3, window.y + 3, window.width - 6, titleHeight - 3);
		g.setColor(DIVIDER);
		g.drawLine(window.x + 4, window.y + titleHeight, window.x + window.width - 5, window.y + titleHeight);
		g.setColor(BORDER_DARK);
		g.drawLine(window.x + 4, window.y + titleHeight + 1, window.x + window.width - 5, window.y + titleHeight + 1);
	}

	/** Raised edge: light top/left, dark bottom/right. */
	public static void bevel(Graphics2D g, Rectangle r)
	{
		g.setColor(BORDER_LIGHT);
		g.drawLine(r.x, r.y, r.x + r.width - 1, r.y);
		g.drawLine(r.x, r.y, r.x, r.y + r.height - 1);
		g.setColor(BORDER_DARK);
		g.drawLine(r.x, r.y + r.height - 1, r.x + r.width - 1, r.y + r.height - 1);
		g.drawLine(r.x + r.width - 1, r.y, r.x + r.width - 1, r.y + r.height - 1);
	}

	/** Sunken edge — the inverse of {@link #bevel}. */
	public static void inset(Graphics2D g, Rectangle r)
	{
		g.setColor(BORDER_DARK);
		g.drawLine(r.x, r.y, r.x + r.width - 1, r.y);
		g.drawLine(r.x, r.y, r.x, r.y + r.height - 1);
		g.setColor(DIVIDER);
		g.drawLine(r.x, r.y + r.height - 1, r.x + r.width - 1, r.y + r.height - 1);
		g.drawLine(r.x + r.width - 1, r.y, r.x + r.width - 1, r.y + r.height - 1);
	}

	/** A sunken well — where items, cards or typed text live. */
	public static void well(Graphics2D g, Rectangle r)
	{
		g.setColor(INSET_FILL);
		g.fillRect(r.x, r.y, r.width, r.height);
		inset(g, r);
	}

	/** A clickable button plate. */
	public static void plate(Graphics2D g, Rectangle r, boolean hot)
	{
		g.setColor(hot ? PLATE_HOVER : PLATE_DISABLED);
		g.fillRect(r.x, r.y, r.width, r.height);
		bevel(g, r);
	}

	/** The red X every Old School interface closes with. */
	public static void closeButton(Graphics2D g, Rectangle r, boolean hot)
	{
		g.setColor(hot ? PLATE_HOVER : PLATE);
		g.fillRect(r.x, r.y, r.width, r.height);
		bevel(g, r);
		g.setColor(hot ? RED_HOVER : RED);
		g.drawLine(r.x + 4, r.y + 4, r.x + r.width - 5, r.y + r.height - 5);
		g.drawLine(r.x + 5, r.y + 4, r.x + r.width - 4, r.y + r.height - 5);
		g.drawLine(r.x + r.width - 5, r.y + 4, r.x + 4, r.y + r.height - 5);
		g.drawLine(r.x + r.width - 4, r.y + 4, r.x + 5, r.y + r.height - 5);
	}

	public static void text(Graphics2D g, String text, Font font, Color colour, int x, int baselineY)
	{
		g.setFont(font);
		g.setColor(SHADOW);
		g.drawString(text, x + 1, baselineY + 1);
		g.setColor(colour);
		g.drawString(text, x, baselineY);
	}

	public static void centred(Graphics2D g, String text, Font font, Color colour, int centreX, int baselineY)
	{
		g.setFont(font);
		text(g, text, font, colour, centreX - g.getFontMetrics().stringWidth(text) / 2, baselineY);
	}

	/** Cuts {@code text} to {@code maxWidth} pixels in {@code font}, ellipsising if it doesn't fit. */
	public static String ellipsise(Graphics2D g, String text, Font font, int maxWidth)
	{
		g.setFont(font);
		if (g.getFontMetrics().stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && g.getFontMetrics().stringWidth(cut + "…") > maxWidth)
		{
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "…";
	}

	/**
	 * Nudges {@code rect} so it stays inside a {@code width}×{@code height} area, in place. Used to keep a
	 * dragged window on screen; a non-positive area (no client yet) leaves the rect alone.
	 */
	public static void clampInto(Rectangle rect, int width, int height)
	{
		if (width <= 0 || height <= 0)
		{
			return;
		}
		rect.x = Math.max(0, Math.min(rect.x, width - rect.width));
		rect.y = Math.max(0, Math.min(rect.y, height - rect.height));
	}

	/** Character-count trim, for text laid out against a known slot rather than measured pixels. */
	public static String trim(String value, int max)
	{
		if (value == null)
		{
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}

	private static TexturePaint createBackdrop()
	{
		BufferedImage tile = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		Random rng = new Random(0x7C6EL);
		for (int y = 0; y < 64; y++)
		{
			for (int x = 0; x < 64; x++)
			{
				int n = rng.nextInt(13) - 6;
				tile.setRGB(x, y, new Color(
					clamp(BACKDROP_BASE.getRed() + n),
					clamp(BACKDROP_BASE.getGreen() + n),
					clamp(BACKDROP_BASE.getBlue() + n)).getRGB());
			}
		}
		return new TexturePaint(tile, new Rectangle(0, 0, 64, 64));
	}

	private static int clamp(int value)
	{
		return Math.max(0, Math.min(255, value));
	}
}
