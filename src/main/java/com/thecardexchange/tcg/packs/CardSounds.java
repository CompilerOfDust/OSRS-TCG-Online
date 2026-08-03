package com.thecardexchange.tcg.packs;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;

/**
 * The pack-opening sounds: the tear when a pack is clicked, a fanfare when the pull turns out to hold
 * something rare, and a woosh as each card turns over — heavier the better the pull, so you hear a good
 * card before you can read it.
 *
 * <p>Which woosh a card gets is its gem tier: Diamond and Onyx share the second, Zenyte has the third,
 * and everything below plays the first. The pack's own flourish goes by its best card — a curated
 * special over a Zenyte.
 *
 * <p>The clips are WAV rather than the source MP3s because the audio stack has no MP3 decoder — see
 * the plugin's CLAUDE.md for the conversion. Playback goes through RuneLite's own
 * {@link AudioPlayer}: the plugin hub disallows {@code javax.sound} directly, and the shared player
 * already owns the mixer lines, so each sound gets its own and quick flips overlap rather than cutting
 * each other off. Still dispatched on the scheduler, never the render thread — opening a line blocks.
 */
@Slf4j
@Singleton
public class CardSounds
{
	private static final String PATH = "/com/thecardexchange/tcg/";
	private static final String PACK_OPENING = "pack_opening.wav";
	/** Fanfares for what a pack turned out to hold. (The source mp3 spells it "zanyte".) */
	private static final String ZENYTE_OPENING = "zenyte_opening.wav";
	private static final String SPECIAL_OPENING = "special_opening.wav";
	/** The hover tick — a tenth of a second, so sweeping the cursor along a row reads as a run of taps. */
	private static final String TICK = "tick.wav";
	/** The three wooshes, in ladder order: everything below Diamond, then Diamond/Onyx, then Zenyte. */
	private static final String WOOSH_STANDARD = "woosh_1.wav";
	private static final String WOOSH_PREMIUM = "woosh_2.wav";
	private static final String WOOSH_ZENYTE = "woosh_3.wav";
	/** Gem tiers (see {@link CatalogueCard.Tiers}) — Diamond is where the heavier woosh starts. */
	private static final int DIAMOND = 5;
	private static final int ZENYTE = 7;

	private final TheCardExchangeTcgConfig config;
	private final ScheduledExecutorService scheduler;
	private final AudioPlayer audioPlayer;

	@Inject
	CardSounds(TheCardExchangeTcgConfig config, ScheduledExecutorService scheduler,
		AudioPlayer audioPlayer)
	{
		this.config = config;
		this.scheduler = scheduler;
		this.audioPlayer = audioPlayer;
	}

	/**
	 * Nothing to preload: {@link AudioPlayer} reads the resource per play. Kept so the manager's
	 * start/stop pairing stays symmetrical with the rest of the pack UI.
	 */
	void start()
	{
	}

	void shutdown()
	{
	}

	/** The pack tearing open — played the moment the pack is clicked, not when the server answers. */
	public void packOpening()
	{
		play(PACK_OPENING);
	}

	/**
	 * The flourish a pack earns from its best card: a curated special — or a showcase card, which is
	 * treated as one here — outranks a Zenyte, and an ordinary pack gets nothing beyond the tear.
	 *
	 * <p>This can't play on the click — nobody knows what's inside until the server answers — so it
	 * lands with the cards, on top of the tear's tail, and plays under the first flips.
	 */
	public void packContents(List<PackResult.PulledCard> cards)
	{
		boolean special = false;
		boolean zenyte = false;
		for (PackResult.PulledCard pulled : cards)
		{
			// A showcase card earns the special's fanfare without being one — see Showcase
			// for why it borrows the presentation and not the flag.
			special |= pulled.getCard().isSpecial() || Showcase.is(pulled.getCard());
			zenyte |= pulled.getCard().getTier() >= ZENYTE;
		}
		if (special)
		{
			play(SPECIAL_OPENING);
		}
		else if (zenyte)
		{
			play(ZENYTE_OPENING);
		}
	}

	/** The cursor arriving on a card — one tap per card entered, never per frame. */
	public void tick()
	{
		play(TICK);
	}

	/** A card turning over, in the weight its gem tier has earned. */
	public void reveal(int tier)
	{
		play(tier >= ZENYTE ? WOOSH_ZENYTE : tier >= DIAMOND ? WOOSH_PREMIUM : WOOSH_STANDARD);
	}

	/**
	 * Plays a clip, unless the player has turned sounds off or the volume to zero.
	 *
	 * <p>The percentage is converted to decibels here because that is what {@link AudioPlayer} takes;
	 * 100% is 0 dB (unchanged) and the curve falls away logarithmically, which is how loudness is
	 * actually perceived — a linear percentage would sound almost unchanged until it collapsed.
	 *
	 * <p>Failures are logged at debug and swallowed: a machine with no sound device is not a reason to
	 * stop someone opening packs. Caught as {@code Exception} deliberately — naming the audio
	 * exceptions would put {@code javax.sound} back in this file, which is the thing being removed.
	 */
	private void play(String name)
	{
		if (!config.packSounds())
		{
			return;
		}
		int volume = Math.max(0, Math.min(config.packSoundVolume(), 100));
		if (volume == 0)
		{
			return;
		}
		float decibels = (float) (20.0 * Math.log10(volume / 100.0));
		scheduler.execute(() ->
		{
			try
			{
				audioPlayer.play(getClass(), PATH + name, decibels);
			}
			catch (Exception ex)
			{
				log.debug("Could not play the sound {}", name, ex);
			}
		});
	}
}
