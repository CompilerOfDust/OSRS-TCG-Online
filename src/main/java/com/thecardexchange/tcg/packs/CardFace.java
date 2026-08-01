package com.thecardexchange.tcg.packs;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.client.ui.FontManager;
import com.thecardexchange.tcg.ui.OsrsSkin;

/**
 * Paints one card face on the card-front template — shared by the pack-opening reveal and the card
 * detail view so the two can't drift apart.
 *
 * <p>The template's art window is transparent, so the face goes down in layers: a gem-tier backdrop
 * first (the colour showing through the window <i>is</i> the rarity — no card ever spells its tier out
 * in words), then the template, the card's artwork in the window, its name on the banner and its
 * description wrapped on the parchment box. Region positions are pixel-measured fractions of
 * card_front.png.
 *
 * <p>From Diamond up the backdrop changes character: a lit core with a sunburst behind the artwork
 * rather than a flat wash, so the top of the ladder looks like it costs something.
 */
final class CardFace
{
	/** Height over width of the template (1024×1536). */
	static final double RATIO = 1536.0 / 1024.0;

	private static final double BANNER_CENTRE = 0.132;
	private static final double NAME_MAX_WIDTH = 0.46;
	/** The transparent art window, padded a little so the fill always reaches under the frame. */
	private static final double WINDOW_LEFT = 0.08;
	private static final double WINDOW_TOP = 0.155;
	private static final double WINDOW_RIGHT = 0.92;
	private static final double WINDOW_BOTTOM = 0.655;
	/** The window's midline and span, for callers placing their own artwork (the collection tiles). */
	static final double WINDOW_CENTRE_Y = (WINDOW_TOP + WINDOW_BOTTOM) / 2;
	static final double WINDOW_HEIGHT = WINDOW_BOTTOM - WINDOW_TOP;
	private static final double ART_LEFT = 0.24;
	private static final double ART_TOP = 0.20;
	private static final double ART_RIGHT = 0.76;
	private static final double ART_BOTTOM = 0.60;
	/**
	 * The parchment description box (y 0.644–0.850), and how much of the card's width its text may use.
	 * The panel itself spans about 0.11–0.89; the text stops short of that so it never crowds the gold
	 * edging.
	 */
	private static final double BOX_TOP = 0.644;
	private static final double BOX_BOTTOM = 0.850;
	private static final double BOX_MAX_WIDTH = 0.72;
	/** Ink for text sitting on the template's parchment panels, where the skin's light tones vanish. */
	private static final Color PARCHMENT_INK = new Color(62, 46, 24);

	/**
	 * The wash that marks a CardMan card — a silvery rose.
	 *
	 * <p>CardMan and Normal cards are otherwise identical, and telling them apart matters: they are
	 * separate economies that cannot trade with each other, so "which of these can I actually swap"
	 * should be answerable at a glance.
	 */
	private static final Color CARDMAN_WASH = new Color(0xCB, 0xAE, 0xB4);

	/**
	 * How far a CardMan card's colour is pulled toward the wash.
	 *
	 * <p>Deliberately a blend rather than a replacement. The backdrop's colour <em>is</em> the gem
	 * tier — it is how rarity reads at a glance — so painting every CardMan card one flat colour would
	 * trade one distinction for a more important one. At this strength the rose is unmistakable while a
	 * Zenyte still plainly outranks a Sapphire. Raise it to 1.0 for a flat wash if the mode signal
	 * should win outright.
	 */
	private static final float CARDMAN_WASH_STRENGTH = 0.62f;

	/** The gem-tier colour to actually paint with, rose-washed for a CardMan character. */
	private static Color modeTint(Color tier, boolean cardman)
	{
		return cardman ? blend(tier, CARDMAN_WASH, CARDMAN_WASH_STRENGTH) : tier;
	}

	private static Color blend(Color from, Color to, float amount)
	{
		float keep = 1f - amount;
		return new Color(
			Math.round(from.getRed() * keep + to.getRed() * amount),
			Math.round(from.getGreen() * keep + to.getGreen() * amount),
			Math.round(from.getBlue() * keep + to.getBlue() * amount));
	}
	/** Diamond and up (5, 6, 7) wear the premium backdrop rather than the plain gradient. */
	private static final int PREMIUM_TIER = 5;
	/** Spokes in the premium sunburst, and the art-window width below which they aren't worth drawing. */
	private static final int RAYS = 12;
	private static final int RAYS_MIN_WIDTH = 70;

	private CardFace()
	{
	}

	/**
	 * Just the gem-tier backdrop behind the template's transparent art window — a wash graded darker
	 * toward the bottom, or from Diamond up the {@linkplain #premiumBackdrop premium} treatment. The
	 * collection grid uses this alone: its tiles are too small for the text layers.
	 */
	static void backdrop(Graphics2D g, Rectangle rect, CatalogueCard card, boolean cardman)
	{
		Color tier = modeTint(card.getTierColour(), cardman);
		int windowX = rect.x + (int) (rect.width * WINDOW_LEFT);
		int windowY = rect.y + (int) (rect.height * WINDOW_TOP);
		int windowW = (int) (rect.width * (WINDOW_RIGHT - WINDOW_LEFT));
		int windowH = (int) (rect.height * (WINDOW_BOTTOM - WINDOW_TOP));
		if (windowW <= 0 || windowH <= 0)
		{
			return;
		}
		Rectangle window = new Rectangle(windowX, windowY, windowW, windowH);
		if (card.getTier() >= PREMIUM_TIER)
		{
			premiumBackdrop(g, window, tier);
			return;
		}
		g.setPaint(new GradientPaint(windowX, windowY, shade(tier, 0.60f),
			windowX, windowY + windowH, shade(tier, 0.28f)));
		g.fillRect(windowX, windowY, windowW, windowH);
		g.setPaint(null);
	}

	/**
	 * The top of the ladder: a lit core radiating out to a deep edge, with a faint sunburst over it, so
	 * a Diamond, Onyx or Zenyte card reads as lit from within rather than tinted. Clipped to the art
	 * window — the caller's own clip (a grid's viewport) is intersected, never replaced — and drawn
	 * antialiased so the spokes don't come out as staircases.
	 */
	private static void premiumBackdrop(Graphics2D g, Rectangle window, Color tier)
	{
		Shape oldClip = g.getClip();
		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.clip(window);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float centreX = window.x + window.width / 2f;
		// High, where the artwork's head sits, so the light reads as falling on the subject.
		float centreY = window.y + window.height * 0.42f;
		float radius = Math.max(window.width, window.height) * 0.85f;
		g.setPaint(new RadialGradientPaint(
			new Point2D.Float(centreX, centreY), radius,
			new float[]{0f, 0.45f, 1f},
			new Color[]{lighten(tier, 0.45f), shade(tier, 0.52f), shade(tier, 0.14f)}));
		g.fillRect(window.x, window.y, window.width, window.height);
		g.setPaint(null);

		// The sunburst is for a card at face size. A grid thumbnail is barely forty pixels across —
		// twelve spokes there are invisible, and the grid puts the best tiers on the first rows, so
		// every visible tile would pay for them.
		if (window.width >= RAYS_MIN_WIDTH)
		{
			Color spoke = lighten(tier, 0.7f);
			g.setColor(new Color(spoke.getRed(), spoke.getGreen(), spoke.getBlue(), 22));
			double reach = radius * 1.6;
			for (int i = 0; i < RAYS; i++)
			{
				double angle = i * (Math.PI * 2 / RAYS) + Math.PI / RAYS;
				double half = 0.09;
				g.fillPolygon(
					new int[]{
						(int) centreX,
						(int) (centreX + Math.cos(angle - half) * reach),
						(int) (centreX + Math.cos(angle + half) * reach),
					},
					new int[]{
						(int) centreY,
						(int) (centreY + Math.sin(angle - half) * reach),
						(int) (centreY + Math.sin(angle + half) * reach),
					}, 3);
			}
		}

		if (oldAa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}
		g.setClip(oldClip);
	}

	static void paint(Graphics2D g, Rectangle rect, @Nullable BufferedImage front,
		@Nullable BufferedImage art, CatalogueCard card, boolean cardman)
	{
		backdrop(g, rect, card, cardman);

		if (front != null)
		{
			g.drawImage(front, rect.x, rect.y, rect.width, rect.height, null);
		}
		else
		{
			g.setColor(OsrsSkin.SLOT_FILL);
			g.fillRect(rect.x, rect.y, rect.width, rect.height);
			OsrsSkin.bevel(g, rect);
		}

		if (art != null)
		{
			int maxW = (int) (rect.width * (ART_RIGHT - ART_LEFT));
			int maxH = (int) (rect.height * (ART_BOTTOM - ART_TOP));
			double scale = Math.min(maxW / (double) art.getWidth(), maxH / (double) art.getHeight());
			// Small item icons look lost in a card this size; let them grow, but not past their detail.
			scale = Math.min(scale, 3.0);
			int w = Math.max(1, (int) (art.getWidth() * scale));
			int h = Math.max(1, (int) (art.getHeight() * scale));
			int artCentreX = rect.x + (int) (rect.width * (ART_LEFT + ART_RIGHT) / 2);
			int artCentreY = rect.y + (int) (rect.height * (ART_TOP + ART_BOTTOM) / 2);
			g.drawImage(art, artCentreX - w / 2, artCentreY - h / 2, w, h, null);
		}

		Font nameFont = rect.width >= 140
			? FontManager.getRunescapeBoldFont()
			: FontManager.getRunescapeSmallFont();
		Font small = FontManager.getRunescapeSmallFont();
		int centreX = rect.x + rect.width / 2;
		// Baselines drop out of the template regions' midlines and the font's own ascent, so the text
		// stays centred on its parchment at any card size.
		int nameBaseline = rect.y + (int) (rect.height * BANNER_CENTRE)
			+ g.getFontMetrics(nameFont).getAscent() / 2 - 1;
		OsrsSkin.centred(g, OsrsSkin.ellipsise(g, card.getName(), nameFont,
				(int) (rect.width * NAME_MAX_WIDTH)),
			nameFont, PARCHMENT_INK, centreX, nameBaseline);

		// The description fills the parchment box, wrapped and centred as a block on its midline.
		String description = card.getDescription();
		if (description != null && !description.trim().isEmpty())
		{
			String text = description.trim();
			int boxWidth = (int) (rect.width * BOX_MAX_WIDTH);
			int boxHeight = (int) (rect.height * (BOX_BOTTOM - BOX_TOP));
			// The biggest font the text still fits the box in: a card at reveal size reads comfortably,
			// while a smaller face steps down to the small font rather than dropping half the line.
			Font font = FontManager.getRunescapeFont();
			List<String> lines = wrap(g, text, font, boxWidth, Integer.MAX_VALUE);
			if (lines.size() > lineCapacity(g, font, boxHeight))
			{
				font = small;
				lines = wrap(g, text, font, boxWidth, lineCapacity(g, small, boxHeight));
			}
			int lineHeight = g.getFontMetrics(font).getHeight() - 2;
			int boxCentreY = rect.y + (int) (rect.height * (BOX_TOP + BOX_BOTTOM) / 2);
			int baseline = boxCentreY - (lines.size() - 1) * lineHeight / 2
				+ g.getFontMetrics(font).getAscent() / 2 - 1;
			for (String line : lines)
			{
				OsrsSkin.centred(g, line, font, PARCHMENT_INK, centreX, baseline);
				baseline += lineHeight;
			}
		}

		// No tier label: the backdrop colour behind the artwork already says what the card is worth.

		if (card.isSpecial())
		{
			// Curated specials wear gold outside the template's own frame, as everywhere else.
			g.setColor(CardPacksInterface.SPECIAL_GOLD);
			g.drawRect(rect.x - 2, rect.y - 2, rect.width + 3, rect.height + 3);
		}
	}

	/** How many lines of {@code font} the box has room for. */
	private static int lineCapacity(Graphics2D g, Font font, int boxHeight)
	{
		int lineHeight = g.getFontMetrics(font).getHeight() - 2;
		return Math.max(1, (boxHeight - 4) / lineHeight);
	}

	/**
	 * Word-wraps {@code text} to {@code maxWidth} pixels, at most {@code maxLines} lines — the last line
	 * is ellipsised when the text doesn't fit. A single word wider than the box gets cut rather than
	 * overflowing it.
	 */
	private static List<String> wrap(Graphics2D g, String text, Font font, int maxWidth, int maxLines)
	{
		g.setFont(font);
		FontMetrics metrics = g.getFontMetrics();
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split("\\s+"))
		{
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (metrics.stringWidth(candidate) <= maxWidth || line.length() == 0)
			{
				line.setLength(0);
				line.append(candidate);
				continue;
			}
			lines.add(OsrsSkin.ellipsise(g, line.toString(), font, maxWidth));
			line.setLength(0);
			line.append(word);
			if (lines.size() == maxLines)
			{
				break;
			}
		}
		if (lines.size() < maxLines && line.length() > 0)
		{
			lines.add(OsrsSkin.ellipsise(g, line.toString(), font, maxWidth));
		}
		else if (lines.size() == maxLines && line.length() > 0)
		{
			// Out of room with words left over: mark the cut on the last kept line.
			String last = lines.get(maxLines - 1);
			lines.set(maxLines - 1, OsrsSkin.ellipsise(g, last + "…", font, maxWidth));
		}
		return lines;
	}

	/** The tier colour taken down to a backdrop tone. */
	private static Color shade(Color colour, float factor)
	{
		return new Color((int) (colour.getRed() * factor), (int) (colour.getGreen() * factor),
			(int) (colour.getBlue() * factor));
	}

	/**
	 * The tier colour lifted {@code amount} of the way to white — what gives a dark tier like Onyx a
	 * core bright enough to read as lit.
	 */
	private static Color lighten(Color colour, float amount)
	{
		return new Color(
			(int) (colour.getRed() + (255 - colour.getRed()) * amount),
			(int) (colour.getGreen() + (255 - colour.getGreen()) * amount),
			(int) (colour.getBlue() + (255 - colour.getBlue()) * amount));
	}
}
