package com.thecardexchange.tcg.trade;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Text;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;

/**
 * The plugin's end of the trade WebSocket. {@link #connect} opens a socket to the Bun api
 * (`/api/v1/plugin/trade/ws`), authenticated by the plugin token in the handshake header, carrying our
 * character as {@code ?rsn=}. Incoming broker messages are parsed to {@link TradeEvent}s and handed to a
 * {@link Handler}; {@link #sendOffer}/{@link #sendAccept}/{@link #sendDecline}/{@link #sendCancel} push
 * actions the other way. One socket at a time; opening a new one closes the old.
 */
@Slf4j
@Singleton
public class TradeSocket
{
	/** Callbacks, invoked on OkHttp's WebSocket thread. */
	public interface Handler
	{
		void onEvent(TradeEvent event);

		/** The socket closed or failed to open (so the owner can reconnect). */
		void onClosed();
	}

	private final OkHttpClient wsClient;
	private final Gson gson;
	private final TheCardExchangeTcgConfig config;

	@Nullable
	private volatile WebSocket socket;

	@Inject
	TradeSocket(OkHttpClient okHttpClient, Gson gson, TheCardExchangeTcgConfig config)
	{
		// A WebSocket is long-lived: no read timeout, and a ping keeps it (and any proxy) alive.
		this.wsClient = okHttpClient.newBuilder()
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.pingInterval(20, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.config = config;
	}

	public void connect(String token, String rsn, Handler handler)
	{
		disconnect();

		HttpUrl base = baseUrl();
		if (base == null)
		{
			log.debug("Trade socket: invalid API base URL");
			handler.onClosed();
			return;
		}
		HttpUrl url = base.newBuilder()
			.addPathSegments("api/v1/plugin/trade/ws")
			.addQueryParameter("rsn", rsn)
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token.trim())
			.build();

		this.socket = wsClient.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onMessage(WebSocket webSocket, String text)
			{
				TradeEvent event = parse(text);
				if (event != null)
				{
					handler.onEvent(event);
				}
			}

			@Override
			public void onClosed(WebSocket webSocket, int code, String reason)
			{
				handler.onClosed();
			}

			@Override
			public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response)
			{
				log.debug("Trade socket failure", t);
				handler.onClosed();
			}
		});
	}

	public void disconnect()
	{
		WebSocket ws = socket;
		socket = null;
		if (ws != null)
		{
			ws.close(1000, "bye");
		}
	}

	public void sendOffer(String targetRsn)
	{
		JsonObject body = new JsonObject();
		body.addProperty("type", "offer");
		body.addProperty("targetRsn", targetRsn);
		send(body);
	}

	public void sendAccept(String offerId)
	{
		sendAction("accept", offerId);
	}

	public void sendDecline(String offerId)
	{
		sendAction("decline", offerId);
	}

	public void sendCancel(String offerId)
	{
		sendAction("cancel", offerId);
	}

	/**
	 * Puts this side's cards on the table. The server keeps only the ones it agrees are duplicates and
	 * pushes the accepted list back to both clients, so the window shows what the server allowed rather
	 * than what was clicked.
	 */
	public void sendOfferCards(String offerId, Collection<Integer> cardIds)
	{
		JsonObject body = new JsonObject();
		body.addProperty("type", "offer_cards");
		body.addProperty("offerId", offerId);
		JsonArray ids = new JsonArray();
		for (Integer id : cardIds)
		{
			ids.add(id);
		}
		body.add("cardIds", ids);
		send(body);
	}

	/** Accepts the cards on the table. The trade settles once both sides have sent this. */
	public void sendAcceptTrade(String offerId)
	{
		sendAction("accept_trade", offerId);
	}

	/** True once a socket has been opened (and not disconnected). Not a liveness guarantee. */
	public boolean isConnected()
	{
		return socket != null;
	}

	private void sendAction(String type, String offerId)
	{
		JsonObject body = new JsonObject();
		body.addProperty("type", type);
		body.addProperty("offerId", offerId);
		send(body);
	}

	private void send(JsonObject body)
	{
		WebSocket ws = socket;
		String json = gson.toJson(body);
		if (ws == null)
		{
			log.debug("Trade socket -> dropped (no socket): {}", json);
			return;
		}
		log.debug("Trade socket -> {}", json);
		ws.send(json);
	}

	@Nullable
	private TradeEvent parse(String text)
	{
		try
		{
			log.debug("Trade socket <- {}", text);
			JsonObject json = gson.fromJson(text, JsonObject.class);
			if (json == null)
			{
				return null;
			}
			String type = str(json, "type");
			String offerId = str(json, "offerId");
			String reason = str(json, "reason");
			switch (type)
			{
				case "offered":
					return new TradeEvent(TradeEvent.Type.OFFERED, offerId, str(json, "to"), null);
				case "incoming":
					return new TradeEvent(TradeEvent.Type.INCOMING, offerId, str(json, "from"), null);
				case "accepted":
					return new TradeEvent(TradeEvent.Type.ACCEPTED, offerId, str(json, "with"), null);
				case "declined":
					return new TradeEvent(TradeEvent.Type.DECLINED, offerId, str(json, "with"), null);
				case "cancelled":
					return new TradeEvent(TradeEvent.Type.CANCELLED, offerId, str(json, "with"), null);
				case "trade_cards":
					return new TradeEvent(TradeEvent.Type.CARDS, offerId, str(json, "from"), null,
						ints(json.getAsJsonArray("cardIds")));
				case "trade_accepted":
					return new TradeEvent(TradeEvent.Type.TRADE_ACCEPTED, offerId, str(json, "from"), null);
				case "trade_completed":
					return new TradeEvent(TradeEvent.Type.COMPLETED, offerId, str(json, "with"), null,
						ints(json.getAsJsonArray("got")));
				case "error":
					// The server explains its refusals; carry the words through so the
					// player is told what to do instead of just that it failed.
					return new TradeEvent(TradeEvent.Type.ERROR, offerId, null, reason,
						str(json, "message"), java.util.Collections.emptyList());
				default:
					return new TradeEvent(TradeEvent.Type.UNKNOWN, offerId, null, null);
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("Bad trade socket message: {}", text, ex);
			return null;
		}
	}

	/** A JSON array of card ids; anything unreadable is skipped rather than failing the message. */
	private static List<Integer> ints(@Nullable JsonArray array)
	{
		if (array == null)
		{
			return Collections.emptyList();
		}
		List<Integer> ids = new ArrayList<>(array.size());
		for (JsonElement element : array)
		{
			try
			{
				ids.add(element.getAsInt());
			}
			catch (RuntimeException ignored)
			{
				// One bad id is not a bad offer.
			}
		}
		return ids;
	}

	@Nullable
	private static String str(JsonObject json, String key)
	{
		return json.has(key) && !json.get(key).isJsonNull() ? Text.removeTags(json.get(key).getAsString()) : null;
	}

	@Nullable
	private HttpUrl baseUrl()
	{
		String configured = config.apiBaseUrl();
		String base = (configured == null || configured.trim().isEmpty())
			? TheCardExchangeTcgConfig.defaultApiBaseUrl()
			: configured.trim();
		return HttpUrl.parse(base.replaceAll("/+$", ""));
	}
}
