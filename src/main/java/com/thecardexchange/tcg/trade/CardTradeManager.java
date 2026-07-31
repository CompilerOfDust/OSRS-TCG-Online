package com.thecardexchange.tcg.trade;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;
import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.packs.CardPacksInterface;
import com.thecardexchange.tcg.packs.CatalogueCard;

/**
 * In-game card trading, and the right-click entries that start it.
 *
 * <p><b>Trade flow (WebSocket).</b> The plugin holds a {@link TradeSocket} to the Bun api while logged in
 * ({@link #setCurrentRsn} opens it on login, closes on logout). Clicking "Trade Cards" sends an
 * <em>offer</em>; the target sees a chat line "X wants to trade cards with you" and accepts it by
 * <b>left-clicking that line, right-clicking it → Accept, or right-clicking X → Trade Cards</b> (the
 * reciprocal offer). The {@link TradeWindow} — painted on the game canvas, Old School trade-interface
 * styled — opens <b>only once the trade is accepted on both ends</b>.
 */
@Slf4j
@Singleton
public class CardTradeManager
{
	private static final String TRADE_OPTION = "Trade Cards";
	private static final String EXCHANGE_OPTION = "Exchange Cards";
	private static final String EXCHANGE = "Exchange";
	private static final String MARKETPLACE_URL = "https://www.osrscardexchange.com/exchanges";
	/** Colour for card-trade chat lines — a red/purple, distinct from the game's own yellow trade text. */
	private static final String TRADE_COLOUR = "aa00ff";
	/** Plain substring of the incoming chat line, used to find its widget and make it clickable. */
	private static final String INCOMING_MARKER = "wants to trade cards with you";
	private static final long RECONNECT_THROTTLE_MS = 3000L;
	/** Drop an un-accepted incoming request after this (the server's offer TTL is 10s). */
	private static final long INCOMING_TTL_MS = 12_000L;

	private final Client client;
	private final ChatMessageManager chatMessageManager;
	private final AccountLinkManager linkManager;
	private final TheCardExchangeTcgConfig config;
	private final TradeWindow window;
	private final CardPacksInterface packs;
	private final TradeSocket socket;

	@Nullable
	private volatile String currentRsn;
	@Nullable
	private volatile String connectedRsn;
	private volatile long lastConnectMs;

	/** The incoming offer we can accept (from the clickable chat line), and when it arrived. */
	@Nullable
	private volatile String incomingOfferId;
	private volatile long incomingAtMs;

	/** The accepted trade currently on screen — what Decline in the trade window calls off. */
	@Nullable
	private volatile String activeOfferId;
	/** What this side has put up. Server-validated, but held here so a click can toggle it. */
	private final Set<Integer> myOffer = Collections.synchronizedSet(new LinkedHashSet<>());
	/** Where the deal stands, mirrored from the server's trade_accepted announcements. */
	private volatile boolean myAcceptedFlag;
	private volatile boolean theirAcceptedFlag;

	private final TradeSocket.Handler socketHandler = new TradeSocket.Handler()
	{
		@Override
		public void onEvent(TradeEvent event)
		{
			handleEvent(event);
		}

		@Override
		public void onClosed()
		{
			connectedRsn = null;
		}
	};

	@Inject
	CardTradeManager(
		Client client,
		ChatMessageManager chatMessageManager,
		AccountLinkManager linkManager,
		TheCardExchangeTcgConfig config,
		TradeWindow window,
		CardPacksInterface packs,
		TradeSocket socket)
	{
		this.client = client;
		this.chatMessageManager = chatMessageManager;
		this.linkManager = linkManager;
		this.config = config;
		this.window = window;
		this.packs = packs;
		this.socket = socket;
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	public void start()
	{
		// The socket opens with login (setCurrentRsn) and the accept UI is the clickable chat line /
		// reciprocal menu; all that's needed up front is the painted trade window and its Decline hook.
		window.setCloseListener(this::onWindowDismissed);
		// Clicking a card already on the table takes it back off.
		window.setSlotListener(this::onCardPicked);
		// Accept settles the trade once the other side accepts too.
		window.setAcceptListener(this::onAcceptClicked);
		window.start();
	}

	public void stop()
	{
		connectedRsn = null;
		incomingOfferId = null;
		activeOfferId = null;
		socket.disconnect();
		window.setCloseListener(null);
		window.setSlotListener(null);
		window.setAcceptListener(null);
		window.shutdown();
		closeTradeUi();
	}

	public void setCurrentRsn(@Nullable String rsn)
	{
		String normalised = rsn == null || rsn.trim().isEmpty() ? null : Text.sanitize(rsn.trim());
		this.currentRsn = normalised;

		if (normalised == null)
		{
			if (connectedRsn != null)
			{
				connectedRsn = null;
				socket.disconnect();
			}
			return;
		}
		if (normalised.equals(connectedRsn))
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastConnectMs < RECONNECT_THROTTLE_MS)
		{
			return;
		}
		String token = linkManager.getToken();
		if (token == null)
		{
			return;
		}
		lastConnectMs = now;
		connectedRsn = normalised;
		socket.connect(token, normalised, socketHandler);
	}

	/**
	 * Client-thread tick: while an incoming request is live, keep its chat line clickable (chat lines are
	 * recreated as the box scrolls, so re-wire), and expire a stale request.
	 */
	public void onGameTick()
	{
		String offerId = incomingOfferId;
		if (offerId == null)
		{
			return;
		}
		if (System.currentTimeMillis() - incomingAtMs > INCOMING_TTL_MS)
		{
			incomingOfferId = null;
			return;
		}
		wireIncomingChatLine();
	}

	// ── Right-click menu ──────────────────────────────────────────────────────

	public void onMenuOpened(MenuOpened event)
	{
		MenuEntry[] entries = event.getMenuEntries();
		if (config.tradeCardsMenuOption())
		{
			addTradeCards(entries);
		}
		if (config.exchangeCardsMenuOption())
		{
			addExchangeCards(entries);
		}
	}

	private void addTradeCards(MenuEntry[] entries)
	{
		Player local = client.getLocalPlayer();
		Map<Player, Integer> anchorIndex = new LinkedHashMap<>();
		Map<Player, Boolean> anchorIsTrade = new HashMap<>();

		for (int i = 0; i < entries.length; i++)
		{
			MenuEntry entry = entries[i];
			Player p = entry.getPlayer();
			if (p == null || p == local)
			{
				continue;
			}
			boolean isTrade = Text.removeTags(entry.getOption()).toLowerCase().startsWith("trade");
			if (!anchorIndex.containsKey(p))
			{
				anchorIndex.put(p, i);
				anchorIsTrade.put(p, isTrade);
			}
			else if (isTrade && !Boolean.TRUE.equals(anchorIsTrade.get(p)))
			{
				anchorIndex.put(p, i);
				anchorIsTrade.put(p, true);
			}
		}

		List<Map.Entry<Player, Integer>> anchors = new ArrayList<>(anchorIndex.entrySet());
		anchors.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		for (Map.Entry<Player, Integer> anchor : anchors)
		{
			final Player target = anchor.getKey();
			client.getMenu().createMenuEntry(anchor.getValue())
				.setOption(TRADE_OPTION)
				.setTarget(entries[anchor.getValue()].getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> requestTrade(target));
		}
	}

	private void addExchangeCards(MenuEntry[] entries)
	{
		int anchor = -1;
		boolean anchorIsExchange = false;
		String targetText = null;

		for (int i = 0; i < entries.length; i++)
		{
			MenuEntry entry = entries[i];
			NPC npc = entry.getNpc();
			if (npc == null || npc.getName() == null
				|| !npc.getName().toLowerCase().contains("grand exchange"))
			{
				continue;
			}
			boolean isExchange = EXCHANGE.equalsIgnoreCase(Text.removeTags(entry.getOption()));
			if (anchor < 0)
			{
				anchor = i;
				targetText = entry.getTarget();
				anchorIsExchange = isExchange;
			}
			else if (isExchange && !anchorIsExchange)
			{
				anchor = i;
				targetText = entry.getTarget();
				anchorIsExchange = true;
			}
		}
		if (anchor < 0)
		{
			return;
		}

		client.getMenu().createMenuEntry(anchor)
			.setOption(EXCHANGE_OPTION)
			.setTarget(targetText)
			.setType(MenuAction.RUNELITE)
			.onClick(e -> openMarketplace());
	}

	// ── Offer / accept flow ───────────────────────────────────────────────────

	/** "Trade Cards" click (client thread): offer the target over the socket. No dialogue yet. */
	private void requestTrade(Player target)
	{
		if (target == null || target.getName() == null)
		{
			return;
		}
		String targetName = Text.sanitize(target.getName());
		String me = currentRsn;
		if (targetName.isEmpty() || me == null || rsnKey(targetName).equals(rsnKey(me)))
		{
			return;
		}

		if (!linkManager.isLinked())
		{
			announce("ff9040", "Link your account in the OSRS TCG (TheCardExchange) side panel to trade cards.");
			return;
		}
		if (!socket.isConnected())
		{
			announce("ff9040", "Still connecting to the Card Exchange — try again in a moment.");
			return;
		}

		socket.sendOffer(targetName);
	}

	private void handleEvent(TradeEvent event)
	{
		switch (event.getType())
		{
			case OFFERED:
				// Sent/incoming requests go out as the game's own trade chat types, so they land in the
				// Trade tab (and its notification dot) exactly like a normal trade request.
				announce(ChatMessageType.TRADE_SENT, TRADE_COLOUR,
					"Trade request sent to " + safe(event.getOther(), "them")
						+ ". They have 10 seconds to accept.");
				break;
			case INCOMING:
				incomingOfferId = event.getOfferId();
				incomingAtMs = System.currentTimeMillis();
				announce(ChatMessageType.TRADEREQ, TRADE_COLOUR, safe(event.getOther(), "A player")
					+ " " + INCOMING_MARKER + " — click this line, or right-click them and Trade Cards, to accept.");
				break;
			case ACCEPTED:
				incomingOfferId = null;
				activeOfferId = event.getOfferId();
				myOffer.clear();
				myAcceptedFlag = false;
				theirAcceptedFlag = false;
				window.showAccepted(safe(event.getOther(), "your trade partner"));
				// Bring the card list up beside the trade: it's what you pick from, duplicates only.
				packs.open();
				packs.setTradePicker(this::onCardPicked);
				packs.setOffered(Collections.emptySet());
				break;
			case CARDS:
				// A changed offer voids any standing acceptance, exactly as the server does.
				myAcceptedFlag = false;
				theirAcceptedFlag = false;
				window.setAccepted(false, false);
				onOfferChanged(event);
				break;
			case TRADE_ACCEPTED:
			{
				String accepter = safe(event.getOther(), "");
				String rsn = currentRsn;
				if (rsn != null && rsnKey(accepter).equals(rsnKey(rsn)))
				{
					myAcceptedFlag = true;
				}
				else
				{
					theirAcceptedFlag = true;
					announce(TRADE_COLOUR, accepter + " has accepted the trade.");
				}
				window.setAccepted(myAcceptedFlag, theirAcceptedFlag);
				break;
			}
			case COMPLETED:
				incomingOfferId = null;
				activeOfferId = null;
				announce("00c853", "Trade completed! You received "
					+ event.getCardIds().size() + (event.getCardIds().size() == 1 ? " card" : " cards")
					+ " from " + safe(event.getOther(), "your trade partner") + ".");
				closeTradeUi();
				// The collection just changed on both ends — new counts, new unlocks.
				packs.refreshData();
				break;
			case DECLINED:
			case CANCELLED:
				incomingOfferId = null;
				activeOfferId = null;
				closeTradeUi();
				announce("ff9040", "The card trade was called off.");
				break;
			case ERROR:
				if (activeOfferId != null && "cards_changed".equals(event.getReason()))
				{
					// The settle failed because someone's duplicates changed. The trade stays open;
					// only the acceptances die.
					myAcceptedFlag = false;
					theirAcceptedFlag = false;
					window.setAccepted(false, false);
					announce("ff9040", "The cards on the table changed — both sides need to accept again.");
					break;
				}
				if (activeOfferId != null && "nothing_offered".equals(event.getReason()))
				{
					announce("ff9040", "Put a card up before accepting.");
					break;
				}
				incomingOfferId = null;
				activeOfferId = null;
				closeTradeUi();
				announce("ff9040", "That trade request "
					+ ("expired".equals(event.getReason()) ? "expired." : "could not be completed."));
				break;
			default:
				break;
		}
	}

	/** Accept the live incoming offer — invoked from the clickable chat line. */
	private void acceptIncoming()
	{
		String offerId = incomingOfferId;
		if (offerId != null)
		{
			socket.sendAccept(offerId);
			incomingOfferId = null;
		}
	}

	/**
	 * The player dismissed the trade window (Decline or the X) — called on the AWT thread from
	 * {@link TradeWindow}. Cancel the trade server-side so the other client's window closes too.
	 */
	private void onWindowDismissed()
	{
		String offerId = activeOfferId;
		activeOfferId = null;
		if (offerId != null)
		{
			socket.sendCancel(offerId);
		}
		// Clears trade mode as well as the windows — otherwise the card view stays stuck on duplicates.
		closeTradeUi();
		announce("ff9040", "You closed the card trade.");
	}

	/** Put away both halves of the trade UI — the window and the card list it opened with. */
	private void closeTradeUi()
	{
		myOffer.clear();
		myAcceptedFlag = false;
		theirAcceptedFlag = false;
		myOfferCards = Collections.emptyList();
		theirOffer = Collections.emptyList();
		window.setOffers(Collections.emptyList(), Collections.emptyList());
		window.setAccepted(false, false);
		window.close();
		packs.setTradePicker(null);
		packs.close();
	}

	/** Accept clicked in the trade window — the server settles once the other side accepts too. */
	private void onAcceptClicked()
	{
		String offerId = activeOfferId;
		if (offerId != null)
		{
			socket.sendAcceptTrade(offerId);
		}
	}

	/**
	 * A card was clicked in the card list while the trade is up: put it on the table, or take it back
	 * down. The server decides what's allowed — it keeps only duplicates — and pushes the agreed list to
	 * both clients, so this is a request rather than the truth.
	 */
	private void onCardPicked(CatalogueCard card)
	{
		String offerId = activeOfferId;
		if (offerId == null)
		{
			log.debug("Card {} picked with no trade open", card.getId());
			return;
		}
		if (!myOffer.remove(card.getId()))
		{
			myOffer.add(card.getId());
		}
		List<Integer> ids;
		synchronized (myOffer)
		{
			ids = new ArrayList<>(myOffer);
		}
		log.debug("Offering cards {} on trade {}", ids, offerId);
		socket.sendOfferCards(offerId, ids);
	}

	/** Either side's offer changed — repaint both halves of the window from the server's list. */
	private void onOfferChanged(TradeEvent event)
	{
		String me = currentRsn;
		boolean mine = me != null && rsnKey(safe(event.getOther(), "")).equals(rsnKey(me));
		log.debug("Offer changed: from={} me={} mine={} ids={}", event.getOther(), me, mine, event.getCardIds());

		List<CatalogueCard> cards = new ArrayList<>();
		for (Integer id : event.getCardIds())
		{
			CatalogueCard card = packs.cardById(id);
			if (card != null)
			{
				cards.add(card);
			}
		}

		if (mine)
		{
			myOffer.clear();
			myOffer.addAll(event.getCardIds());
			packs.setOffered(new LinkedHashSet<>(event.getCardIds()));
			window.setOffers(cards, theirOffer);
			this.myOfferCards = cards;
		}
		else
		{
			theirOffer = cards;
			window.setOffers(myOfferCards, cards);
		}
	}

	/**
	 * Makes the incoming trade-request chat line clickable: an "Accept" op so left-click fires it and
	 * right-click offers it. Chat lines are regenerated as the box scrolls, so this runs each tick while a
	 * request is live.
	 */
	private void wireIncomingChatLine()
	{
		Widget parent = client.getWidget(WidgetInfo.CHATBOX_MESSAGE_LINES);
		if (parent == null)
		{
			return;
		}
		Widget line = findChatLine(parent);
		if (line == null)
		{
			return;
		}
		line.setAction(0, "Accept");
		line.setHasListener(true);
		line.setOnOpListener((JavaScriptCallback) ev -> acceptIncoming());
	}

	@Nullable
	private static Widget findChatLine(Widget parent)
	{
		Widget found = null;
		for (Widget[] group : new Widget[][]{parent.getChildren(), parent.getDynamicChildren(), parent.getStaticChildren()})
		{
			if (group == null)
			{
				continue;
			}
			for (Widget w : group)
			{
				if (w == null)
				{
					continue;
				}
				String text = w.getText();
				if (text != null && text.contains(INCOMING_MARKER))
				{
					found = w; // keep the most recent match
				}
			}
		}
		return found;
	}

	private void openMarketplace()
	{
		announce("ffff00", "Opening the Card Exchange marketplace in your browser…");
		LinkBrowser.browse(MARKETPLACE_URL);
	}

	private void announce(String colour, String message)
	{
		announce(ChatMessageType.GAMEMESSAGE, colour, message);
	}

	private void announce(ChatMessageType type, String colour, String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(type)
			.runeLiteFormattedMessage(
				ColorUtil.wrapWithColorTag("[Card Exchange] ", Color.decode("#" + colour)) + message)
			.build());
	}

	/** The two sides' cards as last painted, so one side changing doesn't wipe the other. */
	private volatile List<CatalogueCard> myOfferCards = Collections.emptyList();
	private volatile List<CatalogueCard> theirOffer = Collections.emptyList();

	private static String rsnKey(String rsn)
	{
		return rsn.trim().replaceAll("\\s+", " ").toLowerCase();
	}

	private static String safe(@Nullable String value, @Nullable String fallback)
	{
		if (value != null && !value.isEmpty())
		{
			return value;
		}
		return fallback != null && !fallback.isEmpty() ? fallback : "a player";
	}
}
