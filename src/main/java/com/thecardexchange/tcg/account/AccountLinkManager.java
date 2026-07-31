package com.thecardexchange.tcg.account;

import java.awt.Color;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;

/**
 * Owns the account-linking lifecycle: opening a handshake, polling until the
 * website confirms it, and holding the resulting plugin token. The token is the
 * plugin's proof of "I am signed in as this account", so everything the plugin
 * will later do against the exchange (opening packs, syncing a collection,
 * trading) authenticates with it.
 *
 * <p>All network work runs on the shared scheduler; the panel registers a
 * listener and is told to redraw whenever the state changes. Nothing here
 * touches Swing — the listener hops to the EDT itself.
 */
@Slf4j
@Singleton
public class AccountLinkManager
{
	/** Config keys we own — not user-facing settings, so read/written raw rather than as @ConfigItems. */
	private static final String TOKEN_KEY = "pluginToken";
	private static final String LINKED_EMAIL_KEY = "linkedEmail";
	private static final String LINKED_NAME_KEY = "linkedOsrsName";

	/** A handshake the website never confirmed is abandoned after this. Matches the server's TTL. */
	private static final long LINK_TTL_MS = 10L * 60L * 1000L;
	/** Give up polling after this many back-to-back transport failures rather than hammering a dead server. */
	private static final int MAX_CONSECUTIVE_POLL_FAILURES = 5;

	private final ExchangeApiClient api;
	private final ConfigManager configManager;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final Client client;
	private final TheCardExchangeTcgConfig config;

	private final AtomicReference<LinkState> state = new AtomicReference<>(LinkState.NOT_LINKED);
	private final AtomicReference<LinkHandshake> handshake = new AtomicReference<>(null);
	private final AtomicReference<LinkedAccount> account = new AtomicReference<>(null);
	private final AtomicReference<String> errorMessage = new AtomicReference<>(null);

	private final AtomicLong pollDeadlineMs = new AtomicLong(0L);
	private final AtomicInteger consecutivePollFailures = new AtomicInteger(0);
	private final AtomicBoolean started = new AtomicBoolean(false);

	private volatile ScheduledFuture<?> pollFuture;
	private volatile Runnable statusListener;

	@Inject
	AccountLinkManager(
		ExchangeApiClient api,
		ConfigManager configManager,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		Client client,
		TheCardExchangeTcgConfig config)
	{
		this.api = api;
		this.configManager = configManager;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.client = client;
		this.config = config;
	}

	// ── Lifecycle ────────────────────────────────────────────────────────────

	/** Loads any stored token and verifies it against the server in the background. */
	public void start()
	{
		if (!started.compareAndSet(false, true))
		{
			return;
		}
		String token = storedToken();
		if (token == null)
		{
			setState(LinkState.NOT_LINKED, null);
			return;
		}
		// Show the last-known account immediately, then confirm the token is still good.
		account.set(new LinkedAccount(stored(LINKED_EMAIL_KEY), stored(LINKED_NAME_KEY)));
		setState(LinkState.LINKED, null);
		scheduler.execute(() -> verifyStoredToken(token));
	}

	public void stop()
	{
		if (!started.compareAndSet(true, false))
		{
			return;
		}
		stopPolling();
	}

	// ── Actions the panel calls ──────────────────────────────────────────────

	/** Begins linking: opens a handshake and starts polling. No-op if already linked or mid-flight. */
	public void beginLink()
	{
		LinkState current = state.get();
		if (current == LinkState.STARTING || current == LinkState.AWAITING_CONFIRMATION || current == LinkState.LINKED)
		{
			return;
		}
		errorMessage.set(null);
		setState(LinkState.STARTING, null);
		scheduler.execute(this::openHandshake);
	}

	/** Abandons an in-progress link attempt and returns to the unlinked state. */
	public void cancelLink()
	{
		stopPolling();
		handshake.set(null);
		if (state.get() != LinkState.LINKED)
		{
			setState(LinkState.NOT_LINKED, null);
		}
	}

	/** Unlinks: forgets the token locally and revokes it server-side, best-effort. */
	public void unlink()
	{
		stopPolling();
		String token = storedToken();
		clearStoredLink();
		handshake.set(null);
		account.set(null);
		setState(LinkState.NOT_LINKED, null);
		if (token != null)
		{
			scheduler.execute(() ->
			{
				try
				{
					api.logout(token);
				}
				catch (Exception ex)
				{
					log.debug("Best-effort token revoke failed", ex);
				}
			});
		}
	}

	// ── Flow internals ───────────────────────────────────────────────────────

	private void openHandshake()
	{
		try
		{
			LinkHandshake started = api.startLink(currentLabel());
			handshake.set(started);
			consecutivePollFailures.set(0);
			pollDeadlineMs.set(System.currentTimeMillis() + LINK_TTL_MS);
			setState(LinkState.AWAITING_CONFIRMATION, null);

			stopPolling();
			int interval = Math.max(2, started.getIntervalSeconds());
			pollFuture = scheduler.scheduleWithFixedDelay(
				this::pollTick, interval, interval, TimeUnit.SECONDS);
		}
		catch (Exception ex)
		{
			log.debug("Could not open link handshake", ex);
			setState(LinkState.ERROR, "Could not reach the exchange. Check the API URL and try again.");
		}
	}

	private void pollTick()
	{
		if (state.get() != LinkState.AWAITING_CONFIRMATION)
		{
			stopPolling();
			return;
		}
		LinkHandshake current = handshake.get();
		if (current == null)
		{
			stopPolling();
			return;
		}
		if (System.currentTimeMillis() > pollDeadlineMs.get())
		{
			stopPolling();
			setState(LinkState.ERROR, "This code expired. Start linking again.");
			return;
		}

		try
		{
			PollResult result = api.poll(current.getDeviceSecret());
			consecutivePollFailures.set(0);
			switch (result.getStatus())
			{
				case LINKED:
					onLinked(result.getPluginToken(), result.getAccount());
					break;
				case EXPIRED:
					stopPolling();
					setState(LinkState.ERROR, "This code expired. Start linking again.");
					break;
				case PENDING:
				default:
					// Keep waiting; nothing to do.
					break;
			}
		}
		catch (Exception ex)
		{
			// Transport hiccup: tolerate a few, then give up rather than polling a dead server forever.
			if (consecutivePollFailures.incrementAndGet() >= MAX_CONSECUTIVE_POLL_FAILURES)
			{
				stopPolling();
				setState(LinkState.ERROR, "Lost contact with the exchange while linking. Try again.");
			}
		}
	}

	private void onLinked(String token, @Nullable LinkedAccount linked)
	{
		stopPolling();
		handshake.set(null);
		storeLink(token, linked);
		account.set(linked);
		setState(LinkState.LINKED, null);
		announce("44bb44", "Account linked" + (linked != null && !linked.getOsrsName().isEmpty()
			? " as " + linked.getOsrsName() : "") + ". You're all set.");
	}

	private void verifyStoredToken(String token)
	{
		try
		{
			LinkedAccount verified = api.session(token);
			if (verified == null)
			{
				// The server no longer accepts this token — it was revoked or is unknown. Drop it so the
				// player is prompted to link again rather than silently staying "linked" with a dead token.
				clearStoredLink();
				account.set(null);
				setState(LinkState.NOT_LINKED, null);
				return;
			}
			storeLink(token, verified);
			account.set(verified);
			setState(LinkState.LINKED, null);
		}
		catch (Exception ex)
		{
			// Couldn't reach the server — keep the stored token and the last-known account rather than
			// logging the player out over a transient network blip.
			log.debug("Token verification deferred (offline?)", ex);
		}
	}

	// ── State + persistence ──────────────────────────────────────────────────

	private void setState(LinkState next, @Nullable String error)
	{
		state.set(next);
		errorMessage.set(error);
		notifyStatusListener();
	}

	public void setStatusListener(Runnable listener)
	{
		this.statusListener = listener;
	}

	private void notifyStatusListener()
	{
		Runnable listener = statusListener;
		if (listener != null)
		{
			try
			{
				listener.run();
			}
			catch (Exception ex)
			{
				log.debug("Status listener failed", ex);
			}
		}
	}

	private void stopPolling()
	{
		ScheduledFuture<?> future = pollFuture;
		if (future != null)
		{
			future.cancel(false);
			pollFuture = null;
		}
	}

	/** Label the confirm screen shows: the current character's name if logged in, else a generic tag. */
	private String currentLabel()
	{
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			String name = Text.sanitize(client.getLocalPlayer().getName());
			if (name != null && !name.trim().isEmpty())
			{
				return name.trim();
			}
		}
		return "RuneLite plugin";
	}

	@Nullable
	private String storedToken()
	{
		String token = configManager.getConfiguration(TheCardExchangeTcgConfig.GROUP, TOKEN_KEY);
		return token == null || token.trim().isEmpty() ? null : token.trim();
	}

	private String stored(String key)
	{
		String value = configManager.getConfiguration(TheCardExchangeTcgConfig.GROUP, key);
		return value == null ? "" : value;
	}

	private void storeLink(String token, @Nullable LinkedAccount linked)
	{
		configManager.setConfiguration(TheCardExchangeTcgConfig.GROUP, TOKEN_KEY, token);
		configManager.setConfiguration(TheCardExchangeTcgConfig.GROUP, LINKED_EMAIL_KEY,
			linked == null ? "" : linked.getEmail());
		configManager.setConfiguration(TheCardExchangeTcgConfig.GROUP, LINKED_NAME_KEY,
			linked == null ? "" : linked.getOsrsName());
	}

	private void clearStoredLink()
	{
		configManager.unsetConfiguration(TheCardExchangeTcgConfig.GROUP, TOKEN_KEY);
		configManager.unsetConfiguration(TheCardExchangeTcgConfig.GROUP, LINKED_EMAIL_KEY);
		configManager.unsetConfiguration(TheCardExchangeTcgConfig.GROUP, LINKED_NAME_KEY);
	}

	private void announce(String colour, String message)
	{
		if (!config.chatNotifications())
		{
			return;
		}
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(
				ColorUtil.wrapWithColorTag("[Card Exchange] ", Color.decode("#" + colour)) + message)
			.build());
	}

	// ── Read-only accessors for the panel ────────────────────────────────────

	public LinkState getState()
	{
		return state.get();
	}

	@Nullable
	public LinkHandshake getHandshake()
	{
		return handshake.get();
	}

	@Nullable
	public LinkedAccount getLinkedAccount()
	{
		return account.get();
	}

	@Nullable
	public String getErrorMessage()
	{
		return errorMessage.get();
	}

	public boolean isLinked()
	{
		return state.get() == LinkState.LINKED;
	}

	/**
	 * The stored plugin token this install authenticates with, or null if not linked. Used by the trade
	 * flow to authorise requests as this account.
	 */
	@Nullable
	public String getToken()
	{
		return storedToken();
	}
}
