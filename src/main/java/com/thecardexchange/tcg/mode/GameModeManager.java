package com.thecardexchange.tcg.mode;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import com.thecardexchange.tcg.TheCardExchangeTcgConfig;

/**
 * The character's chosen {@link GameMode}, cached locally.
 *
 * <p><b>Per character, not per install.</b> The mode lives in RuneLite's
 * <em>RSProfile</em> config, which is keyed to the logged-in account — so an
 * ironman running CardMan and a main running Normal on the same computer keep
 * their own answers. The plugin token stays a global key, because that is a
 * credential for the install rather than a property of a character.
 *
 * <p><b>The server decides; this remembers.</b> Eligibility is checked server
 * side (ironman family, untouched account, what the hiscores say) and the choice
 * is final there. This class only caches the answer so the panel can draw
 * instantly on the next login instead of waiting for a round trip — which is why
 * there is no {@code select()} that writes on its own authority.
 *
 * <p>{@link ConfigManager#getRSProfileKey()} is null before login, so a mode
 * genuinely cannot be read on the login screen. The panel has an explicit
 * logged-out state rather than pretending the choice was never made.
 */
@Slf4j
@Singleton
public class GameModeManager
{
	/** Config key we own. Not a user-facing setting — see the class note. */
	static final String GAME_MODE_KEY = "gameMode";

	private final ConfigManager configManager;

	@Inject
	GameModeManager(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/**
	 * The mode cached for the logged-in character, or {@link GameMode#NOT_SELECTED}
	 * when none is cached — including on the login screen, where there is no
	 * character to have a mode.
	 */
	public GameMode getGameMode()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return GameMode.NOT_SELECTED;
		}
		GameMode profileMode = GameMode.fromConfigValue(
			configManager.getRSProfileConfiguration(TheCardExchangeTcgConfig.GROUP, GAME_MODE_KEY));
		if (profileMode.isSelected())
		{
			return profileMode;
		}
		return adoptLegacyMode();
	}

	public boolean isSelected()
	{
		return getGameMode().isSelected();
	}

	/**
	 * Records what the server said this character's mode is — <b>including "none"</b>.
	 *
	 * <p>Write-through only: the server has already decided by the time this is
	 * called, so there is deliberately no eligibility logic and no refusal here.
	 *
	 * <p>The "none" half matters as much as the other. This used to ignore an
	 * unselected mode and keep whatever was cached, which quietly made the cache
	 * authoritative in the one case where it is most likely to be wrong: a mode
	 * cleared or reset on the server, or a character rebound to a fresh account.
	 * The panel would keep showing a mode the server did not have, and the player
	 * would be told to choose one the moment they tried to trade — a contradiction
	 * with no way out from inside the client. An answer of "no mode" now evicts.
	 */
	public void applyServerMode(GameMode mode)
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		if (mode == null || !mode.isSelected())
		{
			reset();
			return;
		}
		configManager.setRSProfileConfiguration(
			TheCardExchangeTcgConfig.GROUP, GAME_MODE_KEY, mode.name());
	}

	/**
	 * Forgets the cached mode for this character. Local only — the server keeps
	 * the real answer, so this is a cache eviction, not a way out of a run.
	 */
	public void reset()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		configManager.unsetRSProfileConfiguration(TheCardExchangeTcgConfig.GROUP, GAME_MODE_KEY);
	}

	/**
	 * Moves a mode chosen before modes were per-character onto the logged-in
	 * profile, once, then drops the old global key. Without this, the first player
	 * to update would see their chooser again as if they had never picked.
	 */
	private GameMode adoptLegacyMode()
	{
		GameMode legacy = GameMode.fromConfigValue(
			configManager.getConfiguration(TheCardExchangeTcgConfig.GROUP, GAME_MODE_KEY));
		if (!legacy.isSelected())
		{
			return GameMode.NOT_SELECTED;
		}
		log.debug("Adopting legacy global game mode {} for this character", legacy);
		configManager.setRSProfileConfiguration(
			TheCardExchangeTcgConfig.GROUP, GAME_MODE_KEY, legacy.name());
		configManager.unsetConfiguration(TheCardExchangeTcgConfig.GROUP, GAME_MODE_KEY);
		return legacy;
	}
}
