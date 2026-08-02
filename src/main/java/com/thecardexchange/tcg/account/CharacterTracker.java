package com.thecardexchange.tcg.account;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
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
import net.runelite.api.Skill;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.ColorUtil;
import java.awt.Color;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import com.thecardexchange.tcg.mode.GameMode;
import com.thecardexchange.tcg.mode.GameModeManager;
import com.thecardexchange.tcg.packs.Wallet;

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
	/**
	 * How quickly an earning event may pull a report forward.
	 *
	 * <p>Short, because it is now only spent on things that actually pay — a real level, a finished
	 * quest, a completed diary tier — rather than on every XP drop. The old 60s was a throttle on
	 * {@code StatChanged}, which fires on *every* XP gain, so training anything at all produced a
	 * report a minute whether or not a single credit was owed. Detecting the level instead means the
	 * quiet case sends nothing and the paying case pays in seconds.
	 */
	private static final long EARNED_DEBOUNCE_MS = 5_000;
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
	private final Wallet wallet;

	/** Last snapshot read on the client thread; the scheduler only ever reads this. */
	private final AtomicReference<CharacterSnapshot> snapshot = new AtomicReference<>(null);
	/** The server's answer about the current character. */
	private final AtomicReference<CharacterState> state = new AtomicReference<>(CharacterState.UNKNOWN);
	/** The character we have successfully bound, normalised — null when none. */
	private final AtomicReference<String> boundRsn = new AtomicReference<>(null);
	private final AtomicReference<String> mismatchMessage = new AtomicReference<>(null);
	/**
	 * Set when the account holder unlinked this character from their profile.
	 *
	 * <p>Kept separate from {@link #mismatchMessage}: one is "this belongs to somebody else", the other
	 * is "you released this yourself". They need different words, and only the second is undone from
	 * the player's own profile page.
	 */
	private final AtomicReference<String> releasedMessage = new AtomicReference<>(null);

	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean binding = new AtomicBoolean(false);
	private final AtomicLong lastBindAttemptMs = new AtomicLong(0);
	private final AtomicLong lastEarnedBeatMs = new AtomicLong(0);
	/**
	 * Real level last seen per skill, so {@link #onStatChanged} can tell a level from an XP drop.
	 * Cleared with the character — an alt's levels must never look like the main's having dropped.
	 */
	private final Map<Skill, Integer> seenLevels = new EnumMap<>(Skill.class);
	/**
	 * Finished quests + completed diary tiers as of the last report the server accepted, or -1 before
	 * there has been one. Compared each tick so completing either pulls the next report forward.
	 */
	private final AtomicInteger reportedMilestones = new AtomicInteger(-1);
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
		TheCardExchangeTcgConfig config,
		Wallet wallet)
	{
		this.client = client;
		this.api = api;
		this.linkManager = linkManager;
		this.gameModeManager = gameModeManager;
		this.scheduler = scheduler;
		this.chatMessageManager = chatMessageManager;
		this.config = config;
		this.wallet = wallet;
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

	/**
	 * The mode to render by: the server's answer once the character is bound, else the cached one.
	 *
	 * <p>Single source for that decision. The panel and every card renderer ask here rather than
	 * repeating it, because a disagreement between them would show a player CardMan-coloured cards
	 * while the panel said Normal.
	 */
	public GameMode activeGameMode()
	{
		CharacterState current = state.get();
		return current.isBound() ? current.getGameMode() : gameModeManager.getGameMode();
	}

	/** Set when this character was unlinked from the account's profile; null otherwise. */
	@Nullable
	public String getReleasedMessage()
	{
		return releasedMessage.get();
	}

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

		// A quest or a diary tier finishing is worth reporting now rather than at the
		// next scheduled beat. The counts are already in the snapshot `capture` built
		// this tick, so noticing costs a comparison rather than another varbit sweep.
		reportEarnedMilestones(current);

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
	/**
	 * Nudges a report when the quest or diary count has grown since the server last heard.
	 *
	 * <p>Compared against what was actually <em>reported</em>, not against the previous tick: a beat
	 * that failed leaves the count unchanged, so the nudge keeps firing until one gets through rather
	 * than being spent on a request that never landed.
	 */
	private void reportEarnedMilestones(CharacterSnapshot current)
	{
		if (boundRsn.get() == null)
		{
			return;
		}
		int reported = reportedMilestones.get();
		if (reported >= 0 && milestoneCount(current) > reported)
		{
			nudge();
		}
	}

	private static int milestoneCount(CharacterSnapshot snapshot)
	{
		return snapshot.getCompletedQuests().size() + snapshot.getCompletedDiaries().size();
	}

	/**
	 * A skill's real level changed — report it if it went <em>up</em>.
	 *
	 * <p>{@code StatChanged} fires on every XP gain, so the level has to be compared rather than
	 * assumed: without this the plugin reported on a timer while training and stayed silent when the
	 * level actually landed. The first value seen for a skill is login, not a level-up, so it seeds the
	 * map and nudges nothing.
	 */
	public void onStatChanged(Skill skill, int level)
	{
		if (skill == null || skill == Skill.OVERALL || boundRsn.get() == null)
		{
			return;
		}
		Integer previous = seenLevels.put(skill, level);
		if (previous != null && level > previous)
		{
			nudge();
		}
	}

	/**
	 * Pulls the next report forward because something that pays just happened.
	 *
	 * <p>Debounced rather than queued: several levels in a row are one report, because the server
	 * derives what it owes from the XP in the snapshot rather than from how many times it was told.
	 * Anything the debounce swallows is picked up by the next beat, and the ratchet means a late report
	 * pays exactly the same as a prompt one — only later.
	 */
	private void nudge()
	{
		long now = System.currentTimeMillis();
		long last = lastEarnedBeatMs.get();
		if (now - last < EARNED_DEBOUNCE_MS || !lastEarnedBeatMs.compareAndSet(last, now))
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
				releasedMessage.set(null);
				applyState(bound);
				startHeartbeat();
				Runnable callback = onBound;
				if (callback != null)
				{
					callback.run();
				}
			}
			catch (ExchangeApiClient.Unauthorised ex)
			{
				// The token is dead — revoked, or its account no longer exists. Retrying
				// cannot help, so drop the link instead of binding every 30s for ever
				// while the panel still claims to be linked.
				clearCharacter();
				linkManager.onUnauthorised();
			}
			catch (ExchangeApiClient.CharacterReleased ex)
			{
				// Unlinked by its own holder. Only they can undo it, from the
				// profile page, so there is nothing to retry into.
				releasedMessage.set(ex.getMessage());
				state.set(CharacterState.UNKNOWN);
				notifyListener();
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
				// Only once the server has it: a failed report must leave the count
				// alone so the nudge keeps trying.
				reportedMilestones.set(milestoneCount(current));
			}
			catch (ExchangeApiClient.CharacterNotBound ex)
			{
				// The binding went away underneath us; re-bind on the next tick.
				clearCharacter();
			}
			catch (ExchangeApiClient.Unauthorised ex)
			{
				// Same as on bind: a dead token is terminal, so unlink rather than
				// heartbeat into a wall every five minutes.
				clearCharacter();
				linkManager.onUnauthorised();
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
				gameModeManager.applyServerMode(mode);
				// Carry the wallet across rather than resetting it — mode selection says nothing
				// about the balance, and blanking it here would put the pack orb's pip out until
				// the next heartbeat for no reason. The two one-shot credit fields *are* zeroed:
				// nothing was granted or awarded by choosing a mode.
				CharacterState previous = state.get();
				state.set(new CharacterState(current.getRsn(), true, mode, "NONE", null,
					previous.getCredits(), previous.getPackPrice(), 0, 0, null));
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

		// The balance rides on every character response, which is the only reason a credit earned by
		// levelling up shows on the orb without the player opening anything.
		wallet.apply(next.getCredits(), next.getPackPrice());
		announceCredits(next);

		// Cache the server's answer so the panel can render a mode instantly on the
		// next login rather than waiting for a round trip.
		//
		// Applied whether or not a mode is set: "the server says none" is an answer
		// too, and the cache has to be able to follow it down. Guarded on `bound`
		// because an unbound state is *no* answer — nothing was heard — and evicting
		// on that would throw the cache away on every failed bind.
		if (next.isBound())
		{
			gameModeManager.applyServerMode(next.getGameMode());
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
		// Forget the wallet with the character. Hopping to an alt must not leave the main's balance
		// lit up on the pack orb — one character's holdings shown against another is the exact class
		// of bug the server-side character binding exists to prevent.
		wallet.clear();
		// Same reasoning for the earning triggers: an alt's lower levels must not read as the main's
		// having dropped, and its quest count must not read as a hundred finished at once.
		seenLevels.clear();
		reportedMilestones.set(-1);
	}

	/**
	 * Says out loud what the server just paid, when it paid something.
	 *
	 * <p>The payoff moment of the whole reward: gaining a level, finishing a quest or completing a
	 * diary tier pulls a report forward within seconds, so "+550 credits" lands while the player is
	 * still looking at the fireworks. Both messages are
	 * one-shot — they are only ever set on the response that caused them, so an ordinary re-bind or a
	 * quiet heartbeat says nothing.
	 */
	private void announceCredits(CharacterState next)
	{
		if (next.getGrantedCredits() > 0)
		{
			announce("Welcome! " + formatCredits(next.getGrantedCredits())
				+ " credits added to get you started.");
		}
		if (next.getCreditsAwarded() > 0)
		{
			// The server names what it paid for; "for levelling up" would be wrong now
			// that quests and diaries pay too, and only the server knows which of the
			// two this beat was.
			String detail = next.getCreditsDetail();
			announce("+" + formatCredits(next.getCreditsAwarded()) + " credits"
				+ (detail == null || detail.isEmpty() ? "." : " — " + detail + "."));
		}
	}

	private static String formatCredits(int amount)
	{
		return String.format("%,d", amount);
	}

	private void notifyListener()
	{
		Runnable current = listener;
		if (current != null)
		{
			current.run();
		}
	}

	/**
	 * Says something in chat as the plugin, if the player wants chat notifications.
	 *
	 * <p>Public so the pack window can announce a daily bonus through the same gate and the same
	 * purple prefix — a second chat path would be a second place for the config toggle to be
	 * forgotten.
	 */
	public void announce(String message)
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
