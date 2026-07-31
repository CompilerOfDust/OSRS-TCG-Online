package com.thecardexchange.tcg;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.LinkHandshake;
import com.thecardexchange.tcg.account.LinkState;
import com.thecardexchange.tcg.account.LinkedAccount;

/**
 * The side panel. It renders the current linking state and the one action that
 * makes sense for it — "Link my account", the code-and-confirm view while
 * waiting, or "Linked as … / Unlink" once done. It owns no state; everything is
 * read from {@link AccountLinkManager} and rebuilt on {@link #refresh()}.
 */
@Slf4j
class TheCardExchangeTcgPanel extends PluginPanel
{
	private static final Color OK = new Color(0x44BB44);
	private static final Color WARN = new Color(0xFF9040);
	private static final Color BAD = new Color(0xFF4444);
	private static final Color GOLD = new Color(0xD4AF37);

	private final AccountLinkManager linkManager;

	/** The state-dependent region, rebuilt each refresh while the header stays put. */
	private final JPanel body = new JPanel();

	@Inject
	TheCardExchangeTcgPanel(AccountLinkManager linkManager)
	{
		this.linkManager = linkManager;

		setLayout(new BorderLayout());

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		content.add(title("OSRS TCG (TheCardExchange)"));
		content.add(Box.createVerticalStrut(4));
		content.add(wrapped("Turn Old School RuneScape into a card game. Link your OSRS Card Exchange "
			+ "account to get started."));
		content.add(Box.createVerticalStrut(14));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(body);

		add(content, BorderLayout.NORTH);

		redraw();
	}

	/** Rebuilds the state view. Safe to call from any thread. */
	void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::redraw);
			return;
		}
		redraw();
	}

	private void redraw()
	{
		body.removeAll();

		switch (linkManager.getState())
		{
			case NOT_LINKED:
				renderNotLinked();
				break;
			case STARTING:
				renderStarting();
				break;
			case AWAITING_CONFIRMATION:
				renderAwaitingConfirmation();
				break;
			case LINKED:
				renderLinked();
				break;
			case ERROR:
				renderError();
				break;
			default:
				break;
		}

		body.revalidate();
		body.repaint();
	}

	private void renderNotLinked()
	{
		body.add(statusRow("Not linked", WARN));
		body.add(Box.createVerticalStrut(10));
		body.add(wrapped("You'll be given a short code to enter on the website while signed in. Nothing is "
			+ "shared until you confirm it there."));
		body.add(Box.createVerticalStrut(10));
		body.add(primaryButton("Link my account", "Start linking this plugin to your account", () ->
		{
			linkManager.beginLink();
			refresh();
		}));
	}

	private void renderStarting()
	{
		body.add(statusRow("Starting…", WARN));
		body.add(Box.createVerticalStrut(10));
		body.add(wrapped("Contacting the exchange…"));
	}

	private void renderAwaitingConfirmation()
	{
		LinkHandshake hs = linkManager.getHandshake();
		body.add(statusRow("Waiting for confirmation", WARN));
		body.add(Box.createVerticalStrut(10));

		if (hs != null)
		{
			body.add(wrapped("Enter this code on the website while signed in:"));
			body.add(Box.createVerticalStrut(6));
			body.add(codeLabel(hs.getCode()));
			body.add(Box.createVerticalStrut(10));
			body.add(primaryButton("Open link page", "Open " + hs.getVerificationUri(), () ->
				LinkBrowser.browse(hs.getVerificationUriComplete())));
			body.add(Box.createVerticalStrut(6));
			body.add(plainButton("Cancel", "Stop linking", () ->
			{
				linkManager.cancelLink();
				refresh();
			}));
		}
		else
		{
			body.add(wrapped("Waiting…"));
		}
	}

	private void renderLinked()
	{
		LinkedAccount acct = linkManager.getLinkedAccount();
		String name = acct != null && !acct.getOsrsName().isEmpty() ? acct.getOsrsName() : "your account";
		body.add(statusRow("Linked", OK));
		body.add(Box.createVerticalStrut(10));
		body.add(wrapped("Signed in as " + name + "."));
		if (acct != null && !acct.getEmail().isEmpty())
		{
			body.add(Box.createVerticalStrut(2));
			body.add(muted(acct.getEmail()));
		}
		body.add(Box.createVerticalStrut(12));
		body.add(plainButton("Unlink", "Forget this account on this device", () ->
		{
			linkManager.unlink();
			refresh();
		}));
	}

	private void renderError()
	{
		String error = linkManager.getErrorMessage();
		body.add(statusRow("Not linked", BAD));
		body.add(Box.createVerticalStrut(10));
		body.add(coloured(error == null ? "Something went wrong. Please try again." : error, BAD));
		body.add(Box.createVerticalStrut(10));
		body.add(primaryButton("Try again", "Start linking again", () ->
		{
			linkManager.beginLink();
			refresh();
		}));
	}

	// ── Small UI builders ──────────────────────────────────────────────────

	private JPanel statusRow(String value, Color colour)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		JLabel key = new JLabel("Status");
		key.setFont(FontManager.getRunescapeSmallFont());
		key.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(key, BorderLayout.WEST);

		JLabel val = new JLabel(value);
		val.setFont(FontManager.getRunescapeSmallFont());
		val.setForeground(colour);
		val.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(val, BorderLayout.EAST);
		return row;
	}

	private static JLabel codeLabel(String code)
	{
		JLabel label = new JLabel(code, SwingConstants.CENTER);
		label.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
		label.setForeground(GOLD);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
		return label;
	}

	private static JLabel title(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(Color.WHITE);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel wrapped(String text)
	{
		return coloured(text, ColorScheme.LIGHT_GRAY_COLOR);
	}

	private static JLabel muted(String text)
	{
		return coloured(text, ColorScheme.LIGHT_GRAY_COLOR.darker());
	}

	private static JLabel coloured(String text, Color colour)
	{
		JLabel label = new JLabel("<html><body style='width:180px'>" + text + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(colour);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JButton primaryButton(String text, String tooltip, Runnable onClick)
	{
		return button(text, tooltip, onClick, GOLD, Color.BLACK);
	}

	private JButton plainButton(String text, String tooltip, Runnable onClick)
	{
		return button(text, tooltip, onClick, ColorScheme.MEDIUM_GRAY_COLOR, Color.WHITE);
	}

	private static JButton button(String text, String tooltip, Runnable onClick, Color bg, Color fg)
	{
		JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.setToolTipText(tooltip);
		button.setCursor(new Cursor(Cursor.HAND_CURSOR));
		button.setBackground(bg);
		button.setForeground(fg);
		button.setFont(FontManager.getRunescapeBoldFont());
		button.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height + 8));
		button.addActionListener(e -> onClick.run());
		return button;
	}
}
