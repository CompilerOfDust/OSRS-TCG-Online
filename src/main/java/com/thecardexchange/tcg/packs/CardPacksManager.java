package com.thecardexchange.tcg.packs;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Lifecycle for the card-packs feature: the collection orb and interface on the right, the pack-opening
 * orb and ceremony on the left, the credit balance across the top, and the sounds they play. The plugin
 * starts and stops this one thing; the overlays own their own painting and input.
 */
@Singleton
public class CardPacksManager
{
	private final CardPacksOrb orb;
	private final CardPacksInterface packs;
	private final PackOpeningOrb openingOrb;
	private final PackOpeningInterface opening;
	private final CreditsBanner credits;
	private final CardSounds sounds;

	@Inject
	CardPacksManager(CardPacksOrb orb, CardPacksInterface packs,
		PackOpeningOrb openingOrb, PackOpeningInterface opening, CreditsBanner credits,
		CardSounds sounds)
	{
		this.orb = orb;
		this.packs = packs;
		this.openingOrb = openingOrb;
		this.opening = opening;
		this.credits = credits;
		this.sounds = sounds;
	}

	public void start()
	{
		sounds.start();
		packs.start();
		opening.start();
		orb.start();
		openingOrb.start();
		credits.start();
	}

	public void stop()
	{
		credits.shutdown();
		openingOrb.shutdown();
		orb.shutdown();
		opening.shutdown();
		packs.shutdown();
		sounds.shutdown();
	}

	/**
	 * Shuts every window regardless of what it was doing.
	 *
	 * These interfaces deliberately swallow every mouse and key event inside their
	 * bounds so clicks can't fall through to the game behind them — which means one
	 * left open after a disconnect would eat input at the login screen, with no way
	 * to close it because its own close button is gone with the game world. So
	 * losing the character closes them: there is nothing on screen a logged-out
	 * player could meaningfully act on anyway.
	 */
	public void closeAll()
	{
		opening.close();
		packs.closeAll();
	}
}
