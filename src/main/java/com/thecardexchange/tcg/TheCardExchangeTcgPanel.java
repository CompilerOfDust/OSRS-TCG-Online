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
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.CharacterSnapshot;
import com.thecardexchange.tcg.account.CharacterState;
import com.thecardexchange.tcg.account.CharacterTracker;
import com.thecardexchange.tcg.account.LinkHandshake;
import com.thecardexchange.tcg.account.LinkState;
import com.thecardexchange.tcg.account.LinkedAccount;
import com.thecardexchange.tcg.mode.GameMode;

/**
 * The side panel. It renders the current linking state and the one action that
 * makes sense for it — "Link my account", the code-and-confirm view while
 * waiting, or "Linked as … / Unlink" once done — and, below it, the game mode:
 * the CardMan-or-Normal chooser until the player picks, their locked-in mode
 * afterwards. It owns no state; everything is read from
 * {@link AccountLinkManager} / {@link GameModeManager} and rebuilt on
 * {@link #refresh()}.
 */
@Slf4j
class TheCardExchangeTcgPanel extends PluginPanel
{
	private static final Color OK = new Color(0x44BB44);
	private static final Color WARN = new Color(0xFF9040);
	private static final Color BAD = new Color(0xFF4444);
	private static final Color GOLD = new Color(0xD4AF37);

	private final AccountLinkManager linkManager;
	private final CharacterTracker characterTracker;
	private final TheCardExchangeTcgConfig config;

	/** The state-dependent region, rebuilt each refresh while the header stays put. */
	private final JPanel body = new JPanel();

	/** The game-mode region: the chooser, or the mode the player locked in. */
	private final JPanel modeSection = new JPanel();

	@Inject
	TheCardExchangeTcgPanel(
		AccountLinkManager linkManager,
		CharacterTracker characterTracker,
		TheCardExchangeTcgConfig config)
	{
		this.linkManager = linkManager;
		this.characterTracker = characterTracker;
		this.config = config;

		setLayout(new BorderLayout());

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		content.add(title("OSRS TCG Online"));
		content.add(Box.createVerticalStrut(4));
		content.add(wrapped("Turn Old School RuneScape into a card game. Link your OSRS Card Exchange "
			+ "account to get started."));
		content.add(Box.createVerticalStrut(14));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(body);

		content.add(Box.createVerticalStrut(16));
		content.add(divider());
		content.add(Box.createVerticalStrut(12));

		modeSection.setLayout(new BoxLayout(modeSection, BoxLayout.Y_AXIS));
		modeSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeSection.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(modeSection);

		// Built once here rather than inside redraw(), which is the point: every
		// other section rebuilds per state and returns early down half a dozen
		// branches (not linked, wrong account, logged out, paused…). A guide link
		// that lives in one of those is missing exactly when it is most wanted —
		// somebody who has not linked yet is the reader most likely to need it.
		content.add(Box.createVerticalStrut(16));
		content.add(divider());
		content.add(Box.createVerticalStrut(12));
		content.add(sectionTitle("Guides"));
		content.add(Box.createVerticalStrut(8));
		content.add(plainButton("Getting started",
			"Install, link your account, and what to do first",
			() -> LinkBrowser.browse(webUrl("/getting-started"))));
		content.add(Box.createVerticalStrut(6));
		content.add(plainButton("Game modes and trading",
			"Normal vs CardMan, what CardMan requires, and who can trade with whom",
			() -> LinkBrowser.browse(webUrl("/guide/game-modes"))));

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

		// Above the link status on purpose: while a character is held, that is the
		// only thing the player needs to read.
		renderReviewBanner();

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

		renderGameMode();

		body.revalidate();
		body.repaint();
		modeSection.revalidate();
		modeSection.repaint();
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
		// Unlinking is done on the website, not here.
		//
		// One account can have several OSRS characters linked, each with its own game mode, and this
		// install only ever sees the one you are logged in as — so a button here could only guess at
		// which link you meant. The profile page lists them all and unlinks the one you pick, and
		// because the server holds that decision it survives a re-login instead of being undone by the
		// next bind.
		body.add(plainButton("Manage linked accounts",
			"Open your profile to see every linked OSRS account and unlink one",
			() -> LinkBrowser.browse(webUrl("/profile"))));
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

	// ── Game mode ──────────────────────────────────────────────────────────

	/**
	 * Draws the mode region for whichever state the character is in: no account
	 * yet, nobody logged in, a character being linked, one that belongs to
	 * somebody else, the chooser, or the mode already settled on.
	 *
	 * <p>The mode belongs to the <em>character</em>, so most of these states exist
	 * because there may be no character to speak of yet — and the choice is the
	 * server's to grant, so this only ever renders its answer.
	 */
	private void renderGameMode()
	{
		modeSection.removeAll();
		modeSection.add(sectionTitle("Game mode"));
		modeSection.add(Box.createVerticalStrut(8));

		if (linkManager.getState() != LinkState.LINKED)
		{
			modeSection.add(muted("Link your account to choose how you play."));
			return;
		}

		if (characterTracker.isOnExcludedWorld())
		{
			modeSection.add(keyValueRow("Mode", "Paused", WARN));
			modeSection.add(Box.createVerticalStrut(10));
			modeSection.add(muted("Seasonal or beta world — nothing is recorded here, and your "
				+ "CardMan run isn't affected by what you do on it."));
			return;
		}

		String mismatch = characterTracker.getMismatchMessage();
		if (mismatch != null)
		{
			modeSection.add(keyValueRow("Mode", "Wrong account", BAD));
			modeSection.add(Box.createVerticalStrut(10));
			modeSection.add(coloured(mismatch, BAD));
			modeSection.add(Box.createVerticalStrut(8));
			modeSection.add(muted("Sign in to the account that owns this character, or unlink above."));
			return;
		}

		String rsn = characterTracker.getCurrentRsn();
		if (rsn == null)
		{
			// getRSProfileKey() is null before login, so there is genuinely no
			// character whose mode we could show.
			modeSection.add(muted("Log in to Old School RuneScape to see this character's mode."));
			return;
		}

		if (!characterTracker.isBound())
		{
			modeSection.add(keyValueRow("Mode", "Linking…", WARN));
			modeSection.add(Box.createVerticalStrut(10));
			modeSection.add(muted("Linking " + rsn + " to your account…"));
			return;
		}

		// The server's answer once bound, else the cache — see activeGameMode(). The
		// cache is only for the gap before the first bind lands, so the panel can
		// draw instantly instead of flickering.
		GameMode mode = characterTracker.activeGameMode();

		modeSection.add(keyValueRow("Mode", mode.getDisplayName(), mode.isSelected() ? OK : WARN));
		modeSection.add(Box.createVerticalStrut(4));
		modeSection.add(muted(rsn));
		modeSection.add(Box.createVerticalStrut(10));

		if (mode.isSelected())
		{
			modeSection.add(wrapped(mode.getDescription()));
			modeSection.add(Box.createVerticalStrut(8));
			modeSection.add(muted("Locked in for this character. Each character chooses once."));
			return;
		}

		modeSection.add(wrapped("Choose how " + rsn + " plays. You only choose once — it can't be "
			+ "changed afterwards."));

		// CardMan has to start from an untouched account, so the panel checks the
		// character it is looking at before offering it. The *server* still decides
		// — this is only so a player is not invited to make a permanent choice that
		// is going to be refused. `null` means we have not captured a character yet,
		// which reads as "cannot offer it", never as "allow it".
		CharacterSnapshot snapshot = characterTracker.currentSnapshot();
		boolean freshKnown = snapshot != null;
		boolean brandNew = freshKnown && snapshot.isBrandNew();

		for (GameMode option : GameMode.selectable())
		{
			boolean needsFresh = option == GameMode.CARDMAN;
			boolean allowed = !needsFresh || brandNew;

			modeSection.add(Box.createVerticalStrut(12));
			modeSection.add(optionTitle(option.getDisplayName()));
			modeSection.add(Box.createVerticalStrut(2));
			modeSection.add(muted(option.getDescription()));
			modeSection.add(Box.createVerticalStrut(6));

			JButton choose = primaryButton("Play " + option.getDisplayName(),
				allowed
					? "Play as " + option.getDisplayName() + " — this can't be changed later"
					: "CardMan can only be started on a brand-new account",
				() -> chooseGameMode(option, rsn));
			choose.setEnabled(allowed);
			modeSection.add(choose);

			if (!allowed)
			{
				modeSection.add(Box.createVerticalStrut(4));
				modeSection.add(muted(freshKnown
					? "Not available: " + rsn + " has already trained. CardMan starts from a "
						+ "brand-new account — every skill 1, Hitpoints 10 — so it has to be a fresh one."
					: "Checking this character’s skills…"));
			}
		}

		// No guide links here — they live in the always-visible "Guides" section
		// built in the constructor, so they survive every early return above.
	}

	/** A page on the website (not the api — different origins in production). */
	private String webUrl(String path)
	{
		String configured = config.webAppUrl();
		String base = (configured == null || configured.trim().isEmpty())
			? TheCardExchangeTcgConfig.defaultWebAppUrl()
			: configured.trim();
		return base.replaceAll("/+$", "") + path;
	}

	/**
	 * Asks for the choice, then asks the <em>server</em> for it.
	 *
	 * The confirmation is not ceremony — this cannot be undone. CardMan adds a
	 * second step: an explicit acknowledgement that the mode watches every XP
	 * gained, so that a later hold is a rule the player agreed to rather than a
	 * surprise. Eligibility is never judged here; a refusal comes back from the
	 * server with a sentence written for the player.
	 */
	private void chooseGameMode(GameMode mode, String rsn)
	{
		boolean cardman = mode == GameMode.CARDMAN;
		JCheckBox acknowledge = new JCheckBox("<html><body style='width:320px'>I understand: I'll only "
			+ "play this account in RuneLite with this plugin running. Playing on mobile or the "
			+ "vanilla client will flag it for review.</body></html>");

		JPanel message = new JPanel();
		message.setLayout(new BoxLayout(message, BoxLayout.Y_AXIS));
		message.add(new JLabel("<html><body style='width:320px'><b>Play " + rsn + " as "
			+ mode.getDisplayName() + "?</b><br><br>" + mode.getDescription()
			+ "<br><br>This is permanent — the mode can't be changed once it's set."
			+ (cardman ? "<br><br>CardMan needs a brand-new ironman account. The exchange checks this."
				: "")
			+ "</body></html>"));
		if (cardman)
		{
			message.add(Box.createVerticalStrut(10));
			message.add(acknowledge);
		}

		int answer = JOptionPane.showConfirmDialog(
			this, message, "Choose " + mode.getDisplayName() + " mode",
			JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

		if (answer != JOptionPane.YES_OPTION)
		{
			return;
		}
		if (cardman && !acknowledge.isSelected())
		{
			JOptionPane.showMessageDialog(this,
				"Tick the box to confirm you understand how CardMan is tracked.",
				"Not set", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		characterTracker.chooseMode(mode, cardman && acknowledge.isSelected(),
			this::refresh,
			reason -> SwingUtilities.invokeLater(() ->
			{
				JOptionPane.showMessageDialog(this, "<html><body style='width:320px'>" + reason
					+ "</body></html>", "Can't play " + mode.getDisplayName(),
					JOptionPane.WARNING_MESSAGE);
				refresh();
			}));
	}

	/** The hold, pinned above everything else — it is the only thing that matters while it is up. */
	private void renderReviewBanner()
	{
		CharacterState state = characterTracker.getState();
		if (!state.isHeld())
		{
			return;
		}
		String message = state.getReviewMessage() != null
			? state.getReviewMessage()
			: "This character is under review. Please don't play it until the review is complete.";

		body.add(coloured("⚠ " + state.getRsn() + " is under review", BAD));
		body.add(Box.createVerticalStrut(6));
		body.add(coloured(message, BAD));
		body.add(Box.createVerticalStrut(14));
	}

	// ── Small UI builders ──────────────────────────────────────────────────

	private JPanel statusRow(String value, Color colour)
	{
		return keyValueRow("Status", value, colour);
	}

	private JPanel keyValueRow(String name, String value, Color colour)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		JLabel key = new JLabel(name);
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

	/** A heading for a region of the panel — same weight as the panel title. */
	private static JLabel sectionTitle(String text)
	{
		return title(text);
	}

	/** The name of one selectable mode, above its description and button. */
	private static JLabel optionTitle(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(GOLD);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JSeparator divider()
	{
		JSeparator separator = new JSeparator();
		separator.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		separator.setBackground(ColorScheme.DARK_GRAY_COLOR);
		separator.setAlignmentX(Component.LEFT_ALIGNMENT);
		separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		return separator;
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
