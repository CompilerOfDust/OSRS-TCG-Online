package com.thecardexchange.tcg.packs;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Pictures for cards. Item cards use the client's own item icon — the game already has it, it's instant
 * and it looks native. NPC cards have no client-side sprite at all, so their wiki artwork is fetched
 * once, in the background, and kept.
 *
 * <p>{@link #imageFor} is called from the render loop, so it never blocks: a miss returns null and
 * queues the fetch, and the next frame that asks will get the picture. Failures are remembered too, so a
 * dead URL is attempted once rather than every frame.
 */
@Slf4j
@Singleton
public class CardArt
{
	/** Wiki art held in memory. The grid only ever shows a few dozen at a time; the cap is for scrolling. */
	private static final int MAX_CACHED = 512;
	private static final int MAX_IN_FLIGHT = 4;

	private final ItemManager itemManager;
	private final OkHttpClient httpClient;

	/** Access-ordered so the least recently drawn art is what gets evicted. */
	private final Map<Integer, BufferedImage> art = Collections.synchronizedMap(
		new LinkedHashMap<Integer, BufferedImage>(64, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> eldest)
			{
				return size() > MAX_CACHED;
			}
		});
	private final Set<Integer> pending = ConcurrentHashMap.newKeySet();
	private final Set<Integer> failed = ConcurrentHashMap.newKeySet();

	@Inject
	CardArt(ItemManager itemManager, OkHttpClient okHttpClient)
	{
		this.itemManager = itemManager;
		this.httpClient = okHttpClient;
	}

	/**
	 * The card's picture, or null if there isn't one yet. Safe to call every frame: item icons come
	 * straight from the client, NPC art is served from cache and otherwise fetched in the background.
	 */
	@Nullable
	public BufferedImage imageFor(CatalogueCard card)
	{
		if (!card.isNpc())
		{
			return card.getGameId() > 0 ? itemManager.getImage(card.getGameId()) : null;
		}

		BufferedImage cached = art.get(card.getId());
		if (cached != null)
		{
			return cached;
		}
		String url = card.getArt();
		if (url == null || url.isEmpty() || failed.contains(card.getId()) || !pending.add(card.getId()))
		{
			return null;
		}
		if (pending.size() > MAX_IN_FLIGHT)
		{
			// Scrolling fast shouldn't open a hundred connections; drop this one and let a later frame ask.
			pending.remove(card.getId());
			return null;
		}
		fetch(card.getId(), url);
		return null;
	}

	/** Forget everything — on logout, or when the catalogue is reloaded. */
	public void clear()
	{
		art.clear();
		failed.clear();
	}

	private void fetch(int cardId, String url)
	{
		Request request = new Request.Builder()
			.url(url)
			.header("Accept", "image/png,image/*")
			.build();

		httpClient.newCall(request).enqueue(new okhttp3.Callback()
		{
			@Override
			public void onFailure(okhttp3.Call call, IOException ex)
			{
				log.debug("Card art fetch failed for {}", url, ex);
				failed.add(cardId);
				pending.remove(cardId);
			}

			@Override
			public void onResponse(okhttp3.Call call, Response response)
			{
				try (Response closing = response)
				{
					BufferedImage image = closing.isSuccessful() && closing.body() != null
						? read(closing.body().byteStream())
						: null;
					if (image != null)
					{
						art.put(cardId, image);
					}
					else
					{
						failed.add(cardId);
					}
				}
				catch (IOException | RuntimeException ex)
				{
					log.debug("Card art decode failed for {}", url, ex);
					failed.add(cardId);
				}
				finally
				{
					pending.remove(cardId);
				}
			}
		});
	}

	@Nullable
	private static BufferedImage read(InputStream stream) throws IOException
	{
		return ImageIO.read(stream);
	}
}
