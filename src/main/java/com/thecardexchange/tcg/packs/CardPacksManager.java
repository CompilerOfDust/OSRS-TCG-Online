package com.thecardexchange.tcg.packs;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Lifecycle for the card-packs feature: the collection orb and interface on the right, the pack-opening
 * orb and ceremony on the left, and the sounds they play. The plugin starts and stops this one thing;
 * the four overlays own their own painting and input.
 */
@Singleton
public class CardPacksManager
{
	private final CardPacksOrb orb;
	private final CardPacksInterface packs;
	private final PackOpeningOrb openingOrb;
	private final PackOpeningInterface opening;
	private final CardSounds sounds;

	@Inject
	CardPacksManager(CardPacksOrb orb, CardPacksInterface packs,
		PackOpeningOrb openingOrb, PackOpeningInterface opening, CardSounds sounds)
	{
		this.orb = orb;
		this.packs = packs;
		this.openingOrb = openingOrb;
		this.opening = opening;
		this.sounds = sounds;
	}

	public void start()
	{
		sounds.start();
		packs.start();
		opening.start();
		orb.start();
		openingOrb.start();
	}

	public void stop()
	{
		openingOrb.shutdown();
		orb.shutdown();
		opening.shutdown();
		packs.shutdown();
		sounds.shutdown();
	}
}
