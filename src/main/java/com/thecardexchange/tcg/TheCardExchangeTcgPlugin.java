package com.thecardexchange.tcg;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.CharacterTracker;
import com.thecardexchange.tcg.items.ItemLockManager;
import com.thecardexchange.tcg.packs.CardPacksManager;
import com.thecardexchange.tcg.trade.CardTradeManager;

/**
 * OSRS TCG (TheCardExchange) — turns Old School RuneScape into a card game backed by the
 * OSRS Card Exchange.
 *
 * <p>This first cut is the front door: it links the plugin to an exchange account
 * through a device-pairing flow (a short code the player confirms on the website
 * while signed in), and holds the resulting plugin token. Everything the game
 * layer will later do — packs, collections, trading — authenticates with that
 * token, so linking is the foundation the rest is built on.
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS TCG (TheCardExchange)",
	description = "Turn Old School RuneScape into a card game. Link your osrscardexchange.com account to start."
)
public class TheCardExchangeTcgPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private TheCardExchangeTcgPanel panel;

	@Inject
	private AccountLinkManager linkManager;

	@Inject
	private CardTradeManager cardTradeManager;

	@Inject
	private CardPacksManager cardPacksManager;

	@Inject
	private ItemLockManager itemLockManager;

	@Inject
	private CharacterTracker characterTracker;

	@Inject
	private Client client;

	private NavigationButton navButton;

	@Provides
	TheCardExchangeTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TheCardExchangeTcgConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("OSRS TCG (TheCardExchange) starting up");

		// Redraw the panel whenever the link state changes (runs off the scheduler thread; the panel
		// hops to the EDT itself).
		linkManager.setStatusListener(panel::refresh);
		characterTracker.setListener(panel::refresh);
		// The item lock reads a per-character collection, so it waits for the bind
		// rather than racing it on the login tick.
		characterTracker.setOnBound(itemLockManager::refresh);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("OSRS TCG (TheCardExchange)")
			.icon(icon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Load and verify any stored token, then draw the resulting state.
		linkManager.start();
		// Start the trade event long-poll + wire the trade dialogue.
		cardTradeManager.start();
		// Paint the Card Packs orb into the minimap orb column.
		cardPacksManager.start();
		// Grey out and block items this character has no card for.
		itemLockManager.start();
		// Bind the character on login, then report while it plays.
		characterTracker.start();
		panel.refresh();
	}

	@Override
	protected void shutDown()
	{
		log.info("OSRS TCG (TheCardExchange) shutting down");
		linkManager.setStatusListener(null);
		characterTracker.setListener(null);
		characterTracker.setOnBound(null);
		characterTracker.stop();
		linkManager.stop();
		cardTradeManager.stop();
		cardPacksManager.stop();
		itemLockManager.stop();
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
	}

	/**
	 * Adds the "Trade cards" entry to another player's right-click menu. Delegated to
	 * {@link CardTradeManager}; the subscription lives here because the plugin class is what the event bus
	 * registers automatically.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		cardTradeManager.onMenuOpened(event);
	}

	/**
	 * Keep the trade long-poll pointed at whoever we're logged in as. Read on the client thread (where the
	 * local player is safe to touch) and handed to the managers as a plain string — the linking flow needs
	 * the same name for the confirm screen's label, and it runs on the scheduler.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		String rsn = local != null ? local.getName() : null;
		cardTradeManager.setCurrentRsn(rsn);
		linkManager.setCurrentRsn(rsn);
		// Reads the character (levels, XP, account type) on the client thread and
		// drives binding + heartbeats from it. The one place the client is touched.
		characterTracker.onGameTick();
		linkManager.setPendingSnapshot(characterTracker.currentSnapshot());
		// Keep an incoming trade-request chat line clickable while it's live.
		cardTradeManager.onGameTick();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			// Find out what this character has unlocked before they can click anything.
			itemLockManager.onLoggedIn();
			return;
		}

		// LOADING is still being logged in — it fires on every region change and every
		// stairwell — so it must not tear anything down. Everything else means the
		// character is gone.
		if (state == GameState.LOADING)
		{
			return;
		}

		cardTradeManager.setCurrentRsn(null);
		linkManager.setCurrentRsn(null);
		// Closes the server-side session, so a night away is not an unwatched gap.
		characterTracker.onLoggedOut();
		itemLockManager.onLoggedOut();

		// Losing the character closes every painted interface. They swallow all mouse
		// and key input inside their bounds by design, so one left open after a
		// disconnect would eat clicks at the login screen with its own close button
		// no longer reachable.
		cardPacksManager.closeAll();
		cardTradeManager.closeInterfaces();
	}

	/**
	 * Greys the menu entry for an item the character has no card for. Delegated to
	 * {@link ItemLockManager}; this fires for every entry of every menu, so it stays a straight hand-off.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		itemLockManager.onMenuEntryAdded(event);
	}

	/** Blocks the click on a locked item — everything the lock allows is decided in the manager. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		itemLockManager.onMenuOptionClicked(event);
	}

	/**
	 * A level-up is worth telling the server about sooner than the next scheduled report — it is the
	 * event a CardMan run is actually made of. Debounced in the tracker.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		characterTracker.onLevelUp();
	}
}
