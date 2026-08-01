package com.thecardexchange.tcg.network;

import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.ExchangeApiClient;
import java.io.IOException;
import com.thecardexchange.tcg.mode.GameMode;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Which Card Exchange players are logged in right now.
 *
 * <p>The set is fetched whole and matched locally. That is a deliberate shape, not an optimisation:
 * asking the server about each player we happen to see would mean sending it the names of people who
 * never agreed to anything, which is precisely what a plugin must not do. Downloading a list and
 * comparing on this machine tells the server nothing about who is around us.
 *
 * <p>Everything here is best-effort, and a failed poll keeps the previous answer rather than blanking
 * every badge on a blip. Note that this is <em>not</em> only decoration: the "Trade Cards" menu entry
 * appears on players in this set, so an empty one also means nobody to trade with. Failing closed is
 * the right way round — an offer to somebody the broker cannot reach is a dead menu entry — but it is
 * why the previous answer is kept instead of cleared.
 */
@Slf4j
@Singleton
public class NetworkPresence
{
	/**
	 * How often the list is refreshed. Matches the server's own cache window, so polling faster would
	 * buy nothing but load; a player who logs in shows up to other people within a poll.
	 */
	private static final long POLL_SECONDS = 30;

	/**
	 * Consecutive failures before polling backs off.
	 *
	 * <p>A server that is down should cost a request every few minutes, not one every thirty seconds
	 * from every client at once.
	 */
	private static final int FAILURES_BEFORE_BACKOFF = 3;
	private static final long BACKOFF_SECONDS = 5 * 60;

	private final ExchangeApiClient api;
	private final AccountLinkManager linkManager;
	private final ScheduledExecutorService scheduler;

	/**
	 * Lowercased display name → the ruleset that player is on.
	 *
	 * <p>The mode travels with the name so the badge can show which of the two economies somebody is
	 * in. They cannot trade with each other, so it is worth answering on sight rather than after an
	 * offer is refused.
	 *
	 * <p>Replaced wholesale, never mutated, so readers need no lock.
	 */
	private final AtomicReference<Map<String, GameMode>> online =
		new AtomicReference<>(Collections.emptyMap());

	private final AtomicBoolean polling = new AtomicBoolean(false);
	private volatile int consecutiveFailures;
	private volatile long nextPollAtMs;

	@Inject
	NetworkPresence(
		ExchangeApiClient api,
		AccountLinkManager linkManager,
		ScheduledExecutorService scheduler)
	{
		this.api = api;
		this.linkManager = linkManager;
		this.scheduler = scheduler;
	}

	/**
	 * True when this name is a Card Exchange player who is online now.
	 *
	 * <p>Read by the badge <em>and</em> by the trade menu — the "Trade Cards" entry only appears on
	 * players who are on the network, since an offer to anyone else is one the broker can never
	 * deliver.
	 */
	public boolean isOnline(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return false;
		}
		return online.get().containsKey(ExchangeApiClient.normaliseRsn(rsn));
	}

	/**
	 * Which ruleset an online player is on, or {@link GameMode#NOT_SELECTED} when they are not on the
	 * network or have not chosen one.
	 */
	public GameMode modeOf(String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return GameMode.NOT_SELECTED;
		}
		GameMode mode = online.get().get(ExchangeApiClient.normaliseRsn(rsn));
		return mode == null ? GameMode.NOT_SELECTED : mode;
	}

	/** How many members are online, for the panel. */
	public int onlineCount()
	{
		return online.get().size();
	}

	/**
	 * Refreshes the list if it is due. Called from the game tick, so it costs nothing while logged
	 * out and needs no timer of its own to cancel.
	 */
	public void tick()
	{
		// Deliberately NOT gated on the badge config. The set is what decides who
		// can be offered a card trade as well as who wears an icon, so switching
		// the badges off must not quietly disable trading. The decorator and the
		// overlay do the display gating; this is data.
		String token = linkManager.getToken();
		if (token == null || token.isEmpty())
		{
			// Not linked: there is no token to ask with, and an unlinked install
			// is not part of the network anyway.
			online.set(Collections.emptyMap());
			return;
		}

		long now = System.currentTimeMillis();
		if (now < nextPollAtMs || !polling.compareAndSet(false, true))
		{
			return;
		}

		scheduler.execute(() ->
		{
			try
			{
				online.set(api.onlineNetworkPlayers(token));
				consecutiveFailures = 0;
				nextPollAtMs = System.currentTimeMillis() + POLL_SECONDS * 1000;
			}
			catch (IOException | RuntimeException ex)
			{
				consecutiveFailures++;
				long wait = consecutiveFailures >= FAILURES_BEFORE_BACKOFF ? BACKOFF_SECONDS : POLL_SECONDS;
				nextPollAtMs = System.currentTimeMillis() + wait * 1000;
				// The previous set is deliberately kept: a blip should not blank
				// every badge on screen.
				log.debug("could not refresh the network presence list", ex);
			}
			finally
			{
				polling.set(false);
			}
		});
	}

	/** Drops the cached set — on logout, or when the account is unlinked. */
	public void clear()
	{
		online.set(Collections.emptyMap());
		consecutiveFailures = 0;
		nextPollAtMs = 0;
	}

	/**
	 * Publishes, or stops publishing, this account's own online status.
	 *
	 * <p>The setting lives on the server because that is the only place it can be enforced: a client
	 * may hide itself, but nothing a client says can expose somebody who opted out.
	 */
	public void setVisibility(boolean visible)
	{
		String token = linkManager.getToken();
		if (token == null || token.isEmpty())
		{
			return;
		}
		scheduler.execute(() ->
		{
			try
			{
				api.setNetworkVisibility(token, visible);
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("could not update network visibility", ex);
			}
		});
	}
}
