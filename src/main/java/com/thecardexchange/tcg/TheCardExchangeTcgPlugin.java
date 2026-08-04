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
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WorldChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.CharacterTracker;
import com.thecardexchange.tcg.items.ItemLockManager;
import com.thecardexchange.tcg.packs.CardPacksManager;
import com.thecardexchange.tcg.ui.BlockedNotice;
import com.thecardexchange.tcg.network.NetworkBadge;
import com.thecardexchange.tcg.network.NetworkBadgeDecorator;
import com.thecardexchange.tcg.network.NetworkBadgeOverlay;
import com.thecardexchange.tcg.network.NetworkPresence;
import com.thecardexchange.tcg.trade.CardTradeManager;

/**
 * TCG Online (TheCardExchange) — turns Old School RuneScape into a card game
 * backed by the OSRS Card Exchange.
 *
 * <p>The parenthetical is part of the plugin's name in the hub list, where it
 * says whose TCG Online this is; the game itself is called TCG Online, and that
 * is what the panel, the website and every line of copy call it.
 *
 * <p>This first cut is the front door: it links the plugin to an exchange account
 * through a device-pairing flow (a short code the player confirms on the website
 * while signed in), and holds the resulting plugin token. Everything the game
 * layer will later do — packs, collections, trading — authenticates with that
 * token, so linking is the foundation the rest is built on.
 */
@Slf4j
@PluginDescriptor(
	name = "TCG Online (TheCardExchange)",
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

	@Inject
	private TheCardExchangeTcgConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private FeatureGate featureGate;

	@Inject
	private BlockedNotice blockedNotice;

	@Inject
	private NetworkPresence networkPresence;

	@Inject
	private NetworkBadge networkBadge;

	@Inject
	private NetworkBadgeDecorator networkBadgeDecorator;

	@Inject
	private NetworkBadgeOverlay networkBadgeOverlay;

	@Inject
	private ApiEndpoint apiEndpoint;

	@Inject
	private ClashingPlugins clashingPlugins;

	private NavigationButton navButton;

	/**
	 * The clashing-plugin warning is once a session, not once a login. World hops and lobby trips
	 * both come back through LOGGED_IN, and a box that reappears every time is the kind of thing
	 * players disable a plugin over.
	 */
	private boolean warnedAboutClashes;

	@Provides
	TheCardExchangeTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TheCardExchangeTcgConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("TCG Online starting up");

		// Before anything reads a URL: a localhost value persisted by an older build sits in config and
		// silently outranks every -D flag and env var, which reads as the plugin ignoring what it was
		// told. Clear it, then say out loud where requests are going — the answer used to be knowable
		// only by opening the profile's .properties by hand.
		apiEndpoint.clearStaleLocalOverrides();
		log.info("TCG Online backend: {}", apiEndpoint.describe());

		// Redraw the panel whenever the link state changes (runs off the scheduler thread; the panel
		// hops to the EDT itself).
		linkManager.setStatusListener(panel::refresh);
		characterTracker.setListener(() ->
		{
			panel.refresh();
			// A hold or an unlink can land mid-session, so the windows have to shut
			// themselves rather than wait for the next click to be refused — a
			// collection you can still browse does not look blocked.
			if (!featureGate.isPlayable())
			{
				cardPacksManager.closeAll();
				blockedNotice.show();
			}
		});
		// The item lock reads a per-character collection, so it waits for the bind
		// rather than racing it on the login tick.
		characterTracker.setOnBound(itemLockManager::refresh);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/com/thecardexchange/tcg/panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("TCG Online (TheCardExchange)")
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
		// The network badge: who else is online, and the icon that marks them.
		overlayManager.add(networkBadgeOverlay);
		blockedNotice.start();
		// The mod-icon table only exists once the game has loaded, so this is a
		// no-op until then and is retried on every login.
		clientThread.invokeLater(() -> networkBadge.install(client));
		panel.refresh();
	}

	@Override
	protected void shutDown()
	{
		log.info("TCG Online shutting down");
		linkManager.setStatusListener(null);
		characterTracker.setListener(null);
		characterTracker.setOnBound(null);
		characterTracker.stop();
		linkManager.stop();
		cardTradeManager.stop();
		cardPacksManager.stop();
		itemLockManager.stop();
		overlayManager.remove(networkBadgeOverlay);
		blockedNotice.stop();
		networkPresence.clear();
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
		// Which world we are on decides which API region we belong to, so both players in a trade reach
		// the same broker. Read here because this is the client thread; ApiEndpoint holds it for the
		// OkHttp and scheduler threads that actually build URLs.
		apiEndpoint.setWorld(client.getWorld());
		cardTradeManager.setCurrentRsn(rsn);
		linkManager.setCurrentRsn(rsn);
		// Reads the character (levels, XP, account type) on the client thread and
		// drives binding + heartbeats from it. The one place the client is touched.
		characterTracker.onGameTick();
		linkManager.setPendingSnapshot(characterTracker.currentSnapshot());
		// Keep an incoming trade-request chat line clickable while it's live.
		cardTradeManager.onGameTick();
		// Refreshes the online-member list when it's due; free while logged out.
		networkPresence.tick();
	}

	/**
	 * A world hop can move us to the other API region — the two instances do not share the trade
	 * broker's offer state, so staying on the old one would leave us invisible to everyone on the world
	 * we are now standing in. Recording the world here rather than waiting for the next tick means the
	 * reconnect starts immediately; {@link CardTradeManager#setCurrentRsn} is what notices and acts,
	 * because the region can also settle without a hop when the world list finishes loading.
	 */
	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		apiEndpoint.setWorld(client.getWorld());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			// Find out what this character has unlocked before they can click anything.
			itemLockManager.onLoggedIn();
			// The mod-icon table is built as the game loads, so the badge can only be
			// registered once we are in. Idempotent — a world hop must not leak a slot.
			networkBadge.install(client);
			// Here rather than in startUp(): the notice paints on the game canvas, which
			// does not exist at the login screen.
			warnAboutClashingPlugins();
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
		networkPresence.clear();

		// Losing the character closes every painted interface. They swallow all mouse
		// and key input inside their bounds by design, so one left open after a
		// disconnect would eat clicks at the login screen with its own close button
		// no longer reachable.
		cardPacksManager.closeAll();
		cardTradeManager.closeInterfaces();
	}

	/**
	 * Warns once if another plugin covering the same ground is enabled — see {@link ClashingPlugins}
	 * for which, and why matching them is best-effort.
	 *
	 * <p>Advice, not enforcement: nothing here disables anything. Which plugins a player runs is
	 * theirs to decide, and the failure mode without the warning is that this plugin looks broken
	 * when two of them fight over the same item locks and menu entries.
	 *
	 * <p>The flag is set whether or not anything was found, so the detection runs once rather than on
	 * every world hop.
	 */
	private void warnAboutClashingPlugins()
	{
		if (warnedAboutClashes)
		{
			return;
		}
		warnedAboutClashes = true;

		final java.util.List<String> clashes = clashingPlugins.enabledClashes();
		if (clashes.isEmpty())
		{
			return;
		}
		blockedNotice.show("Plugin conflict", ClashingPlugins.message(clashes));
	}

	/**
	 * Greys the menu entry for an item the character has no card for. Delegated to
	 * {@link ItemLockManager}; this fires for every entry of every menu, so it stays a straight hand-off.
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		itemLockManager.onMenuEntryAdded(event);
		networkBadgeDecorator.decorateMenuEntry(event);
	}

	/**
	 * Puts the network badge in front of a member's name in chat.
	 *
	 * <p>A chat line is already built by the time this fires, so the name is rewritten on the message
	 * node and the chat manager asked to render it again — the only way to change one after the fact.
	 */
	/**
	 * Pushes the "Show me as online" setting to the server when it changes.
	 *
	 * <p>The setting has to live server-side to mean anything — a client can decline to draw its own
	 * badge, but only the server can stop publishing somebody. This handler is just the way the choice
	 * gets there; the server is what enforces it.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!TheCardExchangeTcgConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("networkShowMeOnline".equals(event.getKey()))
		{
			networkPresence.setVisibility(config.networkShowMeOnline());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String decorated = networkBadgeDecorator.decorateChatName(event);
		if (decorated == null)
		{
			return;
		}
		// Setting the name on the node is the whole update — ChatMessageManager.update()
		// is a no-op and the plugin hub disallows calling it.
		event.getMessageNode().setName(decorated);
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
		// Fires on every XP gain, so the tracker compares the level rather than
		// treating the event itself as a level-up.
		characterTracker.onStatChanged(event.getSkill(), event.getLevel());
	}
}
