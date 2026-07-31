package com.thecardexchange.tcg.account;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.runelite.client.util.ColorUtil;
import java.awt.Color;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import com.thecardexchange.tcg.mode.GameMode;
import com.thecardexchange.tcg.mode.GameModeManager;

/**
 * Keeps the server told which character is playing, and how it is doing.
 *
 * Three jobs, in order of importance:
 *
 * <ol>
 *   <li><b>Binding.</b> On login it tells the server who we are, which is what
 *       lets the collection, packs and item locks be resolved per character
 *       instead of per account. Nothing else works until this lands.</li>
 *   <li><b>Reporting.</b> While logged in it heartbeats every five minutes, so a
 *       CardMan run has a continuous record. Gaps in that record are what the
 *       server's anti-cheat looks at — but only gaps that contain progress, so an
 *       ordinary crash or a night's sleep costs nothing.</li>
 *   <li><b>Relaying.</b> It holds the server's verdict — mode, eligibility,
 *       whether the character is held — for the panel to render. It decides
 *       none of it.</li>
 * </ol>
 *
 * <p><b>Threading.</b> Every read of {@link Client} happens in
 * {@link #onGameTick()}, on the client thread, and is handed on as a finished
 * {@link CharacterSnapshot}. All network work runs on the shared scheduler.
 */
@Slf4j
@Singleton
public class CharacterTracker
{
	/** Matches the server's expectation; the grace window there is 2.5× this. */
	private static final long HEARTBEAT_PERIOD_SECONDS = 5 * 60;
	/** A level-up nudges an extra report, but no more often than this. */
	private static final long LEVEL_UP_DEBOUNCE_MS = 60_000;
	/** Don't retry a failed bind faster than this. */
	private static final long BIND_RETRY_MS = 30_000;

	private static final Color CHAT_COLOUR = new Color(0x8B5CF6);

	private final Client client;
	private final ExchangeApiClient api;
	private final AccountLinkManager linkManager;
	private final GameModeManager gameModeManager;
	private final ScheduledExecutorService scheduler;
	private final ChatMessageManager chatMessageManager;
	private final TheCardExchangeTcgConfig config;

	/** Last snapshot read on the client thread; the scheduler only ever reads this. */
	private final AtomicReference<CharacterSnapshot> snapshot = new AtomicReference<>(null);
	/** The server's answer about the current character. */
	private final AtomicReference<CharacterState> state = new AtomicReference<>(CharacterState.UNKNOWN);
	/** The character we have successfully bound, normalised — null when none. */
	private final AtomicReference<String> boundRsn = new AtomicReference<>(null);
	private final AtomicReference<String> mismatchMessage = new AtomicReference<>(null);

	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean binding = new AtomicBoolean(false);
	private final AtomicLong lastBindAttemptMs = new AtomicLong(0);
	private final AtomicLong lastLevelUpBeatMs = new AtomicLong(0);
	private final AtomicBoolean excludedWorld = new AtomicBoolean(false);

	private volatile ScheduledFuture<?> heartbeatFuture;
	private volatile Runnable listener;
	private volatile Runnable onBound;

	@Inject
	CharacterTracker(
		Client client,
		ExchangeApiClient api,
		AccountLinkManager linkManager,
		GameModeManager gameModeManager,
		ScheduledExecutorService scheduler,
		ChatMessageManager chatMessageManager,
		TheCardExchangeTcgConfig config)
	{
		this.client = client;
		this.api = api;
		this.linkManager = linkManager;
		this.gameModeManager = gameModeManager;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.config = config;
	}

	// ── Lifecycle ────────────────────────────────────────────────────────────

	public void start()
	{
		started.set(true);
	}

	public void stop()
	{
		started.set(false);
		stopHeartbeat();
		// Best-effort: close the session so a shutdown isn't read as a gap.
		CharacterSnapshot last = snapshot.get();
		String token = linkManager.getToken();
		if (last != null && token != null && boundRsn.get() != null)
		{
			scheduler.execute(() -> {
				try
				{
					api.characterLogout(token, last);
				}
				catch (IOException ex)
				{
					log.debug("Character logout failed on shutdown", ex);
				}
			});
		}
		clearCharacter();
	}

	/** The panel redraws whenever the server's answer changes. */
	public void setListener(@Nullable Runnable listener)
	{
		this.listener = listener;
	}

	/** Run after a successful bind — the item lock waits for this rather than racing it. */
	public void setOnBound(@Nullable Runnable onBound)
	{
		this.onBound = onBound;
	}

	// ── What the panel reads ─────────────────────────────────────────────────

	public CharacterState getState()
	{
		return state.get();
	}

	/** Null when nobody is logged in. */
	@Nullable
	public String getCurrentRsn()
	{
		CharacterSnapshot current = snapshot.get();
		return current == null ? null : current.getRsn();
	}

	@Nullable
	public String getMismatchMessage()
	{
		return mismatchMessage.get();
	}

	public boolean isOnExcludedWorld()
	{
		return excludedWorld.get();
	}

	public boolean isBound()
	{
		return boundRsn.get() != null;
	}

	@Nullable
	public CharacterSnapshot currentSnapshot()
	{
		return snapshot.get();
	}

	// ── Client-thread entry points ───────────────────────────────────────────

	/**
	 * Reads the logged-in character. Called from the plugin's {@code onGameTick},
	 * so this is the one place {@link Client} is touched.
	 */
	public void onGameTick()
	{
		if (!started.get())
		{
			return;
		}

		CharacterSnapshot current = CharacterSnapshot.capture(client);
		if (current == null)
		{
			// Logged out, or the skill table hasn't arrived yet.
			return;
		}
		snapshot.set(current);

		if (current.isExcludedWorld())
		{
			// Nothing is recorded here, and a previous character's session must not
			// be left open against a save that isn't theirs.
			if (excludedWorld.compareAndSet(false, true))
			{
				clearCharacter();
				notifyListener();
			}
			return;
		}
		excludedWorld.set(false);

		String bound = boundRsn.get();
		if (bound != null && !bound.equals(rsnKey(current.getRsn())))
		{
			// A different character is logged in. Drop everything belonging to the
			// last one before a single request can be made in its name.
			clearCharacter();
		}

		if (boundRsn.get() == null)
		{
			bind(current);
		}
	}

	/** A level went up — worth reporting sooner than the next scheduled beat. */
	public void onLevelUp()
	{
		if (boundRsn.get() == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		long last = lastLevelUpBeatMs.get();
		if (now - last < LEVEL_UP_DEBOUNCE_MS || !lastLevelUpBeatMs.compareAndSet(last, now))
		{
			return;
		}
		beat();
	}

	/** Logged out or hopping: close the session cleanly and forget the character. */
	public void onLoggedOut()
	{
		CharacterSnapshot last = snapshot.get();
		String token = linkManager.getToken();
		boolean wasBound = boundRsn.get() != null;
		stopHeartbeat();

		if (wasBound && last != null && token != null)
		{
			scheduler.execute(() -> {
				try
				{
					api.characterLogout(token, last);
				}
				catch (IOException ex)
				{
					// A missed logout only costs a gap with no XP in it, which is free.
					log.debug("Character logout failed", ex);
				}
			});
		}
		clearCharacter();
		snapshot.set(null);
		notifyListener();
	}

	// ── Server conversation (scheduler thread) ───────────────────────────────

	private void bind(CharacterSnapshot current)
	{
		String token = linkManager.getToken();
		if (token == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastBindAttemptMs.get() < BIND_RETRY_MS || !binding.compareAndSet(false, true))
		{
			return;
		}
		lastBindAttemptMs.set(now);

		scheduler.execute(() -> {
			try
			{
				CharacterState bound = api.bindCharacter(token, current);
				boundRsn.set(rsnKey(current.getRsn()));
				mismatchMessage.set(null);
				applyState(bound);
				startHeartbeat();
				Runnable callback = onBound;
				if (callback != null)
				{
					callback.run();
				}
			}
			catch (ExchangeApiClient.CharacterClaimed ex)
			{
				// Requirement made visible: this character belongs to another
				// account, so nothing further is sent for it.
				mismatchMessage.set(ex.getMessage());
				state.set(CharacterState.UNKNOWN);
				notifyListener();
			}
			catch (IOException ex)
			{
				log.debug("Could not bind character", ex);
			}
			finally
			{
				binding.set(false);
			}
		});
	}

	private void startHeartbeat()
	{
		stopHeartbeat();
		heartbeatFuture = scheduler.scheduleWithFixedDelay(
			this::beat, HEARTBEAT_PERIOD_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS);
	}

	private void stopHeartbeat()
	{
		ScheduledFuture<?> future = heartbeatFuture;
		if (future != null)
		{
			future.cancel(false);
			heartbeatFuture = null;
		}
	}

	private void beat()
	{
		String token = linkManager.getToken();
		CharacterSnapshot current = snapshot.get();
		if (token == null || current == null || boundRsn.get() == null || current.isExcludedWorld())
		{
			return;
		}
		scheduler.execute(() -> {
			try
			{
				applyState(api.heartbeat(token, current));
			}
			catch (ExchangeApiClient.CharacterNotBound ex)
			{
				// The binding went away underneath us; re-bind on the next tick.
				clearCharacter();
			}
			catch (IOException ex)
			{
				log.debug("Heartbeat failed", ex);
			}
		});
	}

	/**
	 * Asks the server to set this character's mode. The server is the authority —
	 * it checks the account type, that the account is untouched and what the
	 * hiscores say — so this only writes locally once the server has said yes.
	 */
	public void chooseMode(GameMode mode, boolean acknowledgedRules, Runnable onDone,
		java.util.function.Consumer<String> onRefused)
	{
		String token = linkManager.getToken();
		CharacterSnapshot current = snapshot.get();
		if (token == null || current == null)
		{
			onRefused.accept("Log in to Old School RuneScape first.");
			return;
		}

		scheduler.execute(() -> {
			try
			{
				api.selectMode(token, current, mode.name(), acknowledgedRules);
				gameModeManager.cacheServerMode(mode);
				state.set(new CharacterState(
					current.getRsn(), true, mode, "NONE", null));
				announce("Game mode set to " + mode.getDisplayName() + ".");
				onDone.run();
				notifyListener();
			}
			catch (ExchangeApiClient.ModeRefused ex)
			{
				onRefused.accept(ex.getMessage());
			}
			catch (ExchangeApiClient.CharacterUnderReview ex)
			{
				onRefused.accept(ex.getMessage());
			}
			catch (IOException ex)
			{
				onRefused.accept("Could not reach the exchange. Try again in a moment.");
			}
		});
	}

	// ── Internals ────────────────────────────────────────────────────────────

	private void applyState(CharacterState next)
	{
		CharacterState previous = state.get();
		state.set(next);
		// Cache the server's answer so the panel can render a mode instantly on the
		// next login rather than waiting for a round trip.
		if (next.hasMode())
		{
			gameModeManager.cacheServerMode(next.getGameMode());
		}
		if (next.isHeld() && !previous.isHeld() && next.getReviewMessage() != null)
		{
			announce(next.getReviewMessage());
		}
		notifyListener();
	}

	private void clearCharacter()
	{
		stopHeartbeat();
		boundRsn.set(null);
		state.set(CharacterState.UNKNOWN);
	}

	private void notifyListener()
	{
		Runnable current = listener;
		if (current != null)
		{
			current.run();
		}
	}

	private void announce(String message)
	{
		if (!config.chatNotifications())
		{
			return;
		}
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(ColorUtil.wrapWithColorTag("[Card Exchange] " + message, CHAT_COLOUR))
			.build());
	}

	private static String rsnKey(String name)
	{
		return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase();
	}
}
