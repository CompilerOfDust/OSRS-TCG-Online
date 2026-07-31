package com.thecardexchange.tcg.packs;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import lombok.extern.slf4j.Slf4j;
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
 * <p>The clips are WAV rather than the source MP3s because {@code javax.sound.sampled} has no MP3
 * decoder — see the plugin's CLAUDE.md for the conversion. Each is read into memory once at start-up
 * and played from a fresh {@link Clip} per sound, so five quick flips overlap instead of cutting each
 * other off; every clip closes its own line when it finishes. Playback happens on the scheduler, never
 * on the render thread — opening a mixer line can block.
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
	/** Clip name → the WAV bytes, read once. Empty for anything that failed to load. */
	private final Map<String, byte[]> clips = new ConcurrentHashMap<>();

	@Inject
	CardSounds(TheCardExchangeTcgConfig config, ScheduledExecutorService scheduler)
	{
		this.config = config;
		this.scheduler = scheduler;
	}

	void start()
	{
		for (String name : new String[]{PACK_OPENING, ZENYTE_OPENING, SPECIAL_OPENING, TICK,
			WOOSH_STANDARD, WOOSH_PREMIUM, WOOSH_ZENYTE})
		{
			load(name);
		}
	}

	void shutdown()
	{
		clips.clear();
	}

	/** The pack tearing open — played the moment the pack is clicked, not when the server answers. */
	public void packOpening()
	{
		play(PACK_OPENING);
	}

	/**
	 * The flourish a pack earns from its best card: a curated special outranks a Zenyte, and an ordinary
	 * pack gets nothing beyond the tear.
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
			special |= pulled.getCard().isSpecial();
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

	private void load(String name)
	{
		try (InputStream in = getClass().getResourceAsStream(PATH + name))
		{
			if (in == null)
			{
				log.debug("Sound {} is missing from the plugin resources", name);
				return;
			}
			clips.put(name, in.readAllBytes());
		}
		catch (IOException ex)
		{
			log.debug("Could not read the sound {}", name, ex);
		}
	}

	private void play(String name)
	{
		if (!config.packSounds())
		{
			return;
		}
		int volume = Math.max(0, Math.min(config.packSoundVolume(), 100));
		byte[] data = clips.get(name);
		if (volume == 0 || data == null)
		{
			return;
		}
		scheduler.execute(() ->
		{
			try (AudioInputStream audio = AudioSystem.getAudioInputStream(
				new BufferedInputStream(new ByteArrayInputStream(data))))
			{
				Clip clip = AudioSystem.getClip();
				// open() buffers the whole clip, so the stream can close underneath it.
				clip.open(audio);
				// Without this every sound would hold a mixer line open until the client exits, and a
				// few dozen flips would exhaust them. The close is handed back to the scheduler rather
				// than run inside the line's own event callback, where some mixers deadlock.
				clip.addLineListener(event ->
				{
					if (event.getType() == LineEvent.Type.STOP)
					{
						scheduler.execute(clip::close);
					}
				});
				applyVolume(clip, volume);
				clip.start();
			}
			catch (UnsupportedAudioFileException | LineUnavailableException | IOException
				| IllegalArgumentException | IllegalStateException ex)
			{
				// A machine with no sound device is not a reason to break opening packs.
				log.debug("Could not play the sound {}", name, ex);
			}
		});
	}

	/** Percentage → decibels, clamped to whatever range this line actually offers. */
	private static void applyVolume(Clip clip, int volume)
	{
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}
		FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		float decibels = (float) (20.0 * Math.log10(volume / 100.0));
		control.setValue(Math.max(control.getMinimum(), Math.min(decibels, control.getMaximum())));
	}
}
