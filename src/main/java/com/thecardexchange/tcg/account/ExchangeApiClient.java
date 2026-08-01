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

import com.thecardexchange.tcg.mode.GameMode;
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

	/**
	 * Sent with every character report so a reviewer can tell an old build's quirk from a cheat, and
	 * so a bug that skews the numbers can be traced to the release that shipped it.
	 */
	private static final String pluginVersion = readPluginVersion();

	/** The character header every request that touches a collection carries. */
	private static final String CHARACTER_HEADER = "X-TCG-Character";

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

	/**
	 * Opens a handshake. {@code label} is shown to the account holder on the confirm screen, and the
	 * snapshot — when we are logged in — names the character being linked, so the confirm screen can
	 * say <em>which</em> character is about to be bound and the bind lands the moment they confirm.
	 */
	public LinkHandshake startLink(@Nullable String label, @Nullable CharacterSnapshot character)
		throws IOException
	{
		JsonObject body = new JsonObject();
		if (label != null && !label.trim().isEmpty())
		{
			body.addProperty("label", label.trim());
		}
		if (character != null && !character.isExcludedWorld())
		{
			body.add("character", character.toJson(pluginVersion));
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

	/** The character's wallet and owned cards. {@code rsn} says which character to read. */
	public Holdings collection(String token, @Nullable String rsn) throws IOException
	{
		Request request = authorised(get("/api/v1/plugin/collection"), token, rsn).build();
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
	public PackResult openPack(String token, @Nullable String rsn) throws IOException
	{
		Request request = authorised(
			new Request.Builder().url(url("/api/v1/plugin/packs/open"))
				.header("Accept", "application/json")
				.post(RequestBody.create(JSON, "{}")),
			token, rsn).build();

		try (Response response = execute(request))
		{
			JsonObject json = requireJsonBody(response);
			if (response.code() == 402)
			{
				throw new NotEnoughCredits(asInt(json, "credits", 0), asInt(json, "packPrice", 0));
			}
			if (response.code() == 423)
			{
				// Held for review: the request was fine, the character isn't allowed to play on.
				String message = asString(json, "message");
				throw new CharacterUnderReview(message.isEmpty()
					? "This character is under review." : message);
			}
			if (response.code() == 409)
			{
				throw new CharacterNotBound(
					"This character isn't linked yet. Log in and let the plugin link it first.");
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

	/**
	 * Thrown when the server is holding this character for review. Everything that would let the run
	 * continue — packs, trading, choosing a mode — refuses with this, carrying the sentence to show
	 * the player.
	 */
	public static final class CharacterUnderReview extends IOException
	{
		CharacterUnderReview(String message)
		{
			super(message);
		}
	}

	/**
	 * Thrown when the account holder unlinked this character from their profile.
	 *
	 * <p>Distinct from {@link CharacterNotBound}: that is "bind and carry on", this is a deliberate
	 * decision only they can undo, so retrying is pointless and the plugin says so instead.
	 */
	public static final class CharacterReleased extends IOException
	{
		CharacterReleased(String message)
		{
			super(message);
		}
	}

	/** Thrown when this account has not bound the character the request is for. */
	public static final class CharacterNotBound extends IOException
	{
		CharacterNotBound(String message)
		{
			super(message);
		}
	}

	/** Thrown when the character belongs to a different exchange account. */
	/**
	 * The server rejected our plugin token — revoked, or its account no longer exists.
	 *
	 * <p>Terminal, unlike every other failure here: retrying cannot fix it, so the caller unlinks and
	 * asks the player to link again rather than looping.
	 */
	public static final class Unauthorised extends IOException
	{
		Unauthorised(String message)
		{
			super(message);
		}
	}

	public static final class CharacterClaimed extends IOException
	{
		CharacterClaimed(String message)
		{
			super(message);
		}
	}

	/** Thrown when the server refuses a game mode, carrying its reason and its sentence. */
	public static final class ModeRefused extends IOException
	{
		private final String reason;

		ModeRefused(String reason, String message)
		{
			super(message);
			this.reason = reason;
		}

		public String getReason()
		{
			return reason;
		}
	}

	// ── Character ────────────────────────────────────────────────────────────

	/**
	 * Tells the server which character this install is playing. Sent on every login: the server keys
	 * the collection, packs and item locks by the bound character, so nothing else works until it lands.
	 */
	public CharacterState bindCharacter(String token, CharacterSnapshot snapshot) throws IOException
	{
		return characterCall("/api/v1/plugin/character/bind", token, snapshot, null);
	}

	/**
	 * The Card Exchange characters logged in right now, for the network badge.
	 *
	 * <p>The whole list comes down and the matching happens on this machine. It would be cheaper to
	 * ask the server "is this player one of yours?" for each name we see — and that is exactly what
	 * must not happen: those names belong to third parties who agreed to nothing, and shipping them
	 * off the client is the kind of thing RuneLite's plugin review refuses, rightly.
	 *
	 * @return display names, lowercased for matching
	 */
	public Map<String, GameMode> onlineNetworkPlayers(String token) throws IOException
	{
		Request request = authorised(get("/api/v1/plugin/network/online"), token).build();
		try (Response response = execute(request))
		{
			JsonObject json = response.body() == null
				? new JsonObject()
				: gson.fromJson(response.body().charStream(), JsonObject.class);
			if (json == null)
			{
				return Collections.emptyMap();
			}

			Map<String, GameMode> players = new HashMap<>();
			if (json.has("players") && json.get("players").isJsonArray())
			{
				for (JsonElement element : json.getAsJsonArray("players"))
				{
					if (!element.isJsonObject())
					{
						continue;
					}
					JsonObject player = element.getAsJsonObject();
					String rsn = asString(player, "rsn");
					if (rsn.isEmpty())
					{
						continue;
					}
					players.put(normaliseRsn(rsn), GameMode.fromConfigValue(asString(player, "gameMode")));
				}
				return players;
			}

			// A server that predates the mode being carried. Everyone still gets a
			// badge, just the Normal one — better than no badge at all.
			if (json.has("rsns") && json.get("rsns").isJsonArray())
			{
				for (JsonElement element : json.getAsJsonArray("rsns"))
				{
					String rsn = element.getAsString();
					if (rsn != null && !rsn.isEmpty())
					{
						players.put(normaliseRsn(rsn), GameMode.NOT_SELECTED);
					}
				}
			}
			return players;
		}
	}

	/**
	 * Publishes, or stops publishing, this account's online status to the badge.
	 *
	 * <p>Server-side, deliberately: a client can hide itself but can never un-hide somebody who
	 * chose to opt out.
	 */
	public void setNetworkVisibility(String token, boolean visible) throws IOException
	{
		JsonObject body = new JsonObject();
		body.addProperty("visible", visible);
		Request request = authorised(
			new Request.Builder()
				.url(url("/api/v1/plugin/network/visibility"))
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.header("Accept", "application/json"),
			token).build();
		try (Response response = execute(request))
		{
			// The status is all we need; execute() has already thrown on a refusal.
			response.code();
		}
	}

	/** The key names are matched on: sanitised of the game's markup, then lowercased. */
	public static String normaliseRsn(String rsn)
	{
		return Text.sanitize(rsn).trim().toLowerCase();
	}

	/** The 5-minute "still here, still this shape" report. */
	public CharacterState heartbeat(String token, CharacterSnapshot snapshot) throws IOException
	{
		return characterCall("/api/v1/plugin/character/heartbeat", token, snapshot, null);
	}

	/** Closes the session cleanly, so an overnight break is not read as an unwatched gap. */
	public CharacterState characterLogout(String token, CharacterSnapshot snapshot) throws IOException
	{
		return characterCall("/api/v1/plugin/character/logout", token, snapshot, null);
	}

	/**
	 * Asks the server to set this character's mode. The server decides — it checks the account type,
	 * that the account is untouched, and what Jagex's hiscores say — so a refusal arrives as
	 * {@link ModeRefused} carrying a sentence written for the player.
	 */
	public void selectMode(String token, CharacterSnapshot snapshot, String mode, boolean acknowledgedRules)
		throws IOException
	{
		JsonObject body = snapshot.toJson(pluginVersion);
		body.addProperty("mode", mode);
		body.addProperty("acknowledgedRules", acknowledgedRules);
		characterCall("/api/v1/plugin/character/mode", token, snapshot, body);
	}

	/** The shared request/parse for every character endpoint — one place that maps refusals to types. */
	private CharacterState characterCall(
		String path, String token, CharacterSnapshot snapshot, @Nullable JsonObject overrideBody)
		throws IOException
	{
		JsonObject body = overrideBody != null ? overrideBody : snapshot.toJson(pluginVersion);
		Request request = authorised(
			new Request.Builder()
				.url(url(path))
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.header("Accept", "application/json"),
			token).build();

		try (Response response = execute(request))
		{
			JsonObject json = response.body() == null
				? new JsonObject()
				: parseLenient(response.body().string());

			if (response.isSuccessful())
			{
				JsonObject character = json.getAsJsonObject("character");
				return CharacterState.of(character != null ? character : json);
			}

			String message = asString(json, "message");
			if (message.isEmpty())
			{
				message = asString(json, "error");
			}
			switch (response.code())
			{
				case 401:
					// A *specific* type, not a bare IOException: the caller has to be able
					// to tell "this token is dead" from "the network hiccuped". They were
					// indistinguishable before, so a revoked or deleted account left the
					// plugin retrying for ever while still claiming to be linked.
					throw new Unauthorised(message.isEmpty() ? "Link your account to play." : message);
				case 404:
				case 409:
					if ("claimed_by_another_account".equals(asString(json, "status"))
						|| "claimed_by_another_account".equals(asString(json, "error")))
					{
						throw new CharacterClaimed(message.isEmpty()
							? "That character is linked to a different Card Exchange account." : message);
					}
					if ("released".equals(asString(json, "status")))
					{
						throw new CharacterReleased(message.isEmpty()
							? "This character was unlinked from your account." : message);
					}
					throw new CharacterNotBound(message.isEmpty() ? "Character not linked yet." : message);
				case 422:
					throw new ModeRefused(asString(json, "reason"), message);
				case 423:
					throw new CharacterUnderReview(message.isEmpty()
						? "This character is under review." : message);
				default:
					throw new IOException("Could not reach the exchange (HTTP " + response.code() + ")");
			}
		}
	}

	/** Parses a body that may not be JSON at all, rather than throwing on a proxy's HTML error page. */
	private JsonObject parseLenient(String body)
	{
		try
		{
			JsonObject json = gson.fromJson(body, JsonObject.class);
			return json == null ? new JsonObject() : json;
		}
		catch (RuntimeException ex)
		{
			return new JsonObject();
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

	/**
	 * Authorised, and naming the character to act for. Without the header the server falls back to the
	 * account's sole bound character, which is what keeps an older plugin working — but any request
	 * that could touch a collection should say who it means.
	 */
	private static Request.Builder authorised(Request.Builder builder, String token, @Nullable String rsn)
	{
		Request.Builder authorised = authorised(builder, token);
		return rsn == null || rsn.trim().isEmpty()
			? authorised
			: authorised.header(CHARACTER_HEADER, rsn.trim());
	}

	private static String readPluginVersion()
	{
		String version = ExchangeApiClient.class.getPackage().getImplementationVersion();
		return version == null ? "dev" : version;
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

	/**
	 * A 64-bit number. Total experience on a maxed account passes 2³¹, so anything carrying XP must
	 * read through this — {@link #asInt} would silently truncate it to a negative.
	 */
	private static long asLong(JsonObject json, String key, long fallback)
	{
		if (json == null || !json.has(key) || json.get(key).isJsonNull())
		{
			return fallback;
		}
		try
		{
			return json.get(key).getAsLong();
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
