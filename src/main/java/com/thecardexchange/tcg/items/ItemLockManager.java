package com.thecardexchange.tcg.items;

import java.awt.Color;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.CharacterTracker;
import com.thecardexchange.tcg.account.ExchangeApiClient;
import com.thecardexchange.tcg.packs.CardCatalogue;
import com.thecardexchange.tcg.packs.Holdings;
import com.thecardexchange.tcg.packs.Wallet;

/**
 * The item lock: an item you haven't earned a card for can't be used.
 *
 * <p><b>What it blocks.</b> Wearing, wielding, eating, drinking, using — the actions that get value out
 * of an item. The menu entry is greyed first so it reads as disabled before you click it, and the click
 * itself is consumed with a line in chat saying which card is missing.
 *
 * <p><b>What it never blocks</b>, by design:
 * <ul>
 *   <li><b>Picking things up.</b> You can always take an item off the ground — locking loot would stop
 *       you ever holding the thing you're trying to unlock.</li>
 *   <li><b>The bank.</b> Deposit and withdraw always work, on locked items too, so a locked drop can be
 *       put away instead of dropped.</li>
 *   <li><b>Getting rid of it</b> — Drop, Destroy, Examine, and taking gear off (Remove).</li>
 *   <li><b>Skilling that needs no tool</b> — thieving stalls, picking flax and the like. Nothing goes
 *       in, so there is nothing to have earned; what comes out is not treated as a requirement.</li>
 *   <li><b>Talking to and examining monsters.</b> An NPC you have no card for can still be spoken to and
 *       looked at — but not attacked, pickpocketed or traded with. That's what its card is for.</li>
 * </ul>
 *
 * <p><b>Gathering is gated on its tool</b> ({@link ToolRequirement}): cage-fishing is a lobster pot,
 * mining is a pickaxe, chopping is an axe. Without a card for something that could do the job the world
 * click is refused, so a spot can't hand you fish you had no way to catch.
 *
 * <p>The rule is our own collection data and nothing else: an item is unlocked when a card you own
 * <i>is</i> it or unlocks it ({@link ItemLocks}).
 *
 * <p><b>Known limitation</b>, shared by anything that works through the menu: actions fired without the
 * menu — a keybind, or the spacebar "make" on a production interface — don't pass through
 * {@link MenuOptionClicked} and so can't be consumed here.
 */
@Slf4j
@Singleton
public class ItemLockManager
{
	/** Options that always work on a locked item, matched case-insensitively as prefixes. */
	private static final String[] ALWAYS_ALLOWED = {
		"take", "drop", "destroy", "examine", "remove", "deposit", "withdraw", "bank", "store",
		"value", "sell", "buy", "cancel",
	};
	/**
	 * What you may always do to a monster you have no card for. Talking and looking cost it nothing;
	 * fighting it, robbing it or trading with it are the things the card is for.
	 *
	 * <p>The gathering options are here too — a fishing spot is an NPC, and gathering has its own rule
	 * ({@link ToolRequirement}) that gates it on the tool rather than on a card for the spot.
	 */
	private static final String[] NPC_ALWAYS_ALLOWED = {
		"talk-to", "examine", "cancel", "walk here", "net", "bait", "cage", "harpoon", "lure",
		"big net", "small net", "use-rod",
	};
	/** Colour for a greyed menu entry — the game's own "you can't do this" tone. */
	private static final String LOCKED_MENU_COLOUR = "8f8f8f";
	private static final long REFRESH_THROTTLE_MS = 5000L;

	private final ExchangeApiClient api;
	private final AccountLinkManager linkManager;
	private final CharacterTracker characterTracker;
	private final ItemLocks locks;
	private final LockedItemOverlay overlay;
	private final OverlayManager overlayManager;
	private final ChatMessageManager chatMessageManager;
	private final TheCardExchangeTcgConfig config;
	private final Wallet wallet;
	private final ScheduledExecutorService scheduler;

	private final AtomicBoolean refreshing = new AtomicBoolean();
	private volatile long lastRefreshMs;
	/** Catalogue ids are static between seeds, so they're fetched once per session. */
	private volatile boolean catalogueLoaded;

	@Inject
	ItemLockManager(
		ExchangeApiClient api,
		AccountLinkManager linkManager,
		CharacterTracker characterTracker,
		ItemLocks locks,
		LockedItemOverlay overlay,
		OverlayManager overlayManager,
		ChatMessageManager chatMessageManager,
		TheCardExchangeTcgConfig config,
		Wallet wallet,
		ScheduledExecutorService scheduler)
	{
		this.api = api;
		this.linkManager = linkManager;
		this.characterTracker = characterTracker;
		this.locks = locks;
		this.overlay = overlay;
		this.overlayManager = overlayManager;
		this.chatMessageManager = chatMessageManager;
		this.config = config;
		this.wallet = wallet;
		this.scheduler = scheduler;
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	public void start()
	{
		overlay.loadIcon();
		overlayManager.add(overlay);
	}

	public void stop()
	{
		overlayManager.remove(overlay);
		locks.clear();
	}

	/** Login: pull what this character has unlocked. Logout clears it so locks never outlive a session. */
	/**
	 * Deliberately does not fetch. The collection is resolved per character now,
	 * and at this moment the bind hasn't happened — a fetch here would race it and
	 * be refused. {@link CharacterTracker} calls {@link #refresh()} once the
	 * character is bound.
	 */
	public void onLoggedIn()
	{
		// Intentionally empty; see the note above.
	}

	public void onLoggedOut()
	{
		locks.clear();
	}

	/**
	 * Re-reads the collection. Called at login and after anything that can change what's owned (a pack,
	 * a trade), throttled so a burst of triggers is one request.
	 */
	public void refresh()
	{
		String token = linkManager.getToken();
		if (token == null || !config.lockUncollectedItems())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastRefreshMs < REFRESH_THROTTLE_MS || !refreshing.compareAndSet(false, true))
		{
			return;
		}
		lastRefreshMs = now;

		scheduler.execute(() ->
		{
			try
			{
				if (!catalogueLoaded)
				{
					applyCatalogue(api.cards(token));
				}
				// Named explicitly, so an alt is never handed the bound character's
				// unlocks — the server refuses rather than guessing.
				Holdings holdings = api.collection(token, characterTracker.getCurrentRsn());
				locks.setUnlocked(holdings.getUnlocked(), holdings.getUnlockedNpcs());
				// The same response carries the wallet. This refresh runs at login and after
				// anything that changes what's owned, so taking the balance from it is free.
				wallet.apply(holdings);
			}
			catch (ExchangeApiClient.CharacterNotBound ex)
			{
				// Not linked yet; the tracker calls back once the bind lands.
				log.debug("Item locks deferred until the character is bound");
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("Could not refresh item locks", ex);
			}
			finally
			{
				refreshing.set(false);
			}
		});
	}

	/** A collection we already have in hand (the packs interface just fetched it) — no second request. */
	public void apply(Holdings holdings)
	{
		locks.setUnlocked(holdings.getUnlocked(), holdings.getUnlockedNpcs());
	}

	public void applyCatalogue(CardCatalogue catalogue)
	{
		if (catalogue.getCollectable().isEmpty())
		{
			return;
		}
		locks.setCollectable(catalogue.getCollectable(), catalogue.getCollectableNpcs());
		// Tool requirements are answered by card name, so the names come along with the id set.
		locks.setItemNames(catalogue.getCards());
		catalogueLoaded = true;
	}

	// ── Menu ──────────────────────────────────────────────────────────────────

	/** Greys the option on a locked item, so it reads as disabled before it's clicked. */
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!enforcing())
		{
			return;
		}
		MenuEntry entry = event.getMenuEntry();
		if (!blocks(entry) && !blocksNpc(entry) && !blocksBuy(entry) && missingTool(entry) == null)
		{
			return;
		}
		entry.setOption(ColorUtil.prependColorTag(Text.removeTags(entry.getOption()),
			Color.decode("#" + LOCKED_MENU_COLOUR)));
	}

	/** Consumes the click on a locked item, and says which card is missing. */
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!enforcing())
		{
			return;
		}
		MenuEntry entry = event.getMenuEntry();

		ToolRequirement missing = missingTool(entry);
		if (missing != null)
		{
			event.consume();
			announce("You need " + missing.getLabel() + " card before you can do that.");
			return;
		}

		if (blocksBuy(entry))
		{
			event.consume();
			announce("Your coins are locked — pull the Coins card before you can buy from shops.");
			return;
		}

		if (blocksNpc(entry))
		{
			event.consume();
			String monster = Text.removeTags(entry.getTarget());
			announce((monster.isEmpty() ? "That monster" : monster)
				+ " is locked — you can talk to it and examine it until you pull its card.");
			return;
		}

		if (!blocks(entry))
		{
			return;
		}
		event.consume();

		String name = Text.removeTags(entry.getTarget());
		announce((name.isEmpty() ? "That item" : name)
			+ " is locked — open its card from a pack to use it. You can still pick it up, bank it or drop it.");
	}

	/**
	 * True when this is a shop Buy while the character's coins are locked. Spending money is using it,
	 * so buying is gated on the Coins card — the currency master (CARDS.md §4). Selling and Value stay
	 * open: checking a price costs nothing, and selling *acquires* coins, which is always allowed, the
	 * same as picking an item up.
	 */
	private boolean blocksBuy(@Nullable MenuEntry entry)
	{
		if (entry == null || entry.getItemId() <= 0 || !locks.isLocked(ItemLocks.COINS_ID))
		{
			return false;
		}
		return Text.removeTags(entry.getOption()).toLowerCase(Locale.ROOT).trim().startsWith("buy");
	}

	/**
	 * True when this entry acts on a monster whose card the character hasn't earned. Talk-to and Examine
	 * always survive; Attack, Pickpocket, Trade and the rest are what the card buys.
	 */
	private boolean blocksNpc(@Nullable MenuEntry entry)
	{
		if (entry == null)
		{
			return false;
		}
		MenuAction type = entry.getType();
		if (type == MenuAction.RUNELITE || type == MenuAction.RUNELITE_OVERLAY
			|| type == MenuAction.RUNELITE_HIGH_PRIORITY || type == MenuAction.RUNELITE_LOW_PRIORITY
			|| type == MenuAction.EXAMINE_NPC || type == MenuAction.CANCEL || type == MenuAction.WALK)
		{
			return false;
		}
		NPC npc = entry.getNpc();
		if (npc == null || !locks.isNpcLocked(npc.getId()))
		{
			return false;
		}
		String option = Text.removeTags(entry.getOption()).toLowerCase(Locale.ROOT).trim();
		for (String allowed : NPC_ALWAYS_ALLOWED)
		{
			if (option.startsWith(allowed))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * A gathering action needs the tool that performs it: cage-fishing is a lobster pot, mining is a
	 * pickaxe. Returns the requirement this click fails, or null when the action needs no tool card (a
	 * thieving stall takes no input, so it is never gated) or the player owns one.
	 *
	 * <p>Only entries with <b>no item id</b> are considered — this is about clicking the world, and
	 * item-on-item actions are already covered by the item lock itself.
	 */
	@Nullable
	private ToolRequirement missingTool(@Nullable MenuEntry entry)
	{
		if (entry == null || entry.getItemId() > 0 || !locks.hasItemNames())
		{
			return null;
		}
		MenuAction type = entry.getType();
		if (type == MenuAction.RUNELITE || type == MenuAction.RUNELITE_OVERLAY
			|| type == MenuAction.RUNELITE_HIGH_PRIORITY || type == MenuAction.RUNELITE_LOW_PRIORITY
			|| type == MenuAction.CANCEL || type == MenuAction.WALK)
		{
			return null;
		}

		ToolRequirement requirement = ToolRequirement.forAction(
			Text.removeTags(entry.getOption()), Text.removeTags(entry.getTarget()));
		if (requirement == null)
		{
			return null;
		}
		return locks.ownsItemNamed(requirement.getLabel(), requirement.getCardMatches())
			? null
			: requirement;
	}

	private boolean enforcing()
	{
		return config.lockUncollectedItems() && locks.isLoaded();
	}

	/**
	 * True when this entry is an action on a locked item that isn't one of the always-allowed ones.
	 *
	 * <p>Only item actions are considered — an entry with no item id is a world object, an NPC or an
	 * interface button, none of which the lock has any business touching.
	 */
	private boolean blocks(@Nullable MenuEntry entry)
	{
		if (entry == null)
		{
			return false;
		}
		MenuAction type = entry.getType();
		// Our own entries, and every flavour of Examine, are never the lock's business.
		if (type == MenuAction.RUNELITE || type == MenuAction.RUNELITE_OVERLAY
			|| type == MenuAction.RUNELITE_HIGH_PRIORITY || type == MenuAction.RUNELITE_LOW_PRIORITY
			|| type == MenuAction.EXAMINE_ITEM || type == MenuAction.EXAMINE_ITEM_GROUND
			|| type == MenuAction.CANCEL)
		{
			return false;
		}
		// Taking an item off the floor is always allowed, whatever the option happens to be called.
		if (type == MenuAction.GROUND_ITEM_FIRST_OPTION || type == MenuAction.GROUND_ITEM_SECOND_OPTION
			|| type == MenuAction.GROUND_ITEM_THIRD_OPTION || type == MenuAction.GROUND_ITEM_FOURTH_OPTION
			|| type == MenuAction.GROUND_ITEM_FIFTH_OPTION)
		{
			return false;
		}

		int itemId = entry.getItemId();
		if (itemId <= 0 || !locks.isLocked(itemId))
		{
			return false;
		}
		return !isAlwaysAllowed(entry.getOption());
	}

	private static boolean isAlwaysAllowed(@Nullable String option)
	{
		if (option == null)
		{
			return true;
		}
		String plain = Text.removeTags(option).toLowerCase(Locale.ROOT).trim();
		for (String allowed : ALWAYS_ALLOWED)
		{
			// Prefix match: the game numbers its bank options ("Withdraw-10", "Deposit-All").
			if (plain.startsWith(allowed))
			{
				return true;
			}
		}
		return false;
	}

	private void announce(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(
				ColorUtil.wrapWithColorTag("[Card Exchange] ", Color.decode("#aa00ff")) + message)
			.build());
	}
}
