package com.thecardexchange.tcg.account;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * One {@code /link/poll} outcome. {@code PENDING} means keep waiting; {@code LINKED}
 * carries the freshly minted token and the account it belongs to; {@code EXPIRED}
 * means the handshake is dead and the plugin should stop and start over.
 */
@Value
public class PollResult
{
	public enum Status { PENDING, LINKED, EXPIRED }

	Status status;
	/** Present only when {@link Status#LINKED}. */
	@Nullable String pluginToken;
	/** Present only when {@link Status#LINKED}. */
	@Nullable LinkedAccount account;

	public static PollResult pending()
	{
		return new PollResult(Status.PENDING, null, null);
	}

	public static PollResult expired()
	{
		return new PollResult(Status.EXPIRED, null, null);
	}

	public static PollResult linked(String pluginToken, LinkedAccount account)
	{
		return new PollResult(Status.LINKED, pluginToken, account);
	}
}
