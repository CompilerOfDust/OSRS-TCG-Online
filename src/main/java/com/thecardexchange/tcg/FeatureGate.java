package com.thecardexchange.tcg;

import com.thecardexchange.tcg.account.AccountLinkManager;
import com.thecardexchange.tcg.account.CharacterState;
import com.thecardexchange.tcg.account.CharacterTracker;
import com.thecardexchange.tcg.account.LinkState;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Whether this character may use the game at all, and what to tell them if not.
 *
 * <p><b>One answer, asked everywhere.</b> Packs, the collection view and the orbs that open them all
 * consult this, so they cannot disagree about whether play is allowed. Before it existed the panel
 * showed a hold while the orbs still opened, which reads as the hold not being real.
 *
 * <p>Every reason here is one the <em>server</em> decided — a hold, an unlink, a character claimed by
 * another account, or no link at all. Nothing is judged locally, so a modified client can re-enable
 * its own buttons and still be refused by every endpoint behind them. This is honesty for the player,
 * not a security boundary; the server is the boundary.
 */
@Singleton
public class FeatureGate
{
	private final AccountLinkManager linkManager;
	private final CharacterTracker characterTracker;

	@Inject
	FeatureGate(AccountLinkManager linkManager, CharacterTracker characterTracker)
	{
		this.linkManager = linkManager;
		this.characterTracker = characterTracker;
	}

	/** True when packs and the collection may be opened. */
	public boolean isPlayable()
	{
		return blockedReason() == null;
	}

	/**
	 * Why play is blocked, phrased for the player, or null when it is not.
	 *
	 * <p>Ordered by what the player can act on first. Being unlinked is the most fundamental — nothing
	 * else is even knowable without a link — and a hold is last because it is the one state where
	 * everything else is fine and only the review stands in the way.
	 */
	@Nullable
	public String blockedReason()
	{
		if (linkManager.getState() != LinkState.LINKED)
		{
			return "Your account is not linked. Open the side panel and link it to play.";
		}

		String released = characterTracker.getReleasedMessage();
		if (released != null)
		{
			return released + " Re-link it on your profile to play this character again.";
		}

		String mismatch = characterTracker.getMismatchMessage();
		if (mismatch != null)
		{
			return mismatch;
		}

		CharacterState state = characterTracker.getState();
		if (state.isHeld())
		{
			String message = state.getReviewMessage();
			return message != null ? message
				: "This character is under review. Opening packs and trading are disabled.";
		}

		// Bound and in good standing, or not logged in yet — in the second case
		// there is nothing to block, because there is nothing to play.
		return null;
	}

	/** A short heading for the warning box; the reason above is the body. */
	public String blockedTitle()
	{
		if (linkManager.getState() != LinkState.LINKED)
		{
			return "Not linked";
		}
		if (characterTracker.getReleasedMessage() != null)
		{
			return "Character unlinked";
		}
		if (characterTracker.getMismatchMessage() != null)
		{
			return "Wrong account";
		}
		return "Under review";
	}
}
