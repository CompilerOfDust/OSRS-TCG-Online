package com.thecardexchange.tcg.account;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Text;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;

import com.thecardexchange.tcg.packs.CardCatalogue;
import com.thecardexchange.tcg.packs.CatalogueCard;
import com.thecardexchange.tcg.packs.Holdings;
import com.thecardexchange.tcg.packs.PackResult;

/**
 * Thin HTTP wrapper over the exchange's device-pairing endpoints. It holds no
 * state — {@link AccountLinkManager} owns the flow; this class just turns one
 * call into one typed result and throws {@link IOException} on anything it can't
 * make sense of, so the manager has a single failure path to handle.
 *
 * <p>The base URL is read from config on every call so pointing the plugin at a
 * different server (local dev vs the hosted API) takes effect without a restart.
 */
@Slf4j
@Singleton
public class ExchangeApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final TheCardExchangeTcgConfig config;

	@Inject
	ExchangeApiClient(OkHttpClient okHttpClient, Gson gson, TheCardExchangeTcgConfig config)
	{
		// Short timeouts: linking is interactive, so a hung request should surface as an error the player
		// can retry rather than block the poll loop.
		this.httpClient = okHttpClient.newBuilder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(20, TimeUnit.SECONDS)
			.writeTimeout(20, TimeUnit.SECONDS)
			.build();
		this.gson = gson;
		this.config = config;
	}

	/** Opens a handshake. {@code label} is shown to the account holder on the confirm screen. */
	public LinkHandshake startLink(@Nullable String label) throws IOException
	{
		JsonObject body = new JsonObject();
		if (label != null && !label.trim().isEmpty())
		{
			body.addProperty("label", label.trim());
		}

		try (Response response = execute(post("/api/v1/plugin/link/start", body)))
		{
			JsonObject json = requireJsonBody(response);
			if (!response.isSuccessful())
			{
				throw new IOException("Could not start linking (HTTP " + response.code() + ")");
			}
			return new LinkHandshake(
				asString(json, "code"),
				asString(json, "deviceSecret"),
				asString(json, "verificationUri"),
				asString(json, "verificationUriComplete"),
				json.has("interval") && !json.get("interval").isJsonNull() ? json.get("interval").getAsInt() : 5);
		}
	}

	/** One poll. Never throws on the normal PENDING/LINKED/EXPIRED outcomes — only on transport errors. */
	public PollResult poll(String deviceSecret) throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("deviceSecret", deviceSecret);

		try (Response response = execute(post("/api/v1/plugin/link/poll", body)))
		{
			JsonObject json = requireJsonBody(response);
			if (!response.isSuccessful())
			{
				// A 4xx here (bad body, rate limit) is not a normal poll outcome — treat as transient.
				throw new IOException("Poll failed (HTTP " + response.code() + ")");
			}
			String status = asString(json, "status");
			if ("linked".equals(status))
			{
				return PollResult.linked(asString(json, "pluginToken"), account(json.getAsJsonObject("account")));
			}
			if ("pending".equals(status))
			{
				return PollResult.pending();
			}
			return PollResult.expired();
		}
	}

	/**
	 * Verifies a stored token and returns its account, or null if the server no longer accepts it (401 —
	 * revoked or unknown). Throws only on transport errors, so the caller can tell "definitely not linked"
	 * from "couldn't reach the server, keep the token".
	 */
	@Nullable
	public LinkedAccount session(String token) throws IOException
	{
		Request request = authorised(get("/api/v1/plugin/session"), token).build();
		try (Response response = execute(request))
		{
			if (response.code() == 401)
			{
				return null;
			}
			JsonObject json = requireJsonBody(response);
			if (!response.isSuccessful() || !json.has("account"))
			{
				throw new IOException("Session check failed (HTTP " + response.code() + ")");
			}
			return account(json.getAsJsonObject("account"));
		}
	}

	/**
	 * The whole card catalogue, ordered by card id. Static between seeds and ~750 KB on the wire (gzipped
	 * by the server), so the plugin asks once a session and keeps it.
	 */
	public CardCatalogue cards(String token) throws IOException
	{
		Request request = authorised(get("/api/v1/plugin/cards"), token).build();
		try (Response response = execute(request))
		{
			JsonObject json = requireJsonBody(response);
			if (!response.isSuccessful())
			{
				throw new IOException("Could not load the card catalogue (HTTP " + response.code() + ")");
			}
			JsonArray cards = json.getAsJsonArray("cards");
			List<CatalogueCard> out = new ArrayList<>(cards == null ? 0 : cards.size());
			if (cards != null)
			{
				for (JsonElement element : cards)
				{
					if (element.isJsonObject())
					{
						out.add(catalogueCard(element.getAsJsonObject()));
					}
				}
			}
			return new CardCatalogue(out, intSet(json.getAsJsonArray("collectable")),
				intSet(json.getAsJsonArray("collectableNpcs")));
		}
	}

	/** The character's wallet and owned cards. */
	public Holdings collection(String token) throws IOException
	{
		Request request = authorised(get("/api/v1/plugin/collection"), token).build();
		try (Response response = execute(request))
		{
			JsonObject json = requireJsonBody(response);
			if (!response.isSuccessful())
			{
				throw new IOException("Could not load your collection (HTTP " + response.code() + ")");
			}
			Map<Integer, Integer> owned = new HashMap<>();
			JsonArray rows = json.getAsJsonArray("owned");
			if (rows != null)
			{
				for (JsonElement element : rows)
				{
					if (!element.isJsonObject())
					{
						continue;
					}
					JsonObject row = element.getAsJsonObject();
					owned.put(asInt(row, "i", 0), asInt(row, "q", 0));
				}
			}
			return new Holdings(asInt(json, "credits", 0), asInt(json, "openedPacks", 0),
				asInt(json, "packPrice", 0), owned, intSet(json.getAsJsonArray("unlocked")),
				intSet(json.getAsJsonArray("unlockedNpcs")));
		}
	}

	/**
	 * Opens one pack. The server charges the credits and decides the contents; a {@code 402} means the
	 * wallet was short, which surfaces as {@link NotEnoughCredits} rather than a generic failure.
	 */
	public PackResult openPack(String token) throws IOException
	{
		Request request = authorised(
			new Request.Builder().url(url("/api/v1/plugin/packs/open"))
				.header("Accept", "application/json")
				.post(RequestBody.create(JSON, "{}")),
			token).build();

		try (Response response = execute(request))
		{
			JsonObject json = requireJsonBody(response);
			if (response.code() == 402)
			{
				throw new NotEnoughCredits(asInt(json, "credits", 0), asInt(json, "packPrice", 0));
			}
			if (!response.isSuccessful())
			{
				throw new IOException("Could not open a pack (HTTP " + response.code() + ")");
			}
			List<PackResult.PulledCard> pulled = new ArrayList<>();
			JsonArray cards = json.getAsJsonArray("cards");
			if (cards != null)
			{
				for (JsonElement element : cards)
				{
					if (!element.isJsonObject())
					{
						continue;
					}
					JsonObject card = element.getAsJsonObject();
					pulled.add(new PackResult.PulledCard(
						catalogueCard(card),
						asInt(card, "q", 1),
						card.has("new") && card.get("new").getAsBoolean()));
				}
			}
			return new PackResult(pulled, asInt(json, "credits", 0), asInt(json, "openedPacks", 0));
		}
	}

	/**
	 * The wire shape is compact — {@code i,n,k,g,t,a,d} — because the catalogue is ten thousand rows.
	 * {@code t} is the gem tier as a plain integer 1–7 (see the api's {@code lib/tcg/tier.ts});
	 * {@code d} is the examine-line description, absent when the card has none.
	 */
	private static CatalogueCard catalogueCard(JsonObject card)
	{
		String art = card.has("a") && !card.get("a").isJsonNull() ? card.get("a").getAsString() : null;
		String slug = card.has("s") && !card.get("s").isJsonNull() ? card.get("s").getAsString() : null;
		String description = card.has("d") && !card.get("d").isJsonNull() ? card.get("d").getAsString() : null;
		return new CatalogueCard(
			asInt(card, "i", 0),
			asString(card, "n"),
			"npc".equals(asString(card, "k")),
			asInt(card, "g", -1),
			art,
			slug,
			description,
			asInt(card, "t", 1),
			asInt(card, "sp", 0) == 1,
			intList(card.getAsJsonArray("u")),
			intList(card.getAsJsonArray("cf")),
			intList(card.getAsJsonArray("ci")));
	}

	/** Thrown when a pack costs more than the character has. Carries the numbers so the UI can say so. */
	public static final class NotEnoughCredits extends IOException
	{
		private final int credits;
		private final int packPrice;

		NotEnoughCredits(int credits, int packPrice)
		{
			super("Not enough credits");
			this.credits = credits;
			this.packPrice = packPrice;
		}

		public int getCredits()
		{
			return credits;
		}

		public int getPackPrice()
		{
			return packPrice;
		}
	}

	/** Revokes this device's token server-side. Best-effort — the caller clears local state regardless. */
	public void logout(String token) throws IOException
	{
		Request request = authorised(
			new Request.Builder().url(url("/api/v1/plugin/logout")).post(RequestBody.create(JSON, "{}")),
			token).build();
		try (Response response = execute(request))
		{
			// The body is uninteresting; draining it lets the connection be reused.
			if (response.body() != null)
			{
				response.body().string();
			}
		}
	}

	private Response execute(Request request) throws IOException
	{
		return httpClient.newCall(request).execute();
	}

	private Request post(String path, JsonObject body)
	{
		return new Request.Builder()
			.url(url(path))
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.header("Accept", "application/json")
			.build();
	}

	private Request.Builder get(String path)
	{
		return new Request.Builder().url(url(path)).header("Accept", "application/json").get();
	}

	private static Request.Builder authorised(Request.Builder builder, String token)
	{
		return builder.header("Authorization", "Bearer " + token.trim());
	}

	private HttpUrl url(String path)
	{
		// A blank config field means "fall back to the env var / built-in default", so the
		// THECARDEXCHANGE_API_URL a deployment sets still applies when the player hasn't overridden it.
		String configured = config.apiBaseUrl();
		String base = (configured == null || configured.trim().isEmpty())
			? TheCardExchangeTcgConfig.defaultApiBaseUrl()
			: configured.trim();
		base = base.replaceAll("/+$", "");

		HttpUrl parsed = HttpUrl.parse(base + path);
		if (parsed == null)
		{
			throw new IllegalStateException("Invalid API base URL: " + base);
		}
		return parsed;
	}

	/** Reads the JSON body, or throws if the response isn't JSON — guards against a wrong base URL that
	 * returns an HTML error page with a 200. */
	private JsonObject requireJsonBody(Response response) throws IOException
	{
		String contentType = response.header("Content-Type");
		if (contentType == null || !contentType.toLowerCase().contains("json") || response.body() == null)
		{
			throw new IOException("Expected JSON from the exchange but got '" + contentType + "'");
		}
		try
		{
			JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
			if (json == null)
			{
				throw new IOException("Empty response from the exchange");
			}
			return json;
		}
		catch (RuntimeException ex)
		{
			throw new IOException("Malformed response from the exchange", ex);
		}
	}

	private static LinkedAccount account(@Nullable JsonObject account)
	{
		if (account == null)
		{
			return new LinkedAccount("", "");
		}
		return new LinkedAccount(asString(account, "email"), asString(account, "osrsName"));
	}

	/** A flat JSON array of ints, in wire order — combine recipe card ids. */
	private static List<Integer> intList(@Nullable JsonArray array)
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
			catch (RuntimeException ex)
			{
				// One bad id is not a bad card.
			}
		}
		return ids;
	}

	/** A flat JSON array of game ids — the catalogue's collectable set, or a character's unlocks. */
	private static Set<Integer> intSet(@Nullable JsonArray array)
	{
		if (array == null)
		{
			return Collections.emptySet();
		}
		Set<Integer> ids = new HashSet<>(array.size());
		for (JsonElement element : array)
		{
			try
			{
				ids.add(element.getAsInt());
			}
			catch (RuntimeException ex)
			{
				// A malformed entry is one lost id, not a failed load.
			}
		}
		return ids;
	}

	private static int asInt(JsonObject json, String key, int fallback)
	{
		if (json == null || !json.has(key) || json.get(key).isJsonNull())
		{
			return fallback;
		}
		try
		{
			return json.get(key).getAsInt();
		}
		catch (RuntimeException ex)
		{
			return fallback;
		}
	}

	private static String asString(JsonObject json, String key)
	{
		if (json == null || !json.has(key) || json.get(key).isJsonNull())
		{
			return "";
		}
		return Text.removeTags(json.get(key).getAsString());
	}
}
