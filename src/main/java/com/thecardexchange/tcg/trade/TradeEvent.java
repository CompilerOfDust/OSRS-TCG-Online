package com.thecardexchange.tcg.trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A message pushed by the broker over the trade WebSocket.
 *
 * <ul>
 *   <li>{@code OFFERED} — ack that our offer is now pending ({@link #getOther()} is the target).</li>
 *   <li>{@code INCOMING} — someone offered us a trade ({@link #getOther()} is them).</li>
 *   <li>{@code ACCEPTED} — the trade is on; open the dialogue.</li>
 *   <li>{@code DECLINED} / {@code CANCELLED} — the other side backed out; close the dialogue.</li>
 *   <li>{@code CARDS} — a side's offer changed: {@link #getOther()} is whose it is and
 *       {@link #getCardIds()} is what they're putting up, already validated server-side.</li>
 *   <li>{@code TRADE_ACCEPTED} — {@link #getOther()} accepted the cards on the table.</li>
 *   <li>{@code COMPLETED} — both accepted and the cards moved; {@link #getCardIds()} is what we got.</li>
 *   <li>{@code ERROR} — the action failed ({@link #getReason()}, e.g. "expired").</li>
 * </ul>
 */
public final class TradeEvent
{
	public enum Type
	{
		OFFERED, INCOMING, ACCEPTED, DECLINED, CANCELLED, CARDS, TRADE_ACCEPTED, COMPLETED, ERROR, UNKNOWN
	}

	private final Type type;
	@Nullable
	private final String offerId;
	/** The other player's display name (from `with` / `from` / `to`, whichever the message carried). */
	@Nullable
	private final String other;
	@Nullable
	private final String reason;
	/** The cards on the table for {@code CARDS}; empty otherwise. */
	private final List<Integer> cardIds;

	public TradeEvent(Type type, @Nullable String offerId, @Nullable String other, @Nullable String reason)
	{
		this(type, offerId, other, reason, Collections.emptyList());
	}

	public TradeEvent(Type type, @Nullable String offerId, @Nullable String other, @Nullable String reason,
		List<Integer> cardIds)
	{
		this.type = type;
		this.offerId = offerId;
		this.other = other;
		this.reason = reason;
		this.cardIds = Collections.unmodifiableList(new ArrayList<>(cardIds));
	}

	public List<Integer> getCardIds()
	{
		return cardIds;
	}

	public Type getType()
	{
		return type;
	}

	@Nullable
	public String getOfferId()
	{
		return offerId;
	}

	@Nullable
	public String getOther()
	{
		return other;
	}

	@Nullable
	public String getReason()
	{
		return reason;
	}
}
